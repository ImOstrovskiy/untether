package io.github.imostrovskiy.untether

import android.app.Application
import android.util.Log
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // TetheringManager's constructor, stopTethering(int) and TetheredClient are hidden API.
        HiddenApiBypass.setHiddenApiExemptions("L")
    }
}

/** In-memory log shown in the UI, mirrored to logcat. */
object AppLog {
    private val _lines = MutableStateFlow(emptyList<String>())
    val lines: StateFlow<List<String>> = _lines

    fun log(msg: String) {
        Log.i("Untether", msg)
        val line = "${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)} $msg"
        _lines.update { (it + line).takeLast(200) }
    }
}
