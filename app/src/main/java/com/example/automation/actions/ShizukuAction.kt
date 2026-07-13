package com.example.automation.actions

import android.content.Context
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

class ShizukuAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val action = payload.optString("action")
        val config = payload.optString("config")
        
        val result = when (action.uppercase()) {
            "DISABLE_APP" -> com.example.automation.ShizukuExecutor.runCommand("pm disable-user --user 0 $config")
            "UNINSTALL_APP" -> com.example.automation.ShizukuExecutor.runCommand("pm uninstall --user 0 $config")
            "FORCE_STOP" -> com.example.automation.ShizukuExecutor.runCommand("am force-stop $config")
            "SHIZUKU_SHELL" -> com.example.automation.ShizukuExecutor.runCommand(config)
            else -> "Unknown Shizuku action: $action"
        }
        
        Log.d("ShizukuAction", "Executed $action on $config. Result: $result")
        
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Shizuku $action: $result", Toast.LENGTH_LONG).show()
        }
    }
}
