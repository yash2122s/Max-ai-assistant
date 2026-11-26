package com.maxaiagent.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared cryptographic and content-deduplication utilities for MAX Android and Desktop.
 */
object MaxCrypto {
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    private val secureRandom = SecureRandom()

    fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun generateSalt(lengthBytes: Int = 16): ByteArray =
        ByteArray(lengthBytes).also { secureRandom.nextBytes(it) }

    fun encryptAesGcm(plaintext: String, keyBytes: ByteArray): String {
        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            "AES key must be 128, 192, or 256 bits"
        }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.getEncoder().encodeToString(packed)
    }

    fun decryptAesGcm(encodedPayload: String, keyBytes: ByteArray): String {
        val packed = Base64.getDecoder().decode(encodedPayload)
        require(packed.size > IV_LENGTH_BYTES) { "Encrypted payload is too short" }
        val iv = packed.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = packed.copyOfRange(IV_LENGTH_BYTES, packed.size)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }
}
