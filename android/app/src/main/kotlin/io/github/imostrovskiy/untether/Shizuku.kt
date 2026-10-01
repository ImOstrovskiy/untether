package io.github.imostrovskiy.untether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/** Calls a (possibly hidden) public method by name and arity. */
fun Any.call(name: String, vararg args: Any?): Any? =
    javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(this, *args)

/** Receiver for system broadcasts; the exported flag exists (and is required) only since API 33. */
fun registerExported(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    else ctx.registerReceiver(receiver, filter)
}
