package com.example.voice.trigger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.MainActivity
import com.example.ui.theme.MaxHaptics

/**
 * Android System Assistant / Voice Assist Entry Point.
 * Triggered by hardware Power button long-press/double-press or digital assistant gesture.
 * Immediately brings up MainActivity into active listening state with edge reveal animation.
 */
class JarvisVoiceTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("JarvisVoiceTriggerActivity", "Triggered via Assist / Power-key binding Intent")

        MaxHaptics.mediumImpact(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_start_listening", true)
            putExtra("trigger_source", "power_button")
            putExtra("extra_open_reveal", true)
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
        finish()
    }
}
