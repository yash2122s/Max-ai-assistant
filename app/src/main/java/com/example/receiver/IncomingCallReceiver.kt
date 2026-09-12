package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.example.automation.IncomingCallManager

class IncomingCallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "IncomingCallReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
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

        Log.d(TAG, "Phone state changed: $stateStr (from $lastState to $state)")

        if (lastState == TelephonyManager.CALL_STATE_IDLE && state == TelephonyManager.CALL_STATE_RINGING) {
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            Log.d(TAG, "[CALL_DETECTED] Ringing transition detected. Incoming number: ${incomingNumber ?: "NULL"}")
            IncomingCallManager.handleIncomingCall(context, incomingNumber)
        } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            IncomingCallManager.handleCallOffhook(context)
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            IncomingCallManager.handleCallEnded(context)
        }

        lastState = state
    }
}
