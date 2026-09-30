package io.github.imostrovskiy.pixelhotspot

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** BLE protocol v1, see protocol/PROTOCOL.md. */
object Protocol {
    val SERVICE: UUID = UUID.fromString("13f60001-33cc-47ff-8e19-0a93d1157c5b")
    val NONCE: UUID = UUID.fromString("13f60002-33cc-47ff-8e19-0a93d1157c5b")
    val COMMAND: UUID = UUID.fromString("13f60003-33cc-47ff-8e19-0a93d1157c5b")
    val STATE: UUID = UUID.fromString("13f60004-33cc-47ff-8e19-0a93d1157c5b")
    val PAIRING: UUID = UUID.fromString("13f60005-33cc-47ff-8e19-0a93d1157c5b")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val OP_ON = 0x01
    const val OP_OFF = 0x02
    const val OP_STATUS = 0x03

    const val ERR_REJECTED = 0x80
    const val ERR_UNKNOWN_OP = 0x81
    const val ERR_SHIZUKU = 0x82
    const val ERR_PAIRING_CLOSED = 0x83

    const val MAX_STATE = 512

    /** Returns the op if [frame] is `op ‖ nonce ‖ HMAC(key, op ‖ nonce)` for the current [nonce], else null. */
    fun verify(frame: ByteArray, nonce: ByteArray, key: ByteArray): Int? {
        if (frame.size != 49) return null
        val signed = frame.copyOfRange(0, 17)
        val ok = MessageDigest.isEqual(signed.copyOfRange(1, 17), nonce) and
            MessageDigest.isEqual(frame.copyOfRange(17, 49), hmac(key, signed))
        return if (ok) frame[0].toInt() and 0xff else null
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

/** Ordinal = protocol code (`hs`). */
enum class Hotspot { OFF, STARTING, ON, STOPPING, ERROR }

/** Ordinal = protocol code (`shz`). */
enum class ShizukuStatus { OK, NOT_RUNNING, NO_PERMISSION }

data class HotspotState(val hotspot: Hotspot = Hotspot.OFF, val error: Int? = null, val ssid: String? = null)

data class Client(val mac: String, val ip: String?, val name: String?)

/** `net`: 0 none, 1 3G or older, 2 LTE, 3 5G NSA, 4 5G SA. `signal`: 0–4. */
data class Telemetry(val battery: Int = 0, val charging: Boolean = false, val net: Int = 0, val signal: Int = 0)

data class PhoneState(
    val hotspot: HotspotState,
    val clients: List<Client>,
    val telemetry: Telemetry,
    val shizuku: ShizukuStatus,
) {
    /** CBOR for the `state` characteristic; drops clients from the end until it fits [Protocol.MAX_STATE]. */
    fun encode(): ByteArray {
        var shown = clients
        while (true) {
            val m = linkedMapOf<String, Any>("v" to 1, "hs" to hotspot.hotspot.ordinal)
            if (hotspot.hotspot == Hotspot.ERROR) m["err"] = hotspot.error ?: -1
            if (hotspot.hotspot == Hotspot.ON) hotspot.ssid?.let { m["ssid"] = it }
            m["cl"] = shown.map { c ->
                linkedMapOf<String, Any>("mac" to c.mac).apply {
                    c.ip?.let { put("ip", it) }
                    c.name?.let { put("n", it) }
                }
            }
            m["ncl"] = clients.size
            m["bat"] = telemetry.battery
            m["chg"] = telemetry.charging
            m["net"] = telemetry.net
            m["sig"] = telemetry.signal
            m["shz"] = shizuku.ordinal
            val bytes = Cbor.encode(m)
            if (bytes.size <= Protocol.MAX_STATE || shown.isEmpty()) return bytes
            shown = shown.dropLast(1)
        }
    }
}

/** Minimal CBOR (RFC 8949) encoder for the types the protocol uses. */
object Cbor {
    fun encode(value: Any): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            is Boolean -> out.write(if (v) 0xf5 else 0xf4)
            is Int, is Long -> (v as Number).toLong().let { if (it >= 0) head(out, 0, it) else head(out, 1, -1 - it) }
            is ByteArray -> { head(out, 2, v.size.toLong()); out.write(v) }
            is String -> v.toByteArray().let { head(out, 3, it.size.toLong()); out.write(it) }
            is List<*> -> { head(out, 4, v.size.toLong()); v.forEach { write(out, it) } }
            is Map<*, *> -> { head(out, 5, v.size.toLong()); v.forEach { (k, x) -> write(out, k); write(out, x) } }
            else -> error("cbor: unsupported ${v?.javaClass}")
        }
    }

    private fun head(out: ByteArrayOutputStream, major: Int, n: Long) {
        val m = major shl 5
        val bytes = when {
            n < 24 -> { out.write(m or n.toInt()); return }
            n < 0x100 -> 1.also { out.write(m or 24) }
            n < 0x10000 -> 2.also { out.write(m or 25) }
            n < 0x100000000 -> 4.also { out.write(m or 26) }
            else -> 8.also { out.write(m or 27) }
        }
        for (i in bytes - 1 downTo 0) out.write((n shr (8 * i)).toInt() and 0xff)
    }
}
