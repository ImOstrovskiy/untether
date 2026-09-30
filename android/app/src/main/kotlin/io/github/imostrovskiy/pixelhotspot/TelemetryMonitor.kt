package io.github.imostrovskiy.pixelhotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Battery, cellular network type and signal level. */
class TelemetryMonitor(private val ctx: Context) {
    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry

    private val telephony = ctx.getSystemService(TelephonyManager::class.java)

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
                i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            _telemetry.update { it.copy(battery = level, charging = plugged) }
        }
    }

    private val radio = object : TelephonyCallback(),
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.SignalStrengthsListener {
        override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) = _telemetry.update { it.copy(net = netCode(d)) }
        override fun onSignalStrengthsChanged(s: SignalStrength) = _telemetry.update { it.copy(signal = s.level) }
    }

    fun open() {
        ctx.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        runCatching { telephony.registerTelephonyCallback(ctx.mainExecutor, radio) }
            .onFailure { AppLog.log("Telephony callback: $it (grant phone permission)") }
    }

    fun close() {
        ctx.unregisterReceiver(battery)
        runCatching { telephony.unregisterTelephonyCallback(radio) }
    }

    private fun netCode(d: TelephonyDisplayInfo) = when {
        d.networkType == TelephonyManager.NETWORK_TYPE_NR -> 4
        d.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            d.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> 3
        d.networkType == TelephonyManager.NETWORK_TYPE_LTE -> 2
        d.networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN ||
            d.networkType == TelephonyManager.NETWORK_TYPE_IWLAN -> 0
        else -> 1
    }
}
