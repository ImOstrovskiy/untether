package io.github.imostrovskiy.untether

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_PHONE_STATE
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/** Material 3 UI: status, setup banners, phone telemetry, data SIM, clients, Mac, settings, log. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (hasRequired(this)) HotspotService.start(this)
        setContent { AppTheme { MainScreen() } }
    }

    companion object {
        val REQUIRED = arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT)
        val PERMISSIONS = REQUIRED + READ_PHONE_STATE + if (Build.VERSION.SDK_INT >= 33) arrayOf(POST_NOTIFICATIONS) else emptyArray()

        fun hasRequired(ctx: Context) = REQUIRED.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}

/** Dynamic color (Material You) from the wallpaper, following the system dark theme. */
@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val phone by HotspotService.phone.collectAsStateWithLifecycle()
    val settings by HotspotService.settings.collectAsStateWithLifecycle()
    val pairUntil by HotspotService.pairingUntil.collectAsStateWithLifecycle()
    val log by AppLog.lines.collectAsStateWithLifecycle()

    var hasPermissions by remember { mutableStateOf(MainActivity.hasRequired(ctx)) }
    var batteryOptimized by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        hasPermissions = MainActivity.hasRequired(ctx)
        batteryOptimized = !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        onPauseOrDispose {}
    }
    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        hasPermissions = MainActivity.hasRequired(ctx)
        if (hasPermissions) HotspotService.start(ctx)
    }
    LaunchedEffect(Unit) { if (!hasPermissions) permissionLauncher.launch(MainActivity.PERMISSIONS) }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.app_name)) }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasPermissions) item {
                Banner(R.drawable.ic_bluetooth, R.string.perm_title, R.string.perm_body, R.string.grant) {
                    permissionLauncher.launch(MainActivity.PERMISSIONS)
                }
            }
            when (phone?.shizuku) {
                ShizukuStatus.NOT_RUNNING -> item {
                    Banner(R.drawable.ic_shield, R.string.shizuku_not_running, R.string.shizuku_not_running_body, R.string.open_shizuku) {
                        ctx.packageManager.getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID)?.let(ctx::startActivity)
                    }
                }
                ShizukuStatus.NO_PERMISSION -> item {
                    Banner(R.drawable.ic_shield, R.string.shizuku_no_permission, R.string.shizuku_no_permission_body, R.string.grant) {
                        runCatching { Shizuku.requestPermission(0) }
                    }
                }
                else -> {}
            }
            if (batteryOptimized) item {
                Banner(R.drawable.ic_battery_alert, R.string.battery_opt_title, R.string.battery_opt_body, R.string.allow) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
            }
            phone?.let { p ->
                item { HotspotCard(p) }
                if (p.extras.ringing) item {
                    Banner(R.drawable.ic_notifications_active, R.string.ringing, null, R.string.stop) {
                        HotspotService.start(ctx, HotspotService.ACTION_STOP_RING)
                    }
                }
                item { PhoneCard(p.telemetry) }
                item { DataCard(p) }
                item { ClientsCard(p) }
            }
            item { MacCard(pairUntil, phone?.extras?.findMac == true) }
            HotspotService.pairing?.let { item { NetworkCard(it) } }
            item { SettingsCard(settings) }
            item { LogCard(log) }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(
    @DrawableRes icon: Int,
    headline: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Error-container card with one action, for things the user has to fix. */
@Composable
private fun Banner(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int?, @StringRes action: Int, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        InfoRow(icon, stringResource(title), body?.let { stringResource(it) }) {
            TextButton(onClick = onAction) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun HotspotCard(p: PhoneState) {
    val ctx = LocalContext.current
    val h = p.hotspot.hotspot
    val on = h == Hotspot.ON || h == Hotspot.STARTING
    val enabled = p.shizuku == ShizukuStatus.OK && h != Hotspot.STARTING && h != Hotspot.STOPPING
    val container = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val status = when (h) {
        Hotspot.ERROR -> stringResource(R.string.hs_error, p.hotspot.error ?: -1)
        else -> stringResource(hotspotLabel(h)) + (p.hotspot.ssid?.takeIf { h == Hotspot.ON }?.let { " · $it" } ?: "")
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        ListItem(
            modifier = Modifier
                .toggleable(value = on, enabled = enabled, role = Role.Switch) {
                    HotspotService.start(ctx, if (it) HotspotService.ACTION_ON else HotspotService.ACTION_OFF)
                }
                .padding(vertical = 8.dp),
            headlineContent = { Text(stringResource(R.string.hotspot), style = MaterialTheme.typography.titleLarge) },
            supportingContent = { Text(status) },
            leadingContent = {
                Icon(
                    painterResource(if (on) R.drawable.ic_wifi_tethering else R.drawable.ic_wifi_tethering_off),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            },
            trailingContent = {
                Switch(
                    checked = on,
                    onCheckedChange = null, // the whole row toggles
                    enabled = enabled,
                    thumbContent = if (on) {
                        { Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(SwitchDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun PhoneCard(t: Telemetry) = Section(R.string.section_phone) {
    InfoRow(
        if (t.charging) R.drawable.ic_battery_charging_full else R.drawable.ic_battery_full,
        stringResource(R.string.battery),
        stringResource(R.string.battery_value, t.battery) + if (t.charging) " · " + stringResource(R.string.charging) else "",
    )
    t.temperature?.let {
        InfoRow(
            R.drawable.ic_thermostat,
            stringResource(R.string.temperature),
            stringResource(if (it >= HOT_C) R.string.temperature_hot else R.string.temperature_value, it),
        )
    }
    val net = listOf(stringResource(R.string.net_none), "3G", "LTE", "5G NSA", "5G SA").getOrElse(t.net) { "?" }
    InfoRow(R.drawable.ic_cell_tower, stringResource(R.string.network), listOfNotNull(t.operator, net).joinToString(" · "))
    InfoRow(
        R.drawable.ic_signal_cellular_alt,
        stringResource(R.string.signal),
        listOfNotNull(stringResource(R.string.signal_value, t.signal), t.rsrp?.let { "$it dBm" }, t.snr?.let { "SINR $it dB" })
            .joinToString(" · "),
    )
}

@Composable
private fun DataCard(p: PhoneState) = Section(R.string.section_data) {
    val ctx = LocalContext.current
    val sims = p.telemetry.sims
    if (sims.size > 1) {
        Text(
            stringResource(R.string.data_sim),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            sims.forEachIndexed { i, sim ->
                SegmentedButton(
                    selected = sim.id == p.telemetry.dataSim,
                    onClick = {
                        HotspotService.start(ctx, HotspotService.ACTION_SET_DATA_SIM) { putExtra(HotspotService.EXTRA_SUB_ID, sim.id) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, sims.size),
                    enabled = p.shizuku == ShizukuStatus.OK,
                ) { Text(sim.name) }
            }
        }
    } else {
        sims.firstOrNull()?.let { InfoRow(R.drawable.ic_sim_card, stringResource(R.string.data_sim), it.name) }
    }
    OutlinedButton(
        onClick = { HotspotService.start(ctx, HotspotService.ACTION_RECONNECT_DATA) },
        enabled = p.shizuku == ShizukuStatus.OK,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(18.dp))
        Text(stringResource(R.string.reconnect_data), Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ClientsCard(p: PhoneState) = Section(R.string.section_clients) {
    val ctx = LocalContext.current
    if (p.clients.isEmpty()) InfoRow(R.drawable.ic_devices, stringResource(R.string.no_clients))
    p.clients.forEach { c ->
        InfoRow(R.drawable.ic_laptop_mac, c.name ?: c.mac, listOfNotNull(c.ip, c.mac.takeIf { c.name != null }).joinToString(" · ")) {
            IconButton(onClick = { HotspotService.start(ctx, HotspotService.ACTION_BLOCK) { putExtra(HotspotService.EXTRA_MAC, c.mac) } }) {
                Icon(painterResource(R.drawable.ic_block), stringResource(R.string.block))
            }
        }
    }
    if (p.extras.blocked > 0) {
        TextButton(onClick = { HotspotService.start(ctx, HotspotService.ACTION_UNBLOCK_ALL) }, Modifier.padding(horizontal = 8.dp)) {
            Icon(painterResource(R.drawable.ic_lock_open), null, Modifier.size(18.dp))
            Text(stringResource(R.string.unblock_all, p.extras.blocked), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun MacCard(pairUntil: Long, findingMac: Boolean) = Section(R.string.section_mac) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(pairUntil) {
        while (SystemClock.elapsedRealtime() < pairUntil) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
        now = SystemClock.elapsedRealtime()
    }
    val left = ((pairUntil - now) / 1000).toInt()
    InfoRow(
        R.drawable.ic_add_link,
        stringResource(R.string.pair_mac),
        if (left > 0) stringResource(R.string.pairing_open, left) else stringResource(R.string.pair_mac_body),
    ) {
        FilledTonalButton(onClick = { HotspotService.start(ctx, HotspotService.ACTION_PAIR) }, enabled = left <= 0) {
            Text(stringResource(R.string.pair_button))
        }
    }
    InfoRow(R.drawable.ic_campaign, stringResource(R.string.find_mac), stringResource(R.string.find_mac_body)) {
        FilledTonalButton(onClick = { HotspotService.start(ctx, HotspotService.ACTION_FIND_MAC) }) {
            Text(stringResource(if (findingMac) R.string.stop else R.string.ring_button))
        }
    }
}

@Composable
private fun NetworkCard(credentials: Pairing) = Section(R.string.section_network) {
    val ctx = LocalContext.current
    var visible by rememberSaveable { mutableStateOf(false) }
    fun copy(text: String) = ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text, text))
    InfoRow(R.drawable.ic_wifi_tethering, stringResource(R.string.ssid), credentials.ssid) {
        IconButton(onClick = { copy(credentials.ssid) }) { Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.copy)) }
    }
    InfoRow(R.drawable.ic_key, stringResource(R.string.password), if (visible) credentials.pass else "•".repeat(credentials.pass.length)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    stringResource(if (visible) R.string.hide_password else R.string.show_password),
                )
            }
            IconButton(onClick = { copy(credentials.pass) }) { Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.copy)) }
        }
    }
}

@Composable
private fun SettingsCard(s: HotspotSettings) = Section(R.string.section_settings) {
    val ctx = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    InfoRow(
        R.drawable.ic_timer,
        stringResource(R.string.auto_off),
        if (s.autoOffMinutes > 0) stringResource(R.string.auto_off_minutes, s.autoOffMinutes) else stringResource(R.string.never),
        Modifier.selectable(selected = false, role = Role.Button) { dialog = "auto_off" },
    )
    InfoRow(
        R.drawable.ic_battery_alert,
        stringResource(R.string.battery_guard),
        if (s.batteryMin > 0) stringResource(R.string.battery_guard_value, s.batteryMin) else stringResource(R.string.off),
        Modifier.selectable(selected = false, role = Role.Button) { dialog = "battery" },
    )
    when (dialog) {
        "auto_off" -> ChoiceDialog(
            R.string.auto_off, HotspotService.AUTO_OFF_STEPS, s.autoOffMinutes,
            label = { if (it > 0) stringResource(R.string.auto_off_minutes, it) else stringResource(R.string.never) },
            onDismiss = { dialog = null },
        ) { v -> HotspotService.start(ctx, HotspotService.ACTION_SET_AUTO_OFF) { putExtra(HotspotService.EXTRA_VALUE, v) } }
        "battery" -> ChoiceDialog(
            R.string.battery_guard, HotspotService.BATTERY_MIN_STEPS, s.batteryMin,
            label = { if (it > 0) stringResource(R.string.battery_guard_value, it) else stringResource(R.string.off) },
            onDismiss = { dialog = null },
        ) { v -> HotspotService.start(ctx, HotspotService.ACTION_SET_BATTERY_MIN) { putExtra(HotspotService.EXTRA_VALUE, v) } }
    }
}

/** MD3 simple dialog: choosing an option applies it and closes the dialog. */
@Composable
private fun ChoiceDialog(
    @StringRes title: Int,
    options: List<Int>,
    selected: Int,
    label: @Composable (Int) -> String,
    onDismiss: () -> Unit,
    onChoose: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { v ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = v == selected, role = Role.RadioButton) {
                                onChoose(v)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v == selected, onClick = null)
                        Text(label(v), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LogCard(lines: List<String>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        InfoRow(
            R.drawable.ic_receipt_long,
            stringResource(R.string.section_log),
            modifier = Modifier.toggleable(value = open, role = Role.Button) { open = it },
        ) { Icon(painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more), null) }
        AnimatedVisibility(open) {
            Text(
                lines.takeLast(60).reversed().joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

private const val HOT_C = 42

/** Starts the service after boot (after the first unlock) and after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (MainActivity.hasRequired(context)) HotspotService.start(context)
    }
}
