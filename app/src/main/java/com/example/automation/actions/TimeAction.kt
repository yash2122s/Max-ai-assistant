package com.example.automation.actions

import android.content.Context
import org.json.JSONObject

class TimeAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        // Handled by speech_reply now, but kept for legacy
    }
}
