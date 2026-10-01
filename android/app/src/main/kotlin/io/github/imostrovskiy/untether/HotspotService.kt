package io.github.imostrovskiy.untether

import android.annotation.SuppressLint
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
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.MacAddress
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuProvider

/**
 * Foreground service: BLE GATT server + advertising, hotspot control, telemetry.
 *
 * MissingPermission: the service is only started once BLUETOOTH_CONNECT/ADVERTISE are granted
 * (MainActivity, BootReceiver), and revoking them kills the process.
 */
@SuppressLint("MissingPermission")
class HotspotService : Service() {
    companion object {
        const val ACTION_ON = "io.github.imostrovskiy.untether.ON"
        const val ACTION_OFF = "io.github.imostrovskiy.untether.OFF"
        const val ACTION_PAIR = "io.github.imostrovskiy.untether.PAIR"
        const val ACTION_SET_AUTO_OFF = "io.github.imostrovskiy.untether.SET_AUTO_OFF" // extra EXTRA_VALUE, minutes
        const val ACTION_SET_BATTERY_MIN = "io.github.imostrovskiy.untether.SET_BATTERY_MIN" // extra EXTRA_VALUE, %
        const val ACTION_STOP = "io.github.imostrovskiy.untether.STOP"
        const val ACTION_RESUME = "io.github.imostrovskiy.untether.RESUME"
        const val ACTION_SET_STOCK_NETWORK = "io.github.imostrovskiy.untether.SET_STOCK_NETWORK" // extra EXTRA_VALUE, boolean
        const val ACTION_BLOCK = "io.github.imostrovskiy.untether.BLOCK" // extra EXTRA_MAC
        const val EXTRA_VALUE = "value"
        const val EXTRA_MAC = "mac"
        const val ACTION_UNBLOCK_ALL = "io.github.imostrovskiy.untether.UNBLOCK_ALL"
        const val ACTION_STOP_RING = "io.github.imostrovskiy.untether.STOP_RING"
        const val ACTION_FIND_MAC = "io.github.imostrovskiy.untether.FIND_MAC"
        const val ACTION_RECONNECT_DATA = "io.github.imostrovskiy.untether.RECONNECT_DATA"
        const val ACTION_SET_NR = "io.github.imostrovskiy.untether.SET_NR" // extra EXTRA_VALUE, boolean
        const val ACTION_SET_DATA_SIM = "io.github.imostrovskiy.untether.SET_DATA_SIM" // extra EXTRA_SUB_ID
        const val EXTRA_SUB_ID = "sub_id"
        private const val FIND_MAC_MS = 30_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val PAIRING_WINDOW_MS = 60_000L
        private const val RING_MS = 20_000L
        private const val NOTIFICATION_ID = 1
        val AUTO_OFF_STEPS = listOf(10, 30, 60, 0) // minutes, 0 = never
        val BATTERY_MIN_STEPS = listOf(0, 10, 15, 20, 30) // %, 0 = off

        /** For the UI (same process). */
        val phone = MutableStateFlow<PhoneState?>(null)
        val pairingUntil = MutableStateFlow(0L)
        val settings = MutableStateFlow(HotspotSettings())
        /** The network the Mac is given when pairing; null while unknown. */
        val network = MutableStateFlow<HotspotNetwork?>(null)

        /** True while the service runs. */
        val running = MutableStateFlow(false)

        /** The user stopped the service: it stays stopped, also across reboots, until [ACTION_RESUME]. */
        fun stopped(ctx: Context) = ctx.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("stopped", false)

        fun start(ctx: Context, action: String? = null, extras: Intent.() -> Unit = {}) {
            if (action != ACTION_RESUME && stopped(ctx)) return
            ctx.startForegroundService(Intent(ctx, HotspotService::class.java).setAction(action).apply(extras))
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
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ringing = MutableStateFlow(false)
    private val findMac = MutableStateFlow(false)
    private var findMacStop: Job? = null
    private val connected = ConcurrentHashMap<String, BluetoothDevice>()
    private var ringtone: Ringtone? = null
    private var ringStop: Job? = null
    private var batteryGuardFired = false

    // Per connected device (keyed by address).
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtu = ConcurrentHashMap<String, Int>()
    private val longReads = ConcurrentHashMap<String, ByteArray>()
    private val preparedWrites = ConcurrentHashMap<String, ByteArrayOutputStream>()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("service", getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification(getString(R.string.notif_starting), shizukuLink = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        running.value = true
        credentials = Pairing.load(this)
        settings.value = HotspotSettings(prefs.getInt("auto_off", 10), prefs.getInt("battery_min", 0), prefs.getBoolean("stock_network", false))
        hotspot = ShizukuHotspotController(this, credentials.ssid, credentials.pass, settings.value.autoOffMinutes, settings.value.stockNetwork)
            .also { it.open() }
        // Our own network is known without Shizuku; Android's only once the shell service has read it.
        combine(hotspot.network, settings) { n, s -> n ?: HotspotNetwork(credentials.ssid, credentials.pass).takeUnless { s.stockNetwork } }
            .onEach { network.value = it }
            .launchIn(scope)
        telemetry = TelemetryMonitor(this).also { it.open() }
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        openGatt()
        val extras = combine(hotspot.blocked, settings, ringing, findMac, hotspot.nr) { b, s, r, f, nr -> Extras(b, s.batteryMin, r, f, nr) }
        combine(hotspot.state, hotspot.clients, telemetry.telemetry, hotspot.shizuku, extras, ::PhoneState)
            .onEach(::publish)
            .launchIn(scope)
        scope.launch { watchdog() }
        AppLog.log("Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean("stopped", true).apply()
                AppLog.log("Stopped by the user")
                // The BLE link outlives the GATT server: say goodbye so the Mac lets go right away.
                stateBytes = Cbor.encode(linkedMapOf("v" to 1, "bye" to true))
                gatt?.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.STATE)?.let { ch ->
                    subscribers.values.forEach { notify(it, ch, stateBytes) }
                }
                scope.launch {
                    delay(500)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME -> prefs.edit().putBoolean("stopped", false).apply()
            ACTION_ON -> hotspot.start()
            ACTION_OFF -> hotspot.stop()
            ACTION_PAIR -> {
                pairingUntil.value = SystemClock.elapsedRealtime() + PAIRING_WINDOW_MS
                AppLog.log("Pairing window open for 60 s")
            }
            ACTION_SET_AUTO_OFF -> intent.getIntExtra(EXTRA_VALUE, -1).takeIf { it in AUTO_OFF_STEPS }
                ?.let { v -> updateSettings { it.copy(autoOffMinutes = v) } }
            ACTION_SET_BATTERY_MIN -> intent.getIntExtra(EXTRA_VALUE, -1).takeIf { it in BATTERY_MIN_STEPS }
                ?.let { v -> updateSettings { it.copy(batteryMin = v) } }
            ACTION_SET_STOCK_NETWORK -> intent.getBooleanExtra(EXTRA_VALUE, false)
                .let { v -> updateSettings { it.copy(stockNetwork = v) } }
            ACTION_BLOCK -> intent.getStringExtra(EXTRA_MAC)?.let { mac ->
                runCatching { MacAddress.fromString(mac) }.getOrNull()?.let(hotspot::block)
            }
            ACTION_UNBLOCK_ALL -> hotspot.unblockAll()
            ACTION_STOP_RING -> ring(false)
            ACTION_FIND_MAC -> findMac(!findMac.value)
            ACTION_RECONNECT_DATA -> scope.launch { reconnectData() }
            ACTION_SET_DATA_SIM -> intent.getIntExtra(EXTRA_SUB_ID, -1).takeIf { it >= 0 }?.let { setDataSim(it) }
            ACTION_SET_NR -> hotspot.setNr(intent.getBooleanExtra(EXTRA_VALUE, true))
        }
        return START_STICKY
    }

    /**
     * Every minute: re-send the state (the Mac's watchdog expects it) and bring the GATT server and
     * advertising back if they are gone (Bluetooth stack restarts, advertising dropped by the system).
     */
    private suspend fun watchdog() {
        while (scope.isActive) {
            delay(HEARTBEAT_MS)
            if (gatt == null && bt.adapter?.isEnabled == true) {
                AppLog.log("Watchdog: GATT server missing, reopening")
                openGatt()
            } else if (connected.isEmpty()) {
                startAdvertising()
            }
            phone.value?.let(::publish)
        }
    }

    /** Asks the Mac to play a sound; it stops by itself after [FIND_MAC_MS]. */
    private fun findMac(on: Boolean) {
        findMacStop?.cancel()
        if (on) findMacStop = scope.launch {
            delay(FIND_MAC_MS)
            findMac(false)
        }
        if (findMac.value != on) AppLog.log(if (on) "Finding the Mac" else "Find Mac stopped")
        findMac.value = on
    }

    /** Mobile data off and on again, as the shell user. */
    private suspend fun reconnectData() = withContext(Dispatchers.IO) {
        AppLog.log("Reconnecting mobile data")
        hotspot.exec("svc", "data", "disable")
        delay(2_000)
        val rc = hotspot.exec("svc", "data", "enable")
        AppLog.log("Mobile data back on (exit $rc)")
    }

    private fun setDataSim(subId: Int) {
        if (hotspot.setDataSim(subId)) AppLog.log("Data SIM set to $subId")
        telemetry.refreshSims()
    }

    private fun updateSettings(change: (HotspotSettings) -> HotspotSettings) {
        val s = change(settings.value)
        settings.value = s
        prefs.edit().putInt("auto_off", s.autoOffMinutes).putInt("battery_min", s.batteryMin)
            .putBoolean("stock_network", s.stockNetwork).apply()
        if (hotspot.autoOffMinutes != s.autoOffMinutes) hotspot.autoOffMinutes = s.autoOffMinutes
        if (hotspot.stockNetwork != s.stockNetwork) hotspot.stockNetwork = s.stockNetwork
        AppLog.log("Settings: auto-off ${s.autoOffMinutes} min, battery guard ${s.batteryMin}%, ${if (s.stockNetwork) "Android's" else "own"} network")
    }

    // battery > 0: until the first ACTION_BATTERY_CHANGED arrives the level reads 0, which must not trip the guard.
    private fun batteryTooLow(t: Telemetry) =
        settings.value.batteryMin > 0 && t.battery > 0 && !t.charging && t.battery < settings.value.batteryMin

    /** Find-my-phone: alarm sound and vibration, stops by itself after [RING_MS]. */
    private fun ring(on: Boolean) {
        ringStop?.cancel()
        ringtone?.stop()
        ringtone = null
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        vibrator.cancel()
        if (on) {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                isLooping = true
                play()
            }
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
            ringStop = scope.launch {
                delay(RING_MS)
                ring(false)
            }
        }
        if (ringing.value != on) AppLog.log(if (on) "Ringing" else "Ringing stopped")
        ringing.value = on
    }

    override fun onDestroy() {
        ring(false)
        scope.cancel()
        unregisterReceiver(btReceiver)
        findMac(false)
        closeGatt()
        hotspot.close()
        telemetry.close()
        running.value = false
        phone.value = null
        AppLog.log("Service stopped")
        super.onDestroy()
    }

    private fun publish(p: PhoneState) {
        // Battery guard: stop once when the battery drops below the threshold off the charger.
        if (!batteryTooLow(p.telemetry)) batteryGuardFired = false
        else if (p.hotspot.hotspot == Hotspot.ON && !batteryGuardFired) {
            batteryGuardFired = true
            AppLog.log("Battery below ${settings.value.batteryMin}%: stopping the hotspot")
            hotspot.stop()
        }
        phone.value = p
        stateBytes = p.encode()
        val ch = gatt?.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.STATE)
        if (ch != null) {
            for (d in subscribers.values) {
                // Too long for one notification: an empty value tells the Mac to read.
                val fits = stateBytes.size <= (mtu[d.address] ?: 23) - 3
                notify(d, ch, if (fits) stateBytes else ByteArray(0))
            }
        }
        updateNotification(p)
    }

    private fun notify(d: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            gatt?.notifyCharacteristicChanged(d, ch, false, value)
        } else {
            @Suppress("DEPRECATION")
            ch.value = value
            @Suppress("DEPRECATION")
            gatt?.notifyCharacteristicChanged(d, ch, false)
        }
    }

    private fun updateNotification(p: PhoneState) {
        val text = when (p.shizuku) {
            ShizukuStatus.NOT_RUNNING -> getString(R.string.notif_shizuku_not_running)
            ShizukuStatus.NO_PERMISSION -> getString(R.string.notif_shizuku_no_permission)
            ShizukuStatus.OK -> getString(R.string.notif_status, getString(hotspotLabel(p.hotspot.hotspot)), p.clients.size)
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
            .setSmallIcon(R.drawable.ic_untether)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, target,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_close), getString(R.string.stop),
                PendingIntent.getService(this, 1, Intent(this, HotspotService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE),
            ).build())
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
        val cmd = synchronized(nonce) {
            Protocol.verify(frame, nonce, credentials.secret).also { rng.nextBytes(nonce) }
        }
        val ok = BluetoothGatt.GATT_SUCCESS
        val needsShizuku = cmd?.op in setOf(
            Protocol.OP_ON, Protocol.OP_OFF, Protocol.OP_BLOCK, Protocol.OP_UNBLOCK_ALL,
            Protocol.OP_SET_DATA_SIM, Protocol.OP_RECONNECT_DATA, Protocol.OP_SET_NR,
        )
        val status = when {
            cmd == null -> Protocol.ERR_REJECTED
            needsShizuku && hotspot.shizuku.value != ShizukuStatus.OK -> Protocol.ERR_SHIZUKU
            cmd.op == Protocol.OP_ON && batteryTooLow(telemetry.telemetry.value) -> Protocol.ERR_BATTERY_LOW
            cmd.op == Protocol.OP_BLOCK && cmd.arg.size != 6 -> Protocol.ERR_UNKNOWN_OP
            cmd.op == Protocol.OP_SET_DATA_SIM && cmd.arg.size != 4 -> Protocol.ERR_UNKNOWN_OP
            cmd.op == Protocol.OP_SET_NR && cmd.arg.size != 1 -> Protocol.ERR_UNKNOWN_OP
            else -> when (cmd.op) {
                Protocol.OP_ON -> ok.also { scope.launch { hotspot.start() } }
                Protocol.OP_OFF -> ok.also { scope.launch { hotspot.stop() } }
                Protocol.OP_STATUS -> ok.also { scope.launch { phone.value?.let(::publish) } }
                Protocol.OP_RING -> ok.also { scope.launch { ring(!ringing.value) } }
                Protocol.OP_BLOCK -> ok.also { scope.launch { hotspot.block(MacAddress.fromBytes(cmd.arg)) } }
                Protocol.OP_UNBLOCK_ALL -> ok.also { scope.launch { hotspot.unblockAll() } }
                Protocol.OP_SET_DATA_SIM -> ok.also {
                    val subId = java.nio.ByteBuffer.wrap(cmd.arg).int
                    scope.launch { setDataSim(subId) }
                }
                Protocol.OP_RECONNECT_DATA -> ok.also { scope.launch { reconnectData() } }
                Protocol.OP_STOP_FIND_MAC -> ok.also { scope.launch { findMac(false) } }
                Protocol.OP_SET_NR -> ok.also { scope.launch { hotspot.setNr(cmd.arg[0] != 0.toByte()) } }
                else -> Protocol.ERR_UNKNOWN_OP
            }
        }
        AppLog.log("BLE command op=${cmd?.op} → 0x%02x".format(status))
        return status
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            if (connected) this@HotspotService.connected[device.address] = device
            else this@HotspotService.connected.remove(device.address)
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
                Protocol.PAIRING -> when {
                    SystemClock.elapsedRealtime() >= pairingUntil.value -> return reply(device, requestId, Protocol.ERR_PAIRING_CLOSED)
                    else -> credentials.encode(network.value ?: return reply(device, requestId, Protocol.ERR_SHIZUKU))
                }
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

data class HotspotSettings(val autoOffMinutes: Int = 10, val batteryMin: Int = 0, val stockNetwork: Boolean = false)

fun hotspotLabel(h: Hotspot) = when (h) {
    Hotspot.OFF -> R.string.hs_off
    Hotspot.STARTING -> R.string.hs_starting
    Hotspot.ON -> R.string.hs_on
    Hotspot.STOPPING -> R.string.hs_stopping
    Hotspot.ERROR -> R.string.hs_error_short
}
