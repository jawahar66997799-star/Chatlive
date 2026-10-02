package com.jawahar.livesync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores private relay credentials encrypted at rest with a non-exportable
 * Android Keystore AES key. The encrypted payload remains in app-private
 * SharedPreferences; only the shareable public room code stays in RelayConfig prefs.
 */
object SecretStore {
    private const val PREFS = "jls_private_credentials"
    private const val KEY_ALIAS = "jls_private_credentials_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION = "v1"

    private val lock = Any()
    private val processCache = mutableMapOf<String, String?>()

    fun get(context: Context, name: String): String? = synchronized(lock) {
        if (processCache.containsKey(name)) {
            return@synchronized processCache[name]
        }

        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(name, null)
            ?: return@synchronized null.also { processCache[name] = null }

        val value = try {
            decrypt(name, encoded)
        } catch (_: Throwable) {
            null
        }
        processCache[name] = value
        value
    }

    /**
     * Writes the complete supplied secret set in one SharedPreferences commit.
     * Encryption and verification are completed around one commit, so a crypto
     * failure cannot partially replace persisted credentials.
     */
    fun putAll(context: Context, values: Map<String, String>): Boolean = synchronized(lock) {
        try {
            val prepared = values.mapValues { (name, value) ->
                if (value.isBlank()) null else encrypt(name, value)
            }

            // Verify every ciphertext before replacing persisted state.
            val verifiedBeforeCommit = values.all { (name, expected) ->
                val encoded = prepared[name]
                if (expected.isBlank()) encoded == null
                else encoded != null && decrypt(name, encoded) == expected
            }
            if (!verifiedBeforeCommit) return@synchronized false

            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            prepared.forEach { (name, encoded) ->
                if (encoded == null) editor.remove(name) else editor.putString(name, encoded)
            }
            if (!editor.commit()) return@synchronized false

            values.forEach { (name, value) ->
                processCache[name] = value.takeIf { it.isNotBlank() }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun contains(context: Context, name: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(name)

    private fun encrypt(name: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        ).joinToString(":")
    }

    private fun decrypt(name: String, encoded: String): String {
        val parts = encoded.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == VERSION) {
            "Unsupported encrypted credential format."
        }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
