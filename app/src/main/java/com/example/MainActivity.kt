package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.MaxApp
import com.example.ui.home.HomeViewModel
import com.example.ui.theme.MaxAppTheme
import com.example.ui.theme.MaxHaptics

/**
 * Primary Native Android Activity Entry Point for MAX.
 * Hosts 100% Jetpack Compose application shell (MaxApp).
 * Handles System Assistant, Power-key binding, and Voice trigger intents.
 */
class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleTriggerIntent(intent)
        handleNavigationIntent(intent)

        setContent {
            MaxAppTheme {
                MaxApp(homeViewModel = homeViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTriggerIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent == null) return
        val destination = intent.getStringExtra("destination")
        if (!destination.isNullOrBlank()) {
            homeViewModel.navigateTo(destination)
        }
    }

    private fun handleTriggerIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val isAssist = action == Intent.ACTION_ASSIST ||
                action == Intent.ACTION_VOICE_COMMAND ||
                action == "android.intent.action.VOICE_ASSIST" ||
                action == "com.example.ACTION_ASSIST_TRIGGER" ||
                intent.getBooleanExtra("extra_start_listening", false) ||
                intent.getStringExtra("trigger_source") == "power_button"

        if (isAssist) {
            MaxHaptics.mediumImpact(this)
            val triggerSource = intent.getStringExtra("trigger_source") ?: "power_button"
            val startListening = intent.getBooleanExtra("extra_start_listening", true)
            val openReveal = intent.getBooleanExtra("extra_open_reveal", true)
            homeViewModel.handleAssistantTrigger(
                triggerSource = triggerSource,
                startListening = startListening,
                openReveal = openReveal
            )
        }
    }
}
