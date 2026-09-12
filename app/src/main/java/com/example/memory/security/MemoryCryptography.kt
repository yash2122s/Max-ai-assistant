package com.example.memory.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MemoryCryptography {
    private const val KEY_ALIAS = "max_memory_crypto_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION_PREFIX = "enc_v1:"

    private val isTesting: Boolean by lazy {
        try {
            Class.forName("org.robolectric.Robolectric")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    private fun getOrCreateSecretKey(): SecretKey {
        cachedSecretKey?.let { return it }

        return synchronized(this) {
            cachedSecretKey?.let { return@synchronized it }

            if (isTesting) {
                // Local software key fallback ONLY for unit test environment isolation
                val testKey = SecretKeySpec(ByteArray(16) { 0x01.toByte() }, "AES")
                cachedSecretKey = testKey
                return@synchronized testKey
            }

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existingKey != null) {
                cachedSecretKey = existingKey
                return@synchronized existingKey
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            val newKey = keyGenerator.generateKey()
            cachedSecretKey = newKey
            newKey
        }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        return "$VERSION_PREFIX$ivBase64:$encryptedBase64"
    }

    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        
        if (!encryptedText.startsWith(VERSION_PREFIX)) {
            // Legacy plaintext record identification (not prefixed)
            return encryptedText
        }
        
        val payload = encryptedText.substring(VERSION_PREFIX.length)
        val parts = payload.split(":")
        if (parts.size != 2) {
            // Unexpected format - quarantine
            return "[DECRYPTION_FAILED: Invalid Payload Format]"
        }
        
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // Decryption failure - quarantine and fail-closed
            android.util.Log.e("MemoryCryptography", "Decryption failed", e)
            "[DECRYPTION_FAILED: Content Quarantined]"
        }
    }
}
