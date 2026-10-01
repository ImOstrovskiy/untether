package io.github.imostrovskiy.untether

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.LinkAddress
import android.net.MacAddress
import android.net.TetheringInterface
import android.net.TetheringManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.SparseIntArray
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.function.Supplier
import kotlin.system.exitProcess

/**
 * The privileged half of hotspot control: a Shizuku user service, that is a process Shizuku starts
 * from this APK as the shell user. Hidden API checks are off in such processes, so the framework's
 * tethering and wifi internals are called directly, with the shell user's permissions, and the app
 * itself needs no hidden API at all. Approach after supershadoe/delta (BSD-3-Clause), see
 * docs/DECISIONS.md (D2).
 *
 * NewApi: TetheringManager (with TetheringRequest and the event callback) and
 * SoftApConfiguration.Builder became public in API 36 but exist as @SystemApi since API 30. What
 * really is newer is guarded by SDK_INT below. MissingPermission: the shell user holds them.
 */
@SuppressLint("NewApi", "InlinedApi", "MissingPermission", "PrivateApi")
class HotspotShell(private val ctx: Context) : IHotspotShell.Stub() {
    @Volatile private var events: IHotspotEvents? = null
    private var tm: TetheringManager? = null
    private var wm: WifiManager? = null

    /** TetheringManager/WifiManager take callerPkg from the context; it must match the calling uid (shell). */
    private val shellContext = object : ContextWrapper(ctx) {
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
    }

