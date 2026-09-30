package io.github.imostrovskiy.untether

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Battery, cellular network type, operator and signal. */
class TelemetryMonitor(private val ctx: Context) {
    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry

    private val telephony = ctx.getSystemService(TelephonyManager::class.java)

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
                i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { it / 10 }
            _telemetry.update { it.copy(battery = level, charging = plugged, temperature = temp) }
        }
    }

    private val radio = object : TelephonyCallback(),
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.SignalStrengthsListener {
        override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) = _telemetry.update { it.copy(net = netCode(d)) }
        override fun onSignalStrengthsChanged(s: SignalStrength) = _telemetry.update { t ->
            // Prefer 5G numbers when the phone reports both (NSA).
            val cells = s.cellSignalStrengths
            val nr = cells.filterIsInstance<CellSignalStrengthNr>().firstOrNull()
            val lte = cells.filterIsInstance<CellSignalStrengthLte>().firstOrNull()
            t.copy(
                signal = s.level,
                operator = telephony.networkOperatorName.ifBlank { null },
                rsrp = listOfNotNull(nr?.ssRsrp, lte?.rsrp).firstOrNull { it != CellInfo.UNAVAILABLE },
                snr = listOfNotNull(nr?.ssSinr, lte?.rssnr).firstOrNull { it != CellInfo.UNAVAILABLE },
            )
        }
    }

    private val subscriptions = ctx.getSystemService(SubscriptionManager::class.java)
    private val simsChanged = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() = refreshSims()
    }
    private val dataSimChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refreshSims()
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE is optional; without it the list stays empty
    fun refreshSims() {
        runCatching {
            val sims = subscriptions.activeSubscriptionInfoList.orEmpty()
                .map { Sim(it.subscriptionId, it.displayName?.toString()?.trim().orEmpty().ifEmpty { "SIM ${it.simSlotIndex + 1}" }) }
            _telemetry.update { it.copy(sims = sims, dataSim = SubscriptionManager.getDefaultDataSubscriptionId().takeIf { id -> id >= 0 }) }
        }.onFailure { AppLog.log("SIM list: $it") }
    }

    fun open() {
        ctx.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerExported(ctx, dataSimChanged, IntentFilter(ACTION_DATA_SIM_CHANGED))
        runCatching { subscriptions.addOnSubscriptionsChangedListener(ctx.mainExecutor, simsChanged) }
        refreshSims()
        runCatching { telephony.registerTelephonyCallback(ctx.mainExecutor, radio) }
            .onFailure { AppLog.log("Telephony callback: $it (grant phone permission)") }
    }

    fun close() {
        ctx.unregisterReceiver(battery)
        ctx.unregisterReceiver(dataSimChanged)
        subscriptions.removeOnSubscriptionsChangedListener(simsChanged)
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

/** Hidden TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED. */
private const val ACTION_DATA_SIM_CHANGED = "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"
