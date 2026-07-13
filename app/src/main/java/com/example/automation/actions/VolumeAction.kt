package com.example.automation.actions

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

class VolumeAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        
        val direction = payload.optString("direction", "").lowercase()
        val valueStr = payload.optString("value", "")
        val percentStr = payload.optString("percent", "")
        
        // Target percentage
        var targetPercent = -1
        
        if (valueStr.isNotEmpty()) {
            targetPercent = valueStr.replace("%", "").toIntOrNull() ?: -1
        } else if (percentStr.isNotEmpty()) {
            targetPercent = percentStr.replace("%", "").toIntOrNull() ?: -1
        } else if (payload.has("percent")) {
            targetPercent = payload.optInt("percent", -1)
        } else if (payload.has("value")) {
            targetPercent = payload.optInt("value", -1)
        }
        
        if (targetPercent in 0..100) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = ((targetPercent / 100f) * maxVolume).toInt()
            log("Setting music stream volume to $targetVolume / $maxVolume ($targetPercent%)")
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
        } else {
            when (direction) {
                "up", "raise", "increase" -> {
                    log("Raising volume")
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                "down", "lower", "decrease" -> {
                    log("Lowering volume")
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
                else -> {
                    logError("Invalid volume payload: $payload")
                }
            }
        }
    }
}
