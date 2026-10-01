package com.jawahar.livesync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureConfigStore {
    private const val KEY_ALIAS = "jls_relay_credentials_v1"
    private const val PREFIX = "enc1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun protect(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + cipher.iv.size + ciphertext.size)
        packed[0] = cipher.iv.size.toByte()
        System.arraycopy(cipher.iv, 0, packed, 1, cipher.iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + cipher.iv.size, ciphertext.size)
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun unprotect(value: String): String {
        if (value.isBlank()) return ""
        if (!value.startsWith(PREFIX)) return value
        return try {
            val packed = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            if (packed.isEmpty()) return ""
            val ivLength = packed[0].toInt() and 0xFF
            if (ivLength !in 12..16 || packed.size <= 1 + ivLength) return ""
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val ciphertext = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    fun isProtected(value: String): Boolean = value.startsWith(PREFIX)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }
}
