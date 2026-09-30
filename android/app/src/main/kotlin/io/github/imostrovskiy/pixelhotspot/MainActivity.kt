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
            combine(HotspotService.phone, HotspotService.pairingUntil, AppLog.lines) { p, pairUntil, lines ->
                status.text = render(p, pairUntil)
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
        addView(Button(context).apply { text = label; setOnClickListener { onClick() } })

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
