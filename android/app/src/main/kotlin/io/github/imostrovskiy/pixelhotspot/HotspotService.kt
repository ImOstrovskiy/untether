package io.github.imostrovskiy.pixelhotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.ParcelUuid
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuProvider

/** Foreground service: BLE GATT server + advertising, hotspot control, telemetry. */
class HotspotService : Service() {
    companion object {
        const val ACTION_ON = "io.github.imostrovskiy.pixelhotspot.ON"
        const val ACTION_OFF = "io.github.imostrovskiy.pixelhotspot.OFF"
        const val ACTION_PAIR = "io.github.imostrovskiy.pixelhotspot.PAIR"
        private const val PAIRING_WINDOW_MS = 60_000L
        private const val NOTIFICATION_ID = 1

        /** For the UI (same process). */
        val phone = MutableStateFlow<PhoneState?>(null)
        val pairingUntil = MutableStateFlow(0L)
        @Volatile var pairing: Pairing? = null
            private set

        fun start(ctx: Context, action: String? = null) {
            ctx.startForegroundService(Intent(ctx, HotspotService::class.java).setAction(action))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rng = SecureRandom()
    private val nonce = ByteArray(16).also(rng::nextBytes)
    private lateinit var credentials: Pairing
    private lateinit var hotspot: ShizukuHotspotController
    private lateinit var telemetry: TelemetryMonitor
    private val bt by lazy { getSystemService(BluetoothManager::class.java) }
    private var gatt: BluetoothGattServer? = null
    @Volatile private var stateBytes = ByteArray(0)
    private var notificationText = ""

    // Per connected device (keyed by address).
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtu = ConcurrentHashMap<String, Int>()
    private val longReads = ConcurrentHashMap<String, ByteArray>()
    private val preparedWrites = ConcurrentHashMap<String, ByteArrayOutputStream>()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("service", "Service", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification("Starting…", shizukuLink = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        credentials = Pairing.load(this).also { pairing = it }
        hotspot = ShizukuHotspotController(this, credentials.ssid, credentials.pass).also { it.open() }
        telemetry = TelemetryMonitor(this).also { it.open() }
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        openGatt()
        combine(hotspot.state, hotspot.clients, telemetry.telemetry, hotspot.shizuku, ::PhoneState)
            .onEach(::publish)
            .launchIn(scope)
        AppLog.log("Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ON -> hotspot.start()
            ACTION_OFF -> hotspot.stop()
            ACTION_PAIR -> {
                pairingUntil.value = SystemClock.elapsedRealtime() + PAIRING_WINDOW_MS
                AppLog.log("Pairing window open for 60 s")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(btReceiver)
        closeGatt()
        hotspot.close()
        telemetry.close()
        AppLog.log("Service stopped")
        super.onDestroy()
    }

    private fun publish(p: PhoneState) {
        phone.value = p
        stateBytes = p.encode()
        val ch = gatt?.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.STATE)
        if (ch != null) {
            for (d in subscribers.values) {
                // Too long for one notification: an empty value tells the Mac to read.
                val fits = stateBytes.size <= (mtu[d.address] ?: 23) - 3
                gatt?.notifyCharacteristicChanged(d, ch, false, if (fits) stateBytes else ByteArray(0))
            }
        }
        updateNotification(p)
    }

    private fun updateNotification(p: PhoneState) {
        val text = when (p.shizuku) {
            ShizukuStatus.NOT_RUNNING -> "Shizuku is not running. Tap to start it."
            ShizukuStatus.NO_PERMISSION -> "Shizuku permission missing. Open the app to grant it."
            ShizukuStatus.OK -> "Hotspot ${p.hotspot.hotspot.name.lowercase()}, ${p.clients.size} client(s)"
        }
        if (text == notificationText) return
        notificationText = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, shizukuLink = p.shizuku == ShizukuStatus.NOT_RUNNING))
    }

    private fun notification(text: String, shizukuLink: Boolean): Notification {
        val target = (if (shizukuLink) packageManager.getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID) else null)
            ?: Intent(this, MainActivity::class.java)
        return Notification.Builder(this, "service")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pixel Hotspot")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, target,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    // --- BLE ---

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> openGatt()
                BluetoothAdapter.STATE_TURNING_OFF -> closeGatt()
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = AppLog.log("Advertising failed: $errorCode")
    }

    private fun openGatt() {
        if (gatt != null) return
        if (bt.adapter?.isEnabled != true) return AppLog.log("Bluetooth is off")
        val service = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(Protocol.NONCE, PROPERTY_READ, PERMISSION_READ_ENCRYPTED))
            addCharacteristic(BluetoothGattCharacteristic(Protocol.COMMAND, PROPERTY_WRITE, PERMISSION_WRITE_ENCRYPTED))
            addCharacteristic(
                BluetoothGattCharacteristic(Protocol.STATE, PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ_ENCRYPTED).apply {
                    addDescriptor(BluetoothGattDescriptor(Protocol.CCCD,
                        BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
                },
            )
            addCharacteristic(BluetoothGattCharacteristic(Protocol.PAIRING, PROPERTY_READ, PERMISSION_READ_ENCRYPTED))
        }
        gatt = bt.openGattServer(this, gattCallback)?.apply { addService(service) }
        startAdvertising()
        AppLog.log("GATT server up")
    }

    private fun closeGatt() {
        runCatching { bt.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        gatt?.close()
        gatt = null
        subscribers.clear()
        mtu.clear()
        longReads.clear()
        preparedWrites.clear()
    }

    /** Legacy connectable advertising stops on connect, so it is restarted on every connection change. */
    private fun startAdvertising() {
        val advertiser = bt.adapter?.bluetoothLeAdvertiser ?: return
        advertiser.stopAdvertising(advertiseCallback)
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build(),
            AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(Protocol.SERVICE))
                .setIncludeDeviceName(false)
                .build(),
            advertiseCallback,
        )
    }

