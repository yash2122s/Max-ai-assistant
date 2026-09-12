package com.example.automation.actions

import android.content.Context
import android.util.Log
import com.example.automation.CallDecision
import com.example.automation.IncomingCallManager
import org.json.JSONObject

class RejectCallAction : BaseAction<JSONObject>() {
    companion object {
        private const val TAG = "RejectCallAction"
    }

    override fun execute(context: Context, payload: JSONObject) {
        Log.d(TAG, "[REJECT_REQUESTED] Executing reject call request via IncomingCallManager")
        IncomingCallManager.handleCallDecision(context, CallDecision.REJECT)
    }
}
