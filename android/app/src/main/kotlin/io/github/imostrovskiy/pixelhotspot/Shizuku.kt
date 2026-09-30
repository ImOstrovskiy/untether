package io.github.imostrovskiy.pixelhotspot

import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** A system service binder whose calls run as the shell user (through Shizuku). */
fun shizukuBinder(name: String): IBinder? = SystemServiceHelper.getSystemService(name)?.let { ShizukuBinderWrapper(it) }

/** `Stub.asInterface(shizukuBinder(service))` for a hidden AIDL interface such as `com.android.internal.telephony.ISub`. */
fun shizukuService(service: String, aidl: String): Any =
    Class.forName("$aidl\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, shizukuBinder(service))!!

/** Calls a (possibly hidden) public method by name and arity. */
fun Any.call(name: String, vararg args: Any?): Any? =
    javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(this, *args)

/** Runs a command as the shell user. Shizuku.newProcess is private in API 13 but still the entry point. Blocks. */
fun shizukuShell(vararg cmd: String): Int? = runCatching {
    val newProcess = Shizuku::class.java.getDeclaredMethod(
        "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
    ).apply { isAccessible = true }
    (newProcess.invoke(null, arrayOf(*cmd), null, null) as Process).waitFor()
}.onFailure { AppLog.log("shell ${cmd.joinToString(" ")} failed: $it") }.getOrNull()
