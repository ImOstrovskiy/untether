package io.github.imostrovskiy.untether

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** HMAC secret + hotspot credentials. Generated once, kept encrypted with an Android Keystore key. */
class Pairing(val secret: ByteArray, val ssid: String, val pass: String) {
    /** The same secret with a new random network name and password, stored. */
    fun withNewNetwork(ctx: Context): Pairing = generate().let { Pairing(secret, it.ssid, it.pass) }.also { save(ctx, it) }

    /** Value of the `pairing` characteristic: the secret and the network the hotspot uses now. */
    fun encode(net: HotspotNetwork): ByteArray = Cbor.encode(linkedMapOf("k" to secret, "s" to net.ssid, "p" to net.pass))

    companion object {
        private const val ALIAS = "pairing"

        fun load(ctx: Context): Pairing {
            val prefs = ctx.getSharedPreferences("pairing", Context.MODE_PRIVATE)
            runCatching {
                val iv = Base64.decode(prefs.getString("iv", null) ?: return@runCatching null, 0)
                val ct = Base64.decode(prefs.getString("ct", null)!!, 0)
                val plain = cipher(Cipher.DECRYPT_MODE, GCMParameterSpec(128, iv)).doFinal(ct)
                val (ssid, pass) = String(plain, 32, plain.size - 32).split("\n")
                Pairing(plain.copyOf(32), ssid, pass)
            }.onFailure { AppLog.log("Stored pairing unreadable, generating a new one: $it") }
                .getOrNull()?.let { return it }

            val p = generate()
            save(ctx, p)
            AppLog.log("New pairing generated, SSID ${p.ssid}")
            return p
        }

        private fun save(ctx: Context, p: Pairing) {
            val c = cipher(Cipher.ENCRYPT_MODE, null)
            val ct = c.doFinal(p.secret + "${p.ssid}\n${p.pass}".toByteArray())
            ctx.getSharedPreferences("pairing", Context.MODE_PRIVATE).edit()
                .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                .apply()
        }

        private fun generate(): Pairing {
            val rng = SecureRandom()
            val abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return Pairing(
                ByteArray(32).also(rng::nextBytes),
                "Untether-%04X".format(rng.nextInt(0x10000)),
                String(CharArray(16) { abc[rng.nextInt(abc.length)] }),
            )
        }

        private fun cipher(mode: Int, spec: GCMParameterSpec?): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key(), spec) }

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
        }
    }
}
