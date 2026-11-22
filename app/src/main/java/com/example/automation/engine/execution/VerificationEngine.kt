package com.example.automation.engine.execution

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.util.Log
import com.example.automation.engine.state.ExpectedOutcome
import com.example.automation.verification.RuntimeStepVerifier

class VerificationEngine(private val context: Context) {
    private val TAG = "VerificationEngine"

    suspend fun verify(outcome: ExpectedOutcome?): Boolean {
        if (outcome == null) {
            Log.w(TAG, "Rejected verification without an explicit expected outcome")
            return false
        }

        return when (outcome) {
            is ExpectedOutcome.PackageForeground -> verifyForeground(outcome)
            is ExpectedOutcome.ElementPresent -> verifyElement(outcome)
            is ExpectedOutcome.TextContentEquals -> verifyText(outcome)
            is ExpectedOutcome.SystemSettingState -> verifySystemState(outcome)
            is ExpectedOutcome.MessageSent -> verifyMessageStatus(outcome)
            is ExpectedOutcome.PlaybackActive -> verifyPlayback(outcome)
        }
    }

    private suspend fun verifyForeground(outcome: ExpectedOutcome.PackageForeground): Boolean {
        Log.d(TAG, "Asserting package foreground: '${outcome.expectedPackage}' (timeout: ${outcome.timeoutMs}ms)")
        return RuntimeStepVerifier.verifyAppForeground(
            context = context,
            expectedPackage = outcome.expectedPackage,
            timeoutMs = outcome.timeoutMs
        )
    }

    private suspend fun verifyElement(outcome: ExpectedOutcome.ElementPresent): Boolean {
        Log.d(TAG, "Asserting element present: '${outcome.targetQuery}' (timeout: ${outcome.timeoutMs}ms)")
        return RuntimeStepVerifier.verifyTargetVisible(
            context = context,
            targetQuery = outcome.targetQuery,
            timeoutMs = outcome.timeoutMs,
            expectedPackage = outcome.expectedPackage
        )
    }

    private suspend fun verifyText(outcome: ExpectedOutcome.TextContentEquals): Boolean {
        Log.d(TAG, "Asserting text content: '${outcome.expectedText}' (timeout: ${outcome.timeoutMs}ms)")
        return RuntimeStepVerifier.verifyTextEntered(
            context = context,
            expectedText = outcome.expectedText,
            timeoutMs = outcome.timeoutMs,
            expectedPackage = outcome.expectedPackage
        )
    }

    private suspend fun verifySystemState(outcome: ExpectedOutcome.SystemSettingState): Boolean {
        Log.d(TAG, "Asserting system setting: '${outcome.settingKey}' == ${outcome.expectedValue}")
        return when (outcome.settingKey.lowercase()) {
            "wifi" -> {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiManager?.isWifiEnabled == (outcome.expectedValue as? Boolean ?: true)
            }
            "bluetooth" -> {
                @Suppress("DEPRECATION")
                val adapter = BluetoothAdapter.getDefaultAdapter()
                adapter?.isEnabled == (outcome.expectedValue as? Boolean ?: true)
            }
            "dnd" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val isDnd = (nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) != NotificationManager.INTERRUPTION_FILTER_ALL
                isDnd == (outcome.expectedValue as? Boolean ?: true)
            }
            "ringer", "silent" -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val isSilent = am?.ringerMode == AudioManager.RINGER_MODE_SILENT || am?.ringerMode == AudioManager.RINGER_MODE_VIBRATE
                isSilent == (outcome.expectedValue as? Boolean ?: true)
            }
            else -> {
                Log.w(TAG, "No local verifier exists for system setting '${outcome.settingKey}'")
                false
            }
        }
    }

    private suspend fun verifyMessageStatus(outcome: ExpectedOutcome.MessageSent): Boolean {
        Log.d(TAG, "Asserting message sent to: '${outcome.contactName}'")
        return RuntimeStepVerifier.verifyTargetVisible(
            context = context,
            targetQuery = outcome.contactName,
            timeoutMs = outcome.timeoutMs
        )
    }

    private suspend fun verifyPlayback(outcome: ExpectedOutcome.PlaybackActive): Boolean {
        Log.d(TAG, "Asserting active audio/music playback")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager?.isMusicActive == true
    }
}
