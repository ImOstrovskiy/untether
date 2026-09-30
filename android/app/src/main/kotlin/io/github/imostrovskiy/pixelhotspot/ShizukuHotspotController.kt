package io.github.imostrovskiy.pixelhotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.LinkAddress
import android.net.MacAddress
import android.net.TetheringInterface
import android.net.TetheringManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.IBinder
import android.os.Looper
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
 * Wi-Fi hotspot control through Shizuku: binder calls to the tethering and wifi services run as
 * the shell user. Approach after supershadoe/delta (BSD-3-Clause), see docs/DECISIONS.md (D2).
 *
 * All callbacks arrive on the main thread; call the public methods on the main thread too.
 */
class ShizukuHotspotController(
    private val ctx: Context,
    private val ssid: String,
    private val pass: String,
    autoOffMinutes: Int,
) {
    private val _state = MutableStateFlow(HotspotState())
    val state: StateFlow<HotspotState> = _state

    private val _clients = MutableStateFlow(emptyList<Client>())
    val clients: StateFlow<List<Client>> = _clients

    private val _shizuku = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val shizuku: StateFlow<ShizukuStatus> = _shizuku

    /** Number of clients on the hotspot's blocklist. */
    private val _blocked = MutableStateFlow(0)
    val blocked: StateFlow<Int> = _blocked

    /** 0 = never. Applied to the system hotspot config. */
    var autoOffMinutes = autoOffMinutes
        set(value) {
            field = value
            syncConfig()
        }

    private var tm: TetheringManager? = null
    private var wm: WifiManager? = null // bound to the Shizuku-wrapped wifi service
    private var configSynced = false
    private var eventsRegistered = false
    private var tetheredSsid: String? = null

    /** TetheringManager/WifiManager take callerPkg from the context; it must match the calling uid (shell). */
    private val shellContext = object : ContextWrapper(ctx) {
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
    }

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
            onApState(ctx.getSystemService(WifiManager::class.java).call("getWifiApState") as Int)
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
        if (!configSynced) syncConfig()
        try {
            val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI)
                // Normally the system config already holds our SSID/passphrase (same as quick settings);
                // if it could not be written, carry the config in the request.
                .apply { if (!configSynced) setSoftApConfiguration(desiredConfig(null)) }
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
            tm.call("stopTethering", TetheringManager.TETHERING_WIFI)
        } catch (e: Throwable) {
            AppLog.log("stopTethering threw $e; fallback cmd wifi stop-softap")
            shell("cmd", "wifi", "stop-softap")
        }
    }

    /** Adds [mac] to the hotspot blocklist; a connected client is dropped right away. */
    fun block(mac: MacAddress) = editBlocklist { (it + mac).distinct() }.also { AppLog.log("Blocked $mac: $it") }

    fun unblockAll() = editBlocklist { emptyList() }.also { AppLog.log("Blocklist cleared: $it") }

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
        if (s != ShizukuStatus.OK) {
            tm = null
            wm = null
            configSynced = false
        }
        if (s != _shizuku.value) AppLog.log("Shizuku: $s")
        _shizuku.value = s
    }

    private fun connect() {
        try {
            val binder = Supplier<IBinder?> { shizukuBinder("tethering") }
            val manager = TetheringManager::class.java
                .getConstructor(Context::class.java, Supplier::class.java)
                .newInstance(shellContext, binder)
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
        try {
            val service = Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, shizukuBinder("wifi"))
            // (Context, IWifiManager) on Android 17; AOSP main adds a Looper.
            val ctor = WifiManager::class.java.constructors.first { it.parameterCount in 2..3 && it.parameterTypes[0] == Context::class.java }
            val args = arrayOf<Any?>(shellContext, service, Looper.getMainLooper()).copyOf(ctor.parameterCount)
            wm = ctor.newInstance(*args) as WifiManager
            syncConfig()
        } catch (e: Throwable) {
            AppLog.log("WifiManager over Shizuku failed: $e")
        }
    }

    private fun shizukuBinder(name: String) = SystemServiceHelper.getSystemService(name)?.let { ShizukuBinderWrapper(it) }

    /**
     * Writes our SSID, passphrase, a persistent (stable) BSSID and the auto-off timer into the system
     * hotspot config, keeping everything else. Quick settings then start the same network the Mac knows.
     */
    private fun syncConfig() {
        val wm = wm ?: return
        configSynced = runCatching {
            val current = wm.call("getSoftApConfiguration") as SoftApConfiguration
            val wanted = desiredConfig(current)
            if (wanted != current) {
                require(wm.call("setSoftApConfiguration", wanted) == true) { "rejected by the system" }
                AppLog.log("Hotspot config written: SSID $ssid, auto-off ${autoOffMinutes.takeIf { it > 0 }?.let { "$it min" } ?: "never"}")
            }
            _blocked.value = blocklist(wanted).size
            true
        }.onFailure { AppLog.log("Hotspot config sync failed: $it") }.getOrDefault(false)
    }

    private fun desiredConfig(base: SoftApConfiguration?): SoftApConfiguration {
        val b = base?.let { SoftApConfiguration.Builder::class.java.getConstructor(SoftApConfiguration::class.java).newInstance(it) }
            ?: SoftApConfiguration.Builder()
        b.setWifiSsid(WifiSsid.fromBytes(ssid.toByteArray()))
            .setPassphrase(pass, SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
            // Fixed 5 GHz channel 36 (non-DFS almost everywhere). With automatic selection the channel
            // moves between sessions and the Mac's cached network no longer matches (seen: 36 -> 40, +12 s).
            .setChannels(SparseIntArray().apply { put(SoftApConfiguration.BAND_5GHZ, 36) })
        // Hidden setters. The default since Android 13 is a new random BSSID every session, which makes
        // macOS treat each session as a new network and scan for it; persistent keeps it per SSID.
        b.call("setMacRandomizationSetting", RANDOMIZATION_PERSISTENT)
        b.call("setAutoShutdownEnabled", autoOffMinutes > 0)
        if (autoOffMinutes > 0) b.call("setShutdownTimeoutMillis", autoOffMinutes * 60_000L)
        return b.build()
    }

    private fun editBlocklist(edit: (List<MacAddress>) -> List<MacAddress>): Boolean {
        val wm = wm ?: return false
        return runCatching {
            val current = wm.call("getSoftApConfiguration") as SoftApConfiguration
            val list = edit(blocklist(current))
            val b = SoftApConfiguration.Builder::class.java.getConstructor(SoftApConfiguration::class.java).newInstance(current)
            b.call("setBlockedClientList", list)
            require(wm.call("setSoftApConfiguration", b.build()) == true) { "rejected by the system" }
            _blocked.value = list.size
        }.onFailure { AppLog.log("Blocklist update failed: $it") }.isSuccess
    }

    @Suppress("UNCHECKED_CAST")
    private fun blocklist(c: SoftApConfiguration) = c.call("getBlockedClientList") as List<MacAddress>

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

    /** Calls a (possibly hidden) public method by name and arity. */
    private fun Any.call(name: String, vararg args: Any?): Any? =
        javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(this, *args)

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
        const val RANDOMIZATION_PERSISTENT = 1
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
