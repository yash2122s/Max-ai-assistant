package com.example.voice.trigger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.example.MainActivity
import com.example.voice.session.JarvisVoiceInteractionService

class JarvisVoiceTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("JarvisVoiceTriggerActivity", "Triggered via Assist Intent")

        val service = JarvisVoiceInteractionService.instance
        if (service != null) {
            try {
                service.showSession(Bundle(), 0)
            } catch (e: Exception) {
                Log.e("JarvisVoiceTriggerActivity", "Failed to show assistant session", e)
                Toast.makeText(this, "Failed to trigger Assistant HUD", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(
                this, 
                "Please configure MAX AI Agent as your default digital assistant app", 
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }
        }
        finish()
    }
}
