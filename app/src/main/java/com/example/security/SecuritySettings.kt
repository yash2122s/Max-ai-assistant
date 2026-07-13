package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

class SecuritySettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("security_settings", Context.MODE_PRIVATE)

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
        get() = prefs.getString("pin_enc", "") ?: ""
        set(value) {
            val enc = Base64.encodeToString(value.toByteArray(), Base64.DEFAULT)
            prefs.edit().putString("pin_enc", enc).apply()
        }

    var voiceUnlockCodeWord: String
        get() = prefs.getString("voice_unlock_code_word", "open sesame") ?: "open sesame"
        set(value) = prefs.edit().putString("voice_unlock_code_word", value).apply()

    fun getDecryptedPin(): String {
        val enc = encryptedPin
        if (enc.isEmpty()) return ""
        return try {
            String(Base64.decode(enc, Base64.DEFAULT))
        } catch (e: Exception) {
            ""
        }
    }
}