    private val tetheringEvents = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(TetheringManager.TetheringEventCallback::class.java),
    ) { proxy, method, args -> onTetheringEvent(proxy, method, args) } as TetheringManager.TetheringEventCallback

    override fun destroy() = exitProcess(0)

    @Synchronized
    override fun open(events: IHotspotEvents): String? = attempt {
        this.events = events
        if (tm == null) {
            val tethering = service("tethering")
            tm = (TetheringManager::class.java.getConstructor(Context::class.java, Supplier::class.java)
                .newInstance(shellContext, Supplier { tethering }) as TetheringManager)
                .apply { registerTetheringEventCallback(ctx.mainExecutor, tetheringEvents) }
        }
        if (wm == null) {
            val wifi = asInterface("android.net.wifi.IWifiManager", service("wifi"))
            // (Context, IWifiManager) on Android 17; AOSP main adds a Looper.
            val ctor = WifiManager::class.java.constructors.first { it.parameterCount in 2..3 && it.parameterTypes[0] == Context::class.java }
            wm = ctor.newInstance(*arrayOf<Any?>(shellContext, wifi, Looper.getMainLooper()).copyOf(ctor.parameterCount)) as WifiManager
        }
    }

    @Synchronized
    override fun apState(): Int = runCatching { wm!!.call("getWifiApState") as Int }.getOrDefault(-1)

    /**
     * Writes our SSID, passphrase, a persistent (stable) BSSID and the auto-off timer into the system
     * hotspot config, keeping everything else. Quick settings then start the same network the Mac knows.
     */
    @Synchronized
    override fun syncConfig(ssid: String?, pass: String?, autoOffMinutes: Int): String? = attempt {
        val wm = wm!!
        val current = wm.call("getSoftApConfiguration") as SoftApConfiguration
        val wanted = desiredConfig(current, ssid, pass, autoOffMinutes)
        if (wanted != current) {
            require(wm.call("setSoftApConfiguration", wanted) == true) { "rejected by the system" }
            log("Hotspot config written: SSID ${ssidOf(wanted)}, auto-off ${autoOffMinutes.takeIf { it > 0 }?.let { "$it min" } ?: "never"}")
        }
        events?.onBlocked(blocklist(wanted).size)
        events?.onNetwork(ssidOf(wanted), wanted.passphrase.orEmpty())
    }

    @Synchronized
    override fun startTethering(ssid: String, pass: String, autoOffMinutes: Int, withConfig: Boolean): String? = attempt {
        try {
            val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI)
                // Normally the system config already holds our SSID/passphrase (same as quick settings);
                // if it could not be written, carry the config in the request.
                .apply { if (withConfig && Build.VERSION.SDK_INT >= 36) setSoftApConfiguration(desiredConfig(null, ssid, pass, autoOffMinutes)) }
                .build()
            tm!!.startTethering(request, ctx.mainExecutor, object : TetheringManager.StartTetheringCallback {
                override fun onTetheringStarted() = log("Tethering started")
                override fun onTetheringFailed(error: Int) {
                    log("Tethering failed: $error")
                    events?.onStartFailed(error)
                }
            })
        } catch (e: Throwable) {
            log("startTethering threw $e; fallback cmd wifi start-softap (no internet sharing)")
            log("start-softap → exit ${exec(arrayOf("cmd", "wifi", "start-softap", ssid, "wpa3_transition", pass))}")
        }
    }

    @Synchronized
    override fun stopTethering(): String? = attempt {
        try {
            // Unlike stopTethering(TetheringRequest) this also stops a hotspot started from quick settings.
            tm!!.call("stopTethering", TetheringManager.TETHERING_WIFI)
        } catch (e: Throwable) {
            log("stopTethering threw $e; fallback cmd wifi stop-softap")
            log("stop-softap → exit ${exec(arrayOf("cmd", "wifi", "stop-softap"))}")
        }
    }

    @Synchronized
    override fun editBlocklist(mac: String?): String? = attempt {
        val wm = wm!!
        val current = wm.call("getSoftApConfiguration") as SoftApConfiguration
        val list = if (mac == null) emptyList() else (blocklist(current) + MacAddress.fromString(mac)).distinct()
        val b = SoftApConfiguration.Builder::class.java.getConstructor(SoftApConfiguration::class.java).newInstance(current)
        b.call("setBlockedClientList", list)
        require(wm.call("setSoftApConfiguration", b.build()) == true) { "rejected by the system" }
        events?.onBlocked(list.size)
    }

    override fun setDataSim(subId: Int): String? = attempt {
        asInterface("com.android.internal.telephony.ISub", service("isub")).call("setDefaultDataSubId", subId)
    }

    override fun allowedTypes(subId: Int): Long =
        runCatching { telephony().callWithPkg("getAllowedNetworkTypesForReason", subId, REASON_USER) as Long }.getOrDefault(-1L)

    override fun setAllowedTypes(subId: Int, types: Long): String? = attempt {
        require(telephony().callWithPkg("setAllowedNetworkTypesForReason", subId, REASON_USER, types) != false) { "rejected by the system" }
    }

    private fun telephony() = asInterface("com.android.internal.telephony.ITelephony", service("phone"))

    /** ITelephony methods gained a trailing callingPackage on some versions; pass the shell's when asked. */
    private fun Any.callWithPkg(name: String, vararg args: Any?): Any? {
        val m = javaClass.methods.first { it.name == name && it.parameterCount in args.size..args.size + 1 }
        return if (m.parameterCount == args.size) m.invoke(this, *args) else m.invoke(this, *args, SHELL_PACKAGE)
    }

    override fun exec(cmd: Array<String>): Int = runCatching {
        ProcessBuilder(*cmd).redirectErrorStream(true).start().run {
            inputStream.readBytes()
            waitFor()
        }
    }.onFailure { log("${cmd.joinToString(" ")} failed: $it") }.getOrDefault(-1)

    /** Our network (or, with ssid null, the one already set in Android) plus the settings Untether always owns. */
    private fun desiredConfig(base: SoftApConfiguration?, ssid: String?, pass: String?, autoOffMinutes: Int): SoftApConfiguration {
        val b = base?.let { SoftApConfiguration.Builder::class.java.getConstructor(SoftApConfiguration::class.java).newInstance(it) }
            ?: SoftApConfiguration.Builder()
        if (ssid != null) {
            if (Build.VERSION.SDK_INT >= 33) b.setWifiSsid(WifiSsid.fromBytes(ssid.toByteArray())) else b.call("setSsid", ssid)
            b.setPassphrase(pass, SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
                // Fixed 5 GHz channel 36 (non-DFS almost everywhere). With automatic selection the channel
                // moves between sessions and the Mac's cached network no longer matches (seen: 36 -> 40, +12 s).
                .setChannels(SparseIntArray().apply { put(SoftApConfiguration.BAND_5GHZ, 36) })
        }
        // Hidden setters. The default since Android 13 is a new random BSSID every session, which makes
        // macOS treat each session as a new network and scan for it; persistent keeps it per SSID.
        b.call("setMacRandomizationSetting", RANDOMIZATION_PERSISTENT)
        b.call("setAutoShutdownEnabled", autoOffMinutes > 0)
        if (autoOffMinutes > 0) b.call("setShutdownTimeoutMillis", autoOffMinutes * 60_000L)
        return b.build()
    }

    private fun ssidOf(c: SoftApConfiguration): String =
        if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.bytes?.decodeToString().orEmpty() else @Suppress("DEPRECATION") c.ssid.orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun blocklist(c: SoftApConfiguration) = c.call("getBlockedClientList") as List<MacAddress>

    private fun onTetheringEvent(proxy: Any, method: Method, args: Array<Any?>?): Any? = when (method.name) {
        "onClientsChanged" -> {
            // android.net.TetheredClient is hidden API; read it by reflection.
            val wifi = (args!![0] as Collection<*>).filterNotNull().filter { it.call("getTetheringType") == TetheringManager.TETHERING_WIFI }
            val addresses = wifi.map { it.call("getAddresses") as List<*> }
            events?.onClients(
                wifi.map { it.call("getMacAddress").toString() }.toTypedArray(),
                addresses.map { a -> a.firstNotNullOfOrNull { (it?.call("getAddress") as? LinkAddress)?.address?.hostAddress } }.toTypedArray(),
                addresses.map { a -> a.firstNotNullOfOrNull { it?.call("getHostname") as? String } }.toTypedArray(),
            )
            null
        }
        "onTetheredInterfacesChanged" -> {
            // The Set<TetheringInterface> overload carries the config only since API 36.
            (args!![0] as? Set<*>)?.takeIf { Build.VERSION.SDK_INT >= 36 }?.let { ifaces ->
                val wifi = ifaces.filterIsInstance<TetheringInterface>().firstOrNull { it.type == TetheringManager.TETHERING_WIFI }
                events?.onTetheredSsid(wifi?.softApConfiguration?.wifiSsid?.bytes?.decodeToString())
            }
            null
        }
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.get(0)
        "toString" -> "TetheringEvents"
        else -> null
    }

    private fun log(line: String) {
        runCatching { events?.onLog(line) }
    }

    /** Runs [block]; an exception becomes the error string (only a few exception types cross binder). */
    private inline fun attempt(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: Throwable) {
        (if (e is java.lang.reflect.InvocationTargetException) e.targetException else e).toString()
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val RANDOMIZATION_PERSISTENT = 1
        const val REASON_USER = 0 // TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER

        fun service(name: String): IBinder =
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder

        /** `Stub.asInterface(binder)` for a hidden AIDL interface. */
        fun asInterface(aidl: String, binder: IBinder): Any =
            Class.forName("$aidl\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
}
