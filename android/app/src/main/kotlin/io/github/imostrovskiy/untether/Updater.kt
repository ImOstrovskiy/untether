package io.github.imostrovskiy.untether

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** New releases from GitHub: looked up at start and every 12 hours, installed through the system installer. */
object Updater {
    data class Update(val version: String, val apk: String, val page: String)

    val available = MutableStateFlow<Update?>(null)
    val installing = MutableStateFlow(false)

    private const val LATEST = "https://api.github.com/repos/ImOstrovskiy/untether/releases/latest"

    /** Blocks; call it off the main thread. */
    fun check(ctx: Context) {
        runCatching {
            val release = JSONObject(open(LATEST, api = true).bufferedReader().use { it.readText() })
            val version = release.getString("tag_name").removePrefix("v")
            val assets = release.getJSONArray("assets")
            val apk = (0 until assets.length()).map(assets::getJSONObject)
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
            val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
            available.value = if (apk != null && newer(version, current)) Update(version, apk, release.getString("html_url")) else null
            available.value?.let { AppLog.log("Update available: ${it.version}") }
        }.onFailure { AppLog.log("Update check failed: $it") }
    }

    /** Downloads the APK and hands it to the system installer, which asks the user to confirm. Blocks. */
    fun install(ctx: Context, update: Update) {
        installing.value = true
        try {
            val file = File(ctx.cacheDir, "update.apk")
            open(update.apk).use { input -> file.outputStream().use { input.copyTo(it) } }
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(ctx.packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("untether.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Mutable: the installer adds the status and its confirmation intent.
                val done = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, UpdateReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(done.intentSender)
            }
            file.delete()
            AppLog.log("Update ${update.version} downloaded")
        } catch (e: Exception) {
            AppLog.log("Update failed: $e")
        } finally {
            installing.value = false
        }
    }

    private fun open(url: String, api: Boolean = false): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "Untether-Android")
        if (api) c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${c.responseCode}")
        return c.inputStream
    }

    /** Release version [v] is newer than [current]; both plain x.y.z, so development builds are left alone. */
    fun newer(v: String, current: String): Boolean {
        fun parse(s: String) = s.split(".").takeIf { it.size == 3 }?.map { it.toIntOrNull() ?: return null }
        val a = parse(v) ?: return false
        val b = parse(current) ?: return false
        return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x > y } ?: false
    }
}

/** The system installer's answers: first it needs the user's confirmation, then it reports the outcome. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.log("Update installed")
            else -> AppLog.log("Update not installed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)} ($status)")
        }
    }
}
