package com.example.automation.actions

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

class VolumeAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: throw IllegalStateException("AudioManager service unavailable")
        
        val direction = payload.optString("direction", "").lowercase()
        val action = payload.optString("action", "").uppercase()
        val valueStr = payload.optString("value", "")
        val percentStr = payload.optString("percent", "")
        val volumeStr = payload.optString("volume", "")
        val levelStr = payload.optString("level", "")
        
        // Target percentage
        var targetPercent = -1
        
        fun parseNumber(s: String): Int? {
            if (s.isBlank()) return null
            val digits = s.replace("%", "").trim()
            return digits.toIntOrNull()
        }

        targetPercent = parseNumber(valueStr)
            ?: parseNumber(percentStr)
            ?: parseNumber(volumeStr)
            ?: parseNumber(levelStr)
            ?: if (payload.has("percent")) payload.optInt("percent", -1)
            else if (payload.has("volume")) payload.optInt("volume", -1)
            else if (payload.has("level")) payload.optInt("level", -1)
            else if (payload.has("value")) payload.optInt("value", -1)
            else -1
        
        if (targetPercent in 0..100) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = ((targetPercent / 100f) * maxVolume).toInt().coerceIn(0, maxVolume)
            log("Setting music stream volume to $targetVolume / $maxVolume ($targetPercent%)")
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
        } else if (action == "VOLUME_UP" || direction in listOf("up", "raise", "increase")) {
            log("Raising volume")
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        } else if (action == "VOLUME_DOWN" || direction in listOf("down", "lower", "decrease")) {
            log("Lowering volume")
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        } else {
            throw IllegalArgumentException("Invalid volume payload. Provide percentage (0-100) or direction (up/down): $payload")
        }
    }
}
