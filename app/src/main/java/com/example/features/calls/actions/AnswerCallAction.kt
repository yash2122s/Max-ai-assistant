package com.example.features.calls.actions

import android.content.Context
import android.util.Log
import com.example.automation.actions.BaseAction
import com.example.features.calls.CallDecision
import com.example.features.calls.IncomingCallManager
import org.json.JSONObject

class AnswerCallAction : BaseAction<JSONObject>() {
    companion object {
        private const val TAG = "AnswerCallAction"
    }

    override fun execute(context: Context, payload: JSONObject) {
        Log.d(TAG, "[ANSWER_REQUESTED] Executing answer call request via IncomingCallManager")
        IncomingCallManager.handleCallDecision(context, CallDecision.ACCEPT)
    }
}
