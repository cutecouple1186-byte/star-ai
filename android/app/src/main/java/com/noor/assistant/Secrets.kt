package com.noor.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwords sirf phone mein, Android Keystore ke tale (encrypted) mein. Internet ya server par kabhi nahi jate.
 * Noor ko sirf naam (label) pata hota hai.
 */
object Secrets {
    private const val ALIAS = "noor_secrets_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    private fun store(ctx: Context) = ctx.getSharedPreferences("noor_secrets", Context.MODE_PRIVATE)

    private fun all(ctx: Context): JSONObject = try {
        JSONObject(store(ctx).getString("data", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun put(ctx: Context, label: String, value: String): Boolean {
        val l = label.trim().lowercase()
        if (l.isEmpty() || value.isEmpty()) return false
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)
            val j = all(ctx)
            j.put(l, blob)
            store(ctx).edit().putString("data", j.toString()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun get(ctx: Context, label: String): String? {
        val l = label.trim().lowercase()
        val j = all(ctx)
        var name = if (j.has(l)) l else j.keys().asSequence().firstOrNull { it.contains(l) || l.contains(it) }
        if (name == null) return null
        return try {
            val raw = Base64.decode(j.getString(name), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun labels(ctx: Context): List<String> = all(ctx).keys().asSequence().toList().sorted()

    fun remove(ctx: Context, label: String) {
        val j = all(ctx)
        j.remove(label.trim().lowercase())
        store(ctx).edit().putString("data", j.toString()).apply()
    }
}