    private fun command(frame: ByteArray): Int {
        val op = synchronized(nonce) {
            Protocol.verify(frame, nonce, credentials.secret).also { rng.nextBytes(nonce) }
        }
        val status = when (op) {
            null -> Protocol.ERR_REJECTED
            Protocol.OP_STATUS -> BluetoothGatt.GATT_SUCCESS.also { scope.launch { phone.value?.let(::publish) } }
            Protocol.OP_ON, Protocol.OP_OFF ->
                if (hotspot.shizuku.value != ShizukuStatus.OK) Protocol.ERR_SHIZUKU
                else BluetoothGatt.GATT_SUCCESS.also {
                    scope.launch { if (op == Protocol.OP_ON) hotspot.start() else hotspot.stop() }
                }
            else -> Protocol.ERR_UNKNOWN_OP
        }
        AppLog.log("BLE command op=$op → 0x%02x".format(status))
        return status
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            if (!connected) {
                subscribers.remove(device.address)
                mtu.remove(device.address)
                longReads.remove(device.address)
                preparedWrites.remove(device.address)
            }
            AppLog.log("${device.address} ${if (connected) "connected" else "disconnected"}")
            scope.launch { startAdvertising() }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            this@HotspotService.mtu[device.address] = mtu
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val value = when (ch.uuid) {
                Protocol.NONCE -> synchronized(nonce) { nonce.copyOf() }
                // Long reads come in several requests; serve all of them from the same snapshot.
                Protocol.STATE ->
                    if (offset == 0) stateBytes.also { longReads[device.address] = it }
                    else longReads[device.address] ?: stateBytes
                Protocol.PAIRING ->
                    if (SystemClock.elapsedRealtime() < pairingUntil.value) credentials.encode()
                    else return reply(device, requestId, Protocol.ERR_PAIRING_CLOSED)
                else -> return reply(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
            }
            if (ch.uuid == Protocol.PAIRING && offset == 0) AppLog.log("Pairing read by ${device.address}")
            if (offset > value.size) return reply(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val status = when {
                ch.uuid != Protocol.COMMAND -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                preparedWrite -> {
                    preparedWrites.getOrPut(device.address) { ByteArrayOutputStream() }.write(value)
                    BluetoothGatt.GATT_SUCCESS
                }
                else -> command(value)
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, status, offset, if (preparedWrite) value else null)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = preparedWrites.remove(device.address)
            reply(device, requestId, if (execute && buf != null) command(buf.toByteArray()) else BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            val on = subscribers.containsKey(device.address)
            val value = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers[device.address] = device
            else subscribers.remove(device.address)
            if (responseNeeded) reply(device, requestId, BluetoothGatt.GATT_SUCCESS)
        }

        private fun reply(device: BluetoothDevice, requestId: Int, status: Int) {
            gatt?.sendResponse(device, requestId, status, 0, null)
        }
    }
}
