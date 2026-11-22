package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.example.memory.security.MemoryCryptography
import java.security.GeneralSecurityException

internal interface PinCipher {
    fun encrypt(pin: String): String
    fun decrypt(ciphertext: String): String
    fun isCiphertext(value: String): Boolean
}

internal object KeystorePinCipher : PinCipher {
    override fun encrypt(pin: String): String = MemoryCryptography.encrypt(pin)

    override fun decrypt(ciphertext: String): String {
        if (!isCiphertext(ciphertext)) {
            throw GeneralSecurityException("Unsupported PIN ciphertext")
        }
        val plaintext = MemoryCryptography.decrypt(ciphertext)
        if (plaintext.startsWith("[DECRYPTION_FAILED")) {
            throw GeneralSecurityException("PIN decryption failed")
        }
        return plaintext
    }

    override fun isCiphertext(value: String): Boolean =
        value.startsWith(MemoryCryptography.VERSION_PREFIX)
}

class SecuritySettings internal constructor(
    context: Context,
    private val pinCipher: PinCipher
) {
    constructor(context: Context) : this(context, KeystorePinCipher)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyPin()
    }

    var autoUnlockEnabled: Boolean
        get() = prefs.getBoolean("auto_unlock", false)
        set(value) = prefs.edit().putBoolean("auto_unlock", value).apply()

    var autoRelockEnabled: Boolean
        get() = prefs.getBoolean("auto_relock", true)
        set(value) = prefs.edit().putBoolean("auto_relock", value).apply()

    var lockDelayMs: Long
        get() = prefs.getLong("lock_delay", 20000L)
        set(value) = prefs.edit().putLong("lock_delay", value).apply()

    var encryptedPin: String
        get() {
            val stored = prefs.getString(KEY_PIN, "") ?: ""
            if (prefs.getInt(KEY_VERSION, 0) != CURRENT_VERSION) return ""
            return if (pinCipher.isCiphertext(stored)) stored else ""
        }
        set(value) {
            if (value.isEmpty()) {
                persistPin(null)
                return
            }
            val ciphertext = try {
                pinCipher.encrypt(value)
            } catch (e: Exception) {
                throw IllegalStateException("Unable to encrypt PIN", e)
            }
            if (ciphertext.isEmpty()) {
                throw IllegalStateException("Unable to encrypt PIN")
            }
            persistPin(ciphertext)
        }

    var voiceUnlockCodeWord: String
        get() = prefs.getString("voice_unlock_code_word", "open sesame") ?: "open sesame"
        set(value) = prefs.edit().putString("voice_unlock_code_word", value).apply()

    fun getDecryptedPin(): String {
        val stored = encryptedPin
        if (stored.isEmpty()) return ""
        return try {
            pinCipher.decrypt(stored)
        } catch (e: Exception) {
            Log.e(TAG, "Stored PIN could not be decrypted")
            ""
        }
    }

    private fun persistPin(ciphertext: String?) {
        val editor = prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION)
        if (ciphertext == null) {
            editor.remove(KEY_PIN)
        } else {
            editor.putString(KEY_PIN, ciphertext)
        }
        if (!editor.commit()) {
            throw IllegalStateException("Unable to persist encrypted PIN")
        }
    }

    private fun migrateLegacyPin() {
        synchronized(MIGRATION_LOCK) {
            val stored = prefs.getString(KEY_PIN, null)
            if (stored.isNullOrEmpty()) return

            val version = prefs.getInt(KEY_VERSION, 0)
            if (version > CURRENT_VERSION) {
                Log.e(TAG, "Unsupported PIN storage version; refusing to read")
                return
            }

            if (pinCipher.isCiphertext(stored)) {
                if (version != CURRENT_VERSION) {
                    prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).commit()
                }
                return
            }

            if (version == CURRENT_VERSION) {
                Log.e(TAG, "Stored PIN is not encrypted with the current format; refusing legacy fallback")
                return
            }

            val legacyPin = try {
                String(Base64.decode(stored, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Legacy PIN migration failed; existing value retained")
                return
            }
            if (legacyPin.isEmpty()) return

            val ciphertext = try {
                pinCipher.encrypt(legacyPin)
            } catch (e: Exception) {
                Log.e(TAG, "Legacy PIN migration failed; existing value retained")
                return
            }
            if (ciphertext.isEmpty()) {
                Log.e(TAG, "Legacy PIN migration failed; existing value retained")
                return
            }

            if (!prefs.edit()
                    .putString(KEY_PIN, ciphertext)
                    .putInt(KEY_VERSION, CURRENT_VERSION)
                    .commit()
            ) {
                Log.e(TAG, "Legacy PIN migration could not be persisted; existing value retained")
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "security_settings"
        const val KEY_PIN = "pin_enc"
        const val KEY_VERSION = "pin_storage_version"
        const val CURRENT_VERSION = 2
        const val TAG = "SecuritySettings"
        val MIGRATION_LOCK = Any()
    }
}
