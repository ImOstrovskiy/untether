package io.github.imostrovskiy.pixelhotspot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden vectors in protocol/testdata are shared with the Go client tests. */
class ProtocolTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(16) { (0xa0 + it).toByte() }

    private fun frame(op: Int, n: ByteArray = nonce, k: ByteArray = key): ByteArray {
        val signed = byteArrayOf(op.toByte()) + n
        return signed + Protocol.hmac(k, signed)
    }

    private fun golden(name: String) = File("../../protocol/testdata/$name").readText().trim()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun commandMatchesGolden() = assertEquals(golden("command_on.hex"), frame(Protocol.OP_ON).hex())
    @Test fun acceptsValidFrame() = assertEquals(Protocol.OP_ON, Protocol.verify(frame(Protocol.OP_ON), nonce, key))
    @Test fun rejectsStaleNonce() = assertNull(Protocol.verify(frame(1, n = ByteArray(16)), nonce, key))
    @Test fun rejectsWrongKey() = assertNull(Protocol.verify(frame(1, k = ByteArray(32)), nonce, key))
    @Test fun rejectsWrongLength() = assertNull(Protocol.verify(frame(1).copyOf(48), nonce, key))
    @Test fun rejectsTamperedOp() = assertNull(Protocol.verify(frame(1).also { it[0] = 2 }, nonce, key))

    @Test fun stateMatchesGolden() {
        val state = PhoneState(
            HotspotState(Hotspot.ON, ssid = "Pixel-1A2B"),
            listOf(Client("aa:bb:cc:dd:ee:ff", "10.42.0.2", "macbook"), Client("11:22:33:44:55:66", null, null)),
            Telemetry(battery = 87, charging = true, net = 3, signal = 4),
            ShizukuStatus.OK,
        )
        assertEquals(golden("state_on.hex"), state.encode().hex())
    }

    @Test fun trimsClientsToFit() {
        val many = List(40) { Client("aa:bb:cc:dd:ee:%02x".format(it), "10.42.0.$it", "some-long-hostname-$it") }
        val state = PhoneState(HotspotState(Hotspot.ON, ssid = "x"), many, Telemetry(), ShizukuStatus.OK)
        val bytes = state.encode()
        assertTrue(bytes.size <= Protocol.MAX_STATE)
        // "ncl" still reports all 40: key 0x63 'n' 'c' 'l', then uint8 40.
        assertTrue(bytes.hex().contains("636e636c1828"))
    }
}
