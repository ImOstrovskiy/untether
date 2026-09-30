package io.github.imostrovskiy.pixelhotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.LinkAddress
import android.net.TetheringInterface
import android.net.TetheringManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.IBinder
import android.util.SparseIntArray
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.function.Supplier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Wi-Fi hotspot control through Shizuku: binder calls to the tethering service run as the shell
 * user. Approach after supershadoe/delta (BSD-3-Clause), see docs/DECISIONS.md (D2).
 *
 * All callbacks arrive on the main thread.
 */
class ShizukuHotspotController(private val ctx: Context, private val ssid: String, private val pass: String) {
    private val _state = MutableStateFlow(HotspotState())
    val state: StateFlow<HotspotState> = _state

    private val _clients = MutableStateFlow(emptyList<Client>())
    val clients: StateFlow<List<Client>> = _clients

    private val _shizuku = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val shizuku: StateFlow<ShizukuStatus> = _shizuku

    private var tm: TetheringManager? = null
    private var eventsRegistered = false
    private var tetheredSsid: String? = null

    private val apReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onApState(i.getIntExtra(EXTRA_AP_STATE, AP_DISABLED))
    }
    private val onBinder = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val onDead = Shizuku.OnBinderDeadListener { refreshShizuku() }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }

    private val events = Proxy.newProxyInstance(
        ctx.classLoader,
        arrayOf(TetheringManager.TetheringEventCallback::class.java),
    ) { proxy, method, args -> onTetheringEvent(proxy, method, args) } as TetheringManager.TetheringEventCallback

    fun open() {
        ctx.registerReceiver(apReceiver, IntentFilter(ACTION_AP_STATE), Context.RECEIVER_EXPORTED)
        runCatching {
            val wm = ctx.getSystemService(WifiManager::class.java)
            onApState(WifiManager::class.java.getMethod("getWifiApState").invoke(wm) as Int)
        }.onFailure { AppLog.log("getWifiApState: $it") }
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        refreshShizuku()
    }

    fun close() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onDead)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        ctx.unregisterReceiver(apReceiver)
        runCatching { if (eventsRegistered) tm?.unregisterTetheringEventCallback(events) }
    }

    fun start() {
        clearError()
        val tm = tm ?: return AppLog.log("start: Shizuku not ready")
        try {
            val config = SoftApConfiguration.Builder()
                .setWifiSsid(WifiSsid.fromBytes(ssid.toByteArray()))
                .setPassphrase(pass, SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
                // One AP, 5 GHz preferred; the default would be 2.4 GHz only.
                .setChannels(SparseIntArray().apply { put(SoftApConfiguration.BAND_2GHZ or SoftApConfiguration.BAND_5GHZ, 0) })
                .build()
            val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI)
                .setSoftApConfiguration(config)
                .build()
            tm.startTethering(request, ctx.mainExecutor, object : TetheringManager.StartTetheringCallback {
                override fun onTetheringStarted() = AppLog.log("Tethering started")
                override fun onTetheringFailed(error: Int) {
                    AppLog.log("Tethering failed: $error")
                    _state.value = HotspotState(Hotspot.ERROR, error)
                }
            })
        } catch (e: Throwable) {
            AppLog.log("startTethering threw $e; fallback cmd wifi start-softap (no internet sharing)")
            shell("cmd", "wifi", "start-softap", ssid, "wpa3_transition", pass)
        }
    }

    fun stop() {
        clearError()
        val tm = tm ?: return AppLog.log("stop: Shizuku not ready")
        try {
            // Hidden; unlike stopTethering(TetheringRequest) it also stops a hotspot started from quick settings.
            TetheringManager::class.java.getMethod("stopTethering", Int::class.javaPrimitiveType)
                .invoke(tm, TetheringManager.TETHERING_WIFI)
        } catch (e: Throwable) {
            AppLog.log("stopTethering threw $e; fallback cmd wifi stop-softap")
            shell("cmd", "wifi", "stop-softap")
        }
    }

    private fun clearError() = _state.update { if (it.hotspot == Hotspot.ERROR) HotspotState() else it }

    private fun onApState(s: Int) = _state.update { cur ->
        when (s) {
            AP_ENABLING -> HotspotState(Hotspot.STARTING)
            AP_ENABLED -> HotspotState(Hotspot.ON, ssid = tetheredSsid)
            AP_DISABLING -> HotspotState(Hotspot.STOPPING)
            // A failed start goes FAILED -> DISABLED; keep the error visible until the next command.
            AP_DISABLED -> if (cur.hotspot == Hotspot.ERROR) cur else HotspotState()
            AP_FAILED -> HotspotState(Hotspot.ERROR, -1)
            else -> cur
        }
    }

    private fun refreshShizuku() {
        val s = when {
            !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
            else -> ShizukuStatus.OK
        }
        if (s == ShizukuStatus.OK && tm == null) connect()
        if (s != ShizukuStatus.OK) tm = null
        if (s != _shizuku.value) AppLog.log("Shizuku: $s")
        _shizuku.value = s
    }

    private fun connect() {
        try {
            // TetheringManager takes callerPkg from the context; it must match the calling uid (shell).
            val shell = object : ContextWrapper(ctx) {
                override fun getOpPackageName() = SHELL_PACKAGE
                override fun getAttributionTag(): String? = null
            }
            val binder = Supplier<IBinder?> {
                SystemServiceHelper.getSystemService("tethering")?.let { ShizukuBinderWrapper(it) }
            }
            val manager = TetheringManager::class.java
                .getConstructor(Context::class.java, Supplier::class.java)
                .newInstance(shell, binder)
            // The event registration lives in system_server and survives a Shizuku restart,
            // so only the first manager registers it.
            if (!eventsRegistered) {
                manager.registerTetheringEventCallback(ctx.mainExecutor, events)
                eventsRegistered = true
            }
            tm = manager
        } catch (e: Throwable) {
            AppLog.log("TetheringManager over Shizuku failed: $e")
        }
    }

    private fun onTetheringEvent(proxy: Any, method: Method, args: Array<Any?>?): Any? = when (method.name) {
        "onClientsChanged" -> {
            _clients.value = (args!![0] as Collection<*>).mapNotNull { runCatching { toClient(it!!) }.getOrNull() }
            null
        }
        "onTetheredInterfacesChanged" -> {
            (args!![0] as? Set<*>)?.let { ifaces ->
                val wifi = ifaces.filterIsInstance<TetheringInterface>()
                    .firstOrNull { it.type == TetheringManager.TETHERING_WIFI }
                tetheredSsid = wifi?.softApConfiguration?.wifiSsid?.bytes?.decodeToString()
                _state.update { if (it.hotspot == Hotspot.ON) it.copy(ssid = tetheredSsid) else it }
            }
            null
        }
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.get(0)
        "toString" -> "TetheringEvents"
        else -> null
    }

    /** android.net.TetheredClient is hidden API; read it by reflection. */
    private fun toClient(c: Any): Client? {
        if (c.call("getTetheringType") != TetheringManager.TETHERING_WIFI) return null
        val addresses = c.call("getAddresses") as List<*>
        return Client(
            mac = c.call("getMacAddress").toString(),
            ip = addresses.firstNotNullOfOrNull { (it?.call("getAddress") as? LinkAddress)?.address?.hostAddress },
            name = addresses.firstNotNullOfOrNull { it?.call("getHostname") as? String },
        )
    }

    private fun Any.call(name: String): Any? = javaClass.getMethod(name).invoke(this)

    /** Shizuku.newProcess is private in API 13; it is still the shell entry point. */
    private fun shell(vararg cmd: String) {
        runCatching {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            val p = newProcess.invoke(null, arrayOf(*cmd), null, null) as Process
            AppLog.log("${cmd.take(3).joinToString(" ")} → exit ${p.waitFor()}")
        }.onFailure { AppLog.log("shell failed: $it") }
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        // Hidden WifiManager constants.
        const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_AP_STATE = "wifi_state"
        const val AP_DISABLING = 10
        const val AP_DISABLED = 11
        const val AP_ENABLING = 12
        const val AP_ENABLED = 13
        const val AP_FAILED = 14
    }
}
