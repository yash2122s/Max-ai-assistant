package com.example.features.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

open class IncomingCallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "IncomingCallReceiver"
        private val lastTelephonyState = java.util.concurrent.atomic.AtomicInteger(TelephonyManager.CALL_STATE_IDLE)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != TelephonyManager.ACTION_PHONE_STATE_CHANGED && 
            action != "android.intent.action.PHONE_STATE") return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }

        val previous = lastTelephonyState.getAndSet(state)
        Log.d(TAG, "Phone state changed: $stateStr (from $previous to $state, managerState=${IncomingCallManager.currentCallState})")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.d(TAG, "[CALL_DETECTED] Ringing broadcast received. Incoming number: ${incomingNumber ?: "NULL"}")
                // Delegate to IncomingCallManager state architecture to handle deduplication and transitions safely
                IncomingCallManager.handleIncomingCall(context, incomingNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                IncomingCallManager.handleCallOffhook(context)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                IncomingCallManager.handleCallEnded(context)
            }
        }
    }
}
