package io.github.imostrovskiy.untether

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.MacAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_EDGE
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_GPRS
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_GSM
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_HSPA
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_NR
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_TD_SCDMA
import android.telephony.TelephonyManager.NETWORK_TYPE_BITMASK_UMTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku

/**
 * Wi-Fi hotspot control through Shizuku. The privileged calls run in [HotspotShell], a Shizuku user
 * service (a process of its own, as the shell user); this side keeps the state and talks to it.
 *
 * Call the public methods on the main thread.
 */
class ShizukuHotspotController(
    private val ctx: Context,
    private var ssid: String,
    private var pass: String,
    autoOffMinutes: Int,
    stockNetwork: Boolean,
) {
    private val _state = MutableStateFlow(HotspotState())
    val state: StateFlow<HotspotState> = _state

    private val _clients = MutableStateFlow(emptyList<Client>())
    val clients: StateFlow<List<Client>> = _clients

    private val _shizuku = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val shizuku: StateFlow<ShizukuStatus> = _shizuku

    /** The mobile data SIM's network mode; null while unknown. */
    private val _radio = MutableStateFlow<RadioMode?>(null)
    val radio: StateFlow<RadioMode?> = _radio

    /** Per SIM, the network types it had before Untether locked it (what Auto goes back to); the hotspot fallback. */
    private val defaults = ctx.getSharedPreferences("network", Context.MODE_PRIVATE)

    /** Number of clients on the hotspot's blocklist. */
    private val _blocked = MutableStateFlow(0)
    val blocked: StateFlow<Int> = _blocked

    /** The network in the system hotspot config, known once Shizuku is ready. */
    private val _network = MutableStateFlow<HotspotNetwork?>(null)
    val network: StateFlow<HotspotNetwork?> = _network

    /** Keep the name, password and band set in Android's hotspot settings instead of ours. */
    var stockNetwork = stockNetwork
        set(value) {
            field = value
            syncConfig()
        }

    /** 0 = never. Applied to the system hotspot config. */
    var autoOffMinutes = autoOffMinutes
        set(value) {
            field = value
            syncConfig()
        }

    private val main = Handler(Looper.getMainLooper())
    private var remote: IHotspotShell? = null
    private var bound = false
    private var closed = false
    private var configSynced = false
    /** Our start is under way: a SoftAP failure then means our config, not someone else's. */
    private var starting = false
    /** The fast config (5 GHz channel 36, WPA3) once failed to start here; the compatible one is used since. */
    private var compatible = defaults.getBoolean(COMPATIBLE_KEY, false)
    @Volatile private var tetheredSsid: String? = null

    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, HotspotShell::class.java.name))
        .daemon(false)
        .processNameSuffix("shell")
        // A new install gets a fresh service process with the new code.
        .version((ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime / 1000).toInt())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val shell = IHotspotShell.Stub.asInterface(binder)
            remote = shell
            if (call("Hotspot shell") { shell.open(events) }) onApState(shell.apState())
            syncConfig()
            refreshMode()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            AppLog.log("Hotspot shell disconnected")
            remote = null
            bound = false
            configSynced = false
            // The process died on its own (Shizuku itself going away is handled by its listeners): bind again.
            if (!closed) main.postDelayed(::refreshShizuku, 1_000)
        }
    }

    /** Arrive on binder threads; the flows are thread-safe. */
    private val events = object : IHotspotEvents.Stub() {
        override fun onLog(line: String) = AppLog.log(line)
        override fun onStartFailed(error: Int) {
            _state.value = HotspotState(Hotspot.ERROR, error)
        }
        override fun onClients(macs: Array<String>, ips: Array<String?>, names: Array<String?>) {
            _clients.value = macs.indices.map { Client(macs[it], ips[it], names[it]) }
        }
        override fun onTetheredSsid(ssid: String?) {
            tetheredSsid = ssid
            _state.update { if (it.hotspot == Hotspot.ON) it.copy(ssid = ssid ?: it.ssid) else it }
        }
        override fun onBlocked(count: Int) {
            _blocked.value = count
        }
        override fun onNetwork(ssid: String, pass: String) {
            _network.value = HotspotNetwork(ssid, pass)
            // Show the network that is really on the air, also when it was set by hand in Android.
            _state.update { if (it.hotspot == Hotspot.ON && tetheredSsid == null) it.copy(ssid = ssid) else it }
        }
    }

    private val apReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onApState(i.getIntExtra(EXTRA_AP_STATE, AP_DISABLED))
    }
    private val onBinder = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val onDead = Shizuku.OnBinderDeadListener { refreshShizuku() }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }

    fun open() {
        registerExported(ctx, apReceiver, IntentFilter(ACTION_AP_STATE))
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        refreshShizuku()
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onDead)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        ctx.unregisterReceiver(apReceiver)
        if (bound) runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
    }

    fun start() {
        clearError()
        val shell = remote ?: return AppLog.log("start: Shizuku not ready")
        // Every time: the config may have been changed by hand in Android since.
        syncConfig()
        starting = true
        call("Start") { shell.startTethering(ssid, pass, autoOffMinutes, !configSynced && !stockNetwork, compatible) }
    }

    /** Writes a new network of our own into the system config, also when Android's settings are in use. */
    fun setNetwork(ssid: String, pass: String) {
        this.ssid = ssid
        this.pass = pass
        val shell = remote ?: return AppLog.log("New network: Shizuku not ready")
        configSynced = call("Hotspot config write") { shell.syncConfig(ssid, pass, autoOffMinutes, compatible) } && !stockNetwork
    }

    /** Our hotspot failed to come up with the fast config: switch to the compatible one for good, once. */
    private fun startCompatible() {
        compatible = true
        defaults.edit().putBoolean(COMPATIBLE_KEY, true).apply()
        AppLog.log("Hotspot failed on 5 GHz channel 36 with WPA3; starting it on any band with WPA2, from now on")
        start()
    }

    fun stop() {
        clearError()
        val shell = remote ?: return AppLog.log("stop: Shizuku not ready")
        call("Stop") { shell.stopTethering() }
    }

    /** Adds [mac] to the hotspot blocklist; a connected client is dropped right away. */
    fun block(mac: MacAddress) = call("Blocklist update") { remote!!.editBlocklist(mac.toString()) }.also { AppLog.log("Blocked $mac: $it") }

    fun unblockAll() = call("Blocklist update") { remote!!.editBlocklist(null) }.also { AppLog.log("Blocklist cleared: $it") }

    fun setDataSim(subId: Int) = call("Data SIM switch") { remote!!.setDataSim(subId) }.also { refreshMode() }

    /** Locks the mobile data SIM to one generation, or gives it back the types it had before (Auto). */
    fun setMode(mode: Int) {
        val shell = remote ?: return AppLog.log("Network mode: Shizuku not ready")
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val key = "default_$subId"
        val current = runCatching { shell.allowedTypes(subId) }.getOrDefault(-1L)
        if (current < 0) return AppLog.log("Network mode: allowed types unreadable")
        if (!defaults.contains(key) && current !in LOCKS.values) defaults.edit().putLong(key, current).apply()
        val wanted = if (mode == Protocol.MODE_AUTO) defaults.getLong(key, ALL_TYPES) else LOCKS[mode] ?: return
        if (call("Network mode") { shell.setAllowedTypes(subId, wanted) }) {
            AppLog.log("Network mode on SIM $subId: ${MODE_NAMES[mode]}")
            if (mode == Protocol.MODE_AUTO) defaults.edit().remove(key).apply()
        }
        refreshMode()
    }

    fun refreshMode() {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val key = "default_$subId"
        val types = runCatching { remote?.allowedTypes(subId) }.getOrNull()?.takeIf { it >= 0 } ?: return run { _radio.value = null }
        val mode = LOCKS.entries.firstOrNull { it.value == types }?.key ?: when {
            !defaults.contains(key) || defaults.getLong(key, 0) == types -> Protocol.MODE_AUTO
            else -> Protocol.MODE_OTHER // changed elsewhere since Untether locked it
        }
        // 5G is on offer where the SIM allows it on its own: not on a 4G-only phone or plan.
        val own = if (defaults.contains(key)) defaults.getLong(key, 0) else types
        _radio.value = RadioMode(mode, own and NETWORK_TYPE_BITMASK_NR != 0L)
    }

    /** Runs a command as the shell user; null if Shizuku is not ready. Blocks. */
    fun exec(vararg cmd: String): Int? = remote?.let { runCatching { it.exec(arrayOf(*cmd)) }.getOrNull() }

    private fun clearError() = _state.update { if (it.hotspot == Hotspot.ERROR) HotspotState() else it }

    /** Logs the failure of a shell call (an error string, or the shell process gone) and returns success. */
    private fun call(what: String, block: () -> String?): Boolean {
        val error = try {
            block()
        } catch (e: Exception) {
            e.toString()
        }
        error?.let { AppLog.log("$what failed: $it") }
        return error == null
    }

    private fun onApState(s: Int) {
        if (s == AP_FAILED && starting && !compatible && !stockNetwork) {
            starting = false
            return startCompatible()
        }
        if (s == AP_ENABLED || s == AP_FAILED || s == AP_DISABLED) starting = false
        if (s == AP_ENABLED) runCatching { remote?.readNetwork() }
        setApState(s)
    }

    private fun setApState(s: Int) = _state.update { cur ->
        when (s) {
            AP_ENABLING -> HotspotState(Hotspot.STARTING)
            // The tethering callback does not always carry the config; the system config is ours anyway.
            AP_ENABLED -> HotspotState(Hotspot.ON, ssid = tetheredSsid ?: _network.value?.ssid)
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
        if (s == ShizukuStatus.OK && !bound) {
            bound = runCatching { Shizuku.bindUserService(serviceArgs, connection) }
                .onFailure { AppLog.log("Hotspot shell: $it") }.isSuccess
        }
        if (s != ShizukuStatus.OK) {
            remote = null
            bound = false
            configSynced = false
        }
        if (s != _shizuku.value) AppLog.log("Shizuku: $s")
        _shizuku.value = s
    }

    private fun syncConfig() {
        val shell = remote ?: return
        configSynced = call("Hotspot config sync") {
            shell.syncConfig(ssid.takeUnless { stockNetwork }, pass.takeUnless { stockNetwork }, autoOffMinutes, compatible)
        }
    }

    private companion object {
        const val COMPATIBLE_KEY = "compatible_hotspot"
        val LTE_TYPES = NETWORK_TYPE_BITMASK_LTE or NETWORK_TYPE_BITMASK_LTE_CA
        val G3_TYPES = NETWORK_TYPE_BITMASK_UMTS or NETWORK_TYPE_BITMASK_HSDPA or NETWORK_TYPE_BITMASK_HSUPA or
            NETWORK_TYPE_BITMASK_HSPA or NETWORK_TYPE_BITMASK_HSPAP or NETWORK_TYPE_BITMASK_TD_SCDMA
        val G2_TYPES = NETWORK_TYPE_BITMASK_GSM or NETWORK_TYPE_BITMASK_GPRS or NETWORK_TYPE_BITMASK_EDGE
        val LOCKS = mapOf(
            Protocol.MODE_5G to (NETWORK_TYPE_BITMASK_NR or LTE_TYPES),
            Protocol.MODE_LTE to LTE_TYPES, Protocol.MODE_3G to G3_TYPES, Protocol.MODE_2G to G2_TYPES,
        )
        /** Auto when nothing was saved (Untether reinstalled while locked). */
        val ALL_TYPES = G2_TYPES or G3_TYPES or LTE_TYPES or NETWORK_TYPE_BITMASK_NR
        val MODE_NAMES = mapOf(
            Protocol.MODE_AUTO to "auto", Protocol.MODE_5G to "5G", Protocol.MODE_LTE to "LTE", Protocol.MODE_3G to "3G", Protocol.MODE_2G to "2G",
        )

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
