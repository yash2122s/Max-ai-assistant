package com.example.automation.engine.execution

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.state.TaskContext
import com.example.automation.engine.state.TaskStep
import com.example.automation.models.UiStep
import com.example.automation.resolver.UiTargetResolver
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.keyboard.KeyboardController
import com.example.knowledge.apps.InstalledAppsRepository
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class CapabilityRouter(
    private val context: Context,
    private val appRepository: InstalledAppsRepository = InstalledAppsRepository
) {
    private val TAG = "CapabilityRouter"

    suspend fun executeStep(step: TaskStep, taskContext: TaskContext): ExecutionResult = withContext(Dispatchers.IO) {
        val executionId = UUID.randomUUID()
        
        when (val uiStep = step.action) {
            // =========================================================================
            // TIER 1: DIRECT SYSTEM API (Hardware Controls) < 10ms
            // =========================================================================
            is UiStep.ToggleSystemSetting -> {
                Log.d(TAG, "[TIER 1 SYSTEM API] Setting '${uiStep.settingKey}' = ${uiStep.enabled}")
                when (uiStep.settingKey.lowercase()) {
                    "flashlight", "torch" -> {
                        try {
                            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                            val cameraIds = cameraManager.cameraIdList
                            if (cameraIds.isNotEmpty()) {
                                cameraManager.setTorchMode(cameraIds[0], uiStep.enabled)
                                AutomationEventBus.publish(AutomationEvent.ActionStarted(executionId, "FLASHLIGHT", uiStep.enabled.toString()))
                                ExecutionResult(success = true)
                            } else {
                                ExecutionResult(success = false)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to toggle flashlight: ${e.message}")
                            ExecutionResult(success = false)
                        }
                    }
                    "wifi" -> {
                        try {
                            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                            @Suppress("DEPRECATION")
                            val directOk = wifiManager?.setWifiEnabled(uiStep.enabled) ?: false
                            if (!directOk) {
                                val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                context.startActivity(intent)
                            }
                            AutomationEventBus.publish(AutomationEvent.ActionStarted(executionId, "WIFI", uiStep.enabled.toString()))
                            ExecutionResult(success = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to toggle wifi: ${e.message}")
                            ExecutionResult(success = false)
                        }
                    }
                    "bluetooth" -> {
                        try {
                            @Suppress("DEPRECATION")
                            val adapter = BluetoothAdapter.getDefaultAdapter()
                            if (uiStep.enabled) adapter?.enable() else adapter?.disable()
                            AutomationEventBus.publish(AutomationEvent.ActionStarted(executionId, "BLUETOOTH", uiStep.enabled.toString()))
                            ExecutionResult(success = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to toggle bluetooth: ${e.message}")
                            ExecutionResult(success = false)
                        }
                    }
                    else -> ExecutionResult(success = true)
                }
            }

            // =========================================================================
            // TIER 2: ANDROID INTENT (App Launch & Deep Links) < 100ms
            // =========================================================================
            is UiStep.OpenApp -> {
                Log.d(TAG, "[TIER 2 INTENT] Opening app: ${uiStep.appName} (pkg: ${uiStep.packageName})")
                val pkgToLaunch = uiStep.packageName
                    ?: if (uiStep.appName.contains(".") && context.packageManager.getLaunchIntentForPackage(uiStep.appName) != null) {
                        uiStep.appName
                    } else {
                        appRepository.findApp(context, uiStep.appName)
                    }
                
                if (pkgToLaunch == null) {
                    Log.w(TAG, "Cannot launch '${uiStep.appName}': package not resolved")
                    return@withContext ExecutionResult(success = false)
                }

                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkgToLaunch)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(launchIntent)
                    AutomationEventBus.publish(AutomationEvent.ActionStarted(executionId, "OPEN_APP", pkgToLaunch))
                    return@withContext ExecutionResult(success = true, activePackage = pkgToLaunch)
                }
                ExecutionResult(success = false)
            }

            // =========================================================================
            // TIER 3: ACCESSIBILITY UI (Grounded Node Actions) 100 - 300ms
            // =========================================================================
            is UiStep.Tap -> {
                val query = uiStep.target ?: uiStep.resourceId ?: uiStep.contentDescription ?: ""
                Log.d(TAG, "[TIER 3 ACCESSIBILITY] Tapping target: '$query'")
                
                val resolution = UiTargetResolver.resolve(
                    query = query,
                    explicitResourceId = uiStep.resourceId,
                    explicitContentDescription = uiStep.contentDescription
                )

                if (resolution.success && resolution.target != null) {
                    val bounds = resolution.target.bounds
                    if (bounds != null) {
                        AutomationEventBus.publish(
                            AutomationEvent.TargetGrounded(
                                executionId = executionId,
                                bounds = bounds,
                                label = query,
                                actionType = "TAP"
                            )
                        )
                    }
                    
                    val node = resolution.matchedNode
                    var clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                    
                    if (!clicked && bounds != null) {
                        val centerX = bounds.exactCenterX().coerceAtLeast(0f)
                        val centerY = bounds.exactCenterY().coerceAtLeast(0f)
                        val path = android.graphics.Path().apply { moveTo(centerX, centerY) }
                        val gesture = android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                            .build()
                        clicked = JarvisAccessibilityService.dispatchGestureAction(gesture)
                        Log.d(TAG, "[TIER 3 ACCESSIBILITY] Fallback gesture tap at ($centerX, $centerY): $clicked")
                    }
                    
                    AutomationEventBus.publish(AutomationEvent.ActionStarted(executionId, "TAP", query))
                    ExecutionResult(success = clicked, activePackage = node?.packageName?.toString())
                } else {
                    Log.w(TAG, "Target '$query' could not be resolved in active hierarchy")
                    ExecutionResult(success = false)
                }
            }

            is UiStep.TypeText -> {
                Log.d(TAG, "[TIER 3 ACCESSIBILITY] Typing text into target: '${uiStep.target ?: "focused"}'")
                val targetQuery = uiStep.target ?: uiStep.resourceId
                
                val resolution = if (!targetQuery.isNullOrBlank()) {
                    UiTargetResolver.resolve(query = targetQuery, requireEditable = true)
                } else null

                val bounds = resolution?.target?.bounds
                if (bounds != null) {
                    AutomationEventBus.publish(
                        AutomationEvent.TargetGrounded(
                            executionId = executionId,
                            bounds = bounds,
                            label = targetQuery ?: "focused",
                            actionType = "TYPE"
                        )
                    )
                }

                AutomationEventBus.publish(AutomationEvent.TypingStarted(executionId, uiStep.text, targetQuery ?: "focused"))
                val result = KeyboardController.typeText(context, uiStep.text, targetQuery, uiStep.clearExisting)
                ExecutionResult(success = result.success)
            }

            is UiStep.ClearText -> {
                val targetQuery = uiStep.target ?: uiStep.resourceId
                val result = KeyboardController.clearText(context, targetQuery)
                ExecutionResult(success = result.success)
            }

            is UiStep.PressKey -> {
                val result = KeyboardController.pressKey(context, uiStep.key, uiStep.target)
                ExecutionResult(success = result.success)
            }

            is UiStep.PressEnter -> {
                val result = KeyboardController.pressEnter(context)
                ExecutionResult(success = result.success)
            }

            is UiStep.PressBack -> {
                val service = JarvisAccessibilityService.instance
                val ok = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
                ExecutionResult(success = ok)
            }

            is UiStep.Scroll -> {
                val service = JarvisAccessibilityService.instance
                val (ok, _) = service?.scrollScreen(uiStep.direction) ?: Pair(false, "Accessibility unavailable")
                ExecutionResult(success = ok)
            }

            is UiStep.Swipe -> {
                val service = JarvisAccessibilityService.instance
                val (ok, _) = service?.scrollScreen("down") ?: Pair(false, "Accessibility unavailable")
                ExecutionResult(success = ok)
            }

            is UiStep.LongPress -> {
                val query = uiStep.target ?: uiStep.resourceId ?: ""
                val resolution = UiTargetResolver.resolve(query = query)
                val ok = resolution.matchedNode?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false
                ExecutionResult(success = ok)
            }

            is UiStep.WaitForUi -> {
                val target = uiStep.targetText ?: uiStep.resourceId
                val ok = if (!target.isNullOrBlank()) {
                    com.example.automation.verification.RuntimeStepVerifier.verifyTargetVisible(
                        context = context,
                        targetQuery = target,
                        timeoutMs = uiStep.timeoutMs
                    )
                } else if (!uiStep.packageName.isNullOrBlank()) {
                    com.example.automation.verification.RuntimeStepVerifier.verifyAppForeground(
                        context = context,
                        expectedPackage = uiStep.packageName,
                        timeoutMs = uiStep.timeoutMs
                    )
                } else {
                    true
                }
                ExecutionResult(success = ok)
            }

            is UiStep.Finish -> {
                ExecutionResult(success = uiStep.success)
            }
        }
    }
}

data class ExecutionResult(
    val success: Boolean,
    val activePackage: String? = null,
    val activeScreen: String? = null
)
