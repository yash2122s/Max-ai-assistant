package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.automation.actions.*
import org.json.JSONObject

object AutomationEngine {
    private const val TAG = "AutomationEngine"

    fun dispatch(context: Context, json: JSONObject): Boolean {
        Log.d(TAG, "Dispatching automation: $json")
        return ActionDispatcher.dispatch(context, json)
    }
}