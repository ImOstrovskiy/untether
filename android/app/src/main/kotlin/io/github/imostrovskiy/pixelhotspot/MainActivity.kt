package io.github.imostrovskiy.pixelhotspot

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_PHONE_STATE
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** Status, Shizuku setup, pairing, manual ON/OFF and the log. Plain views, no extra dependencies. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var autoOff: Button
    private lateinit var batteryMin: Button
    private lateinit var unblock: Button
    private lateinit var stopRing: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f }
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setPadding(0, pad, 0, 0) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            button("Grant permissions") { requestPermissions(PERMISSIONS, 1) }
            button("Grant Shizuku permission") { requestShizuku() }
            button("Disable battery optimization") { askBatteryExemption() }
            button("Pair Mac (60 s)") { HotspotService.start(this@MainActivity, HotspotService.ACTION_PAIR) }
            button("Hotspot ON") { HotspotService.start(this@MainActivity, HotspotService.ACTION_ON) }
            button("Hotspot OFF") { HotspotService.start(this@MainActivity, HotspotService.ACTION_OFF) }
            autoOff = button("") { HotspotService.start(this@MainActivity, HotspotService.ACTION_CYCLE_AUTO_OFF) }
            batteryMin = button("") { HotspotService.start(this@MainActivity, HotspotService.ACTION_CYCLE_BATTERY_MIN) }
            unblock = button("") { HotspotService.start(this@MainActivity, HotspotService.ACTION_UNBLOCK_ALL) }
            stopRing = button("Stop ringing") { HotspotService.start(this@MainActivity, HotspotService.ACTION_STOP_RING) }
            addView(log)
        }
        setContentView(ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        })

        if (hasRequired(this)) HotspotService.start(this) else requestPermissions(PERMISSIONS, 1)
        scope.launch {
            combine(HotspotService.phone, HotspotService.pairingUntil, HotspotService.settings, AppLog.lines) { p, pairUntil, s, lines ->
                status.text = render(p, pairUntil)
                autoOff.text = "Auto-off when idle: ${if (s.autoOffMinutes > 0) "${s.autoOffMinutes} min" else "never"}"
                batteryMin.text = "Battery guard: ${if (s.batteryMin > 0) "below ${s.batteryMin}%" else "off"}"
                val blocked = p?.extras?.blocked ?: 0
                unblock.text = "Unblock all ($blocked)"
                unblock.visibility = if (blocked > 0) View.VISIBLE else View.GONE
                stopRing.visibility = if (p?.extras?.ringing == true) View.VISIBLE else View.GONE
                log.text = lines.reversed().joinToString("\n")
            }.collect {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasRequired(this)) HotspotService.start(this)
        else AppLog.log("Bluetooth permissions are required")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(p: PhoneState?, pairUntil: Long): String {
        if (p == null) return "Service is not running"
        val t = p.telemetry
        val net = listOf("no network", "3G", "LTE", "5G NSA", "5G SA")[t.net]
        val pairing = HotspotService.pairing ?: return "Service is starting"
        val secondsLeft = (pairUntil - SystemClock.elapsedRealtime()) / 1000
        return buildString {
            appendLine("Shizuku: ${p.shizuku.name.lowercase().replace('_', ' ')}")
            append("Hotspot: ${p.hotspot.hotspot.name.lowercase()}")
            p.hotspot.error?.let { append(" (code $it)") }
            appendLine()
            p.clients.forEach { appendLine("  • ${it.name ?: it.mac} ${it.ip ?: ""}") }
            appendLine("Battery ${t.battery}%${if (t.charging) " charging" else ""} · $net · signal ${t.signal}/4")
            val radio = listOfNotNull(t.operator, t.rsrp?.let { "$it dBm" }, t.snr?.let { "SINR $it dB" })
            if (radio.isNotEmpty()) appendLine(radio.joinToString(" · "))
            appendLine("SSID ${pairing.ssid} · password ${pairing.pass}")
            if (secondsLeft > 0) appendLine("Pairing window open (${secondsLeft}s left when last updated)")
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                appendLine("Battery optimization is ON: the service may be killed")
            }
        }
    }

    /** Shizuku shows its dialog; the result reaches the service through its permission listener. */
    private fun requestShizuku() {
        runCatching { Shizuku.requestPermission(0) }.onFailure { AppLog.log("Shizuku is not running: start it first") }
    }

    private fun askBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun LinearLayout.button(label: String, onClick: () -> Unit) =
        Button(context).apply { text = label; setOnClickListener { onClick() } }.also(::addView)

    companion object {
        val REQUIRED = arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT)
        val PERMISSIONS = REQUIRED + arrayOf(POST_NOTIFICATIONS, READ_PHONE_STATE)

        fun hasRequired(ctx: Context) = REQUIRED.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}

/** Starts the service after boot (after the first unlock) and after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (MainActivity.hasRequired(context)) HotspotService.start(context)
    }
}
