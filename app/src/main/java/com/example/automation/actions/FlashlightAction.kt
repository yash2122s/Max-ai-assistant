package com.example.automation.actions

import android.content.Context
import android.hardware.camera2.CameraManager
import org.json.JSONObject

class FlashlightAction(private val state: Boolean) : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, state)
        } catch (e: Exception) {
            logError("Failed to toggle flashlight", e)
        }
    }
}
