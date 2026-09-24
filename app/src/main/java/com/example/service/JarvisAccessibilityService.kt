package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.example.automation.resolver.WhatsAppContactMatch
import com.example.automation.resolver.WhatsAppContactResolver
import com.example.core.registry.ServiceRegistry
import com.example.utils.NotificationHelper
import java.io.ByteArrayOutputStream
import java.util.Locale

enum class SendVerificationResult {
    SUCCESS,
    SEND_ACTION_CONFIRMED,
    VERIFICATION_PENDING,
    WRONG_CHAT,
    INPUT_NOT_FOUND,
    SEND_BUTTON_NOT_FOUND,
    FAILED,
    TIMEOUT
}

enum class ClickEscalationResult {
    ACCESSIBILITY_CONFIRMED,
    GESTURE_CONFIRMED,
    UNCONFIRMED,
    FAILED
}

data class TargetStateSnapshot(
    val isChecked: Boolean? = null,
    val text: String? = null,
    val bounds: Rect = Rect()
)

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var pendingTask: AutomationTask? = null
        var instance: JarvisAccessibilityService? = null
        var lastSendVerificationResult: SendVerificationResult? = null
        var testRootProvider: (() -> AccessibilityNodeInfo?)? = null
        var testWindowsProvider: (() -> List<android.view.accessibility.AccessibilityWindowInfo>?)? = null
        var testGestureDispatcher: ((android.accessibilityservice.GestureDescription) -> Boolean)? = null
        var testShellExecutor: ((String) -> Unit)? = null

        fun isServiceRunning(): Boolean = instance != null || testRootProvider != null

        fun getActiveRoot(): AccessibilityNodeInfo? {
            val root = instance?.rootInActiveWindow ?: testRootProvider?.invoke()
            if (root != null) return root

            try {
                val windows = instance?.windows ?: testWindowsProvider?.invoke()
                if (windows != null) {
                    val activeWin = windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it.isFocused } ?: windows.lastOrNull()
                    val winRoot = activeWin?.root
                    if (winRoot != null) return winRoot
                }
            } catch (_: Exception) {}

            return null
        }

        fun getActiveWindows(): List<android.view.accessibility.AccessibilityWindowInfo>? {
            return instance?.windows ?: testWindowsProvider?.invoke()
        }

        fun dispatchGestureAction(gesture: android.accessibilityservice.GestureDescription): Boolean {
            return instance?.dispatchGesture(gesture, null, null) ?: testGestureDispatcher?.invoke(gesture) ?: false
        }

        fun runShell(cmd: String): Boolean {
            if (!com.example.automation.computeruse.ShellCommandPolicy.isCommandAllowed(cmd)) {
                android.util.Log.w("JarvisAccessibility", "[SHELL_POLICY_BLOCKED] Shell command rejected by security policy: '$cmd'")
                return false
            }
            val executor = testShellExecutor
            if (executor != null) {
                return try {
                    executor.invoke(cmd)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (com.example.automation.ShizukuManager.isShizukuAvailable() && com.example.automation.ShizukuManager.isPermissionGranted()) {
                return try {
                    com.example.automation.ShizukuShellPlugin.runCommand(cmd)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            return false
        }

        fun triggerAssistantLaunch(context: android.content.Context, source: String = "accessibility") {
            try {
                val intent = Intent(context, com.example.MainActivity::class.java).apply {
                    action = Intent.ACTION_ASSIST
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("extra_start_listening", true)
                    putExtra("trigger_source", source)
                    putExtra("extra_open_reveal", true)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("JarvisAccessibility", "Failed to trigger assistant launch", e)
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isWaiting = false
    private var directClickTime = 0L

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)
        val keyCode = event.keyCode
        val action = event.action

        if (action == android.view.KeyEvent.ACTION_DOWN) {
            if (keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST ||
                keyCode == android.view.KeyEvent.KEYCODE_ASSIST ||
                keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
                triggerAssistantLaunch(this, "power_button")
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServiceRegistry.register(com.example.core.registry.ServiceType.ACCESSIBILITY, this)
        Log.d("JarvisAccessibility", "Accessibility service connected successfully.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) {
            instance = null
        }
        ServiceRegistry.unregister(com.example.core.registry.ServiceType.ACCESSIBILITY)
        Log.d("JarvisAccessibility", "Accessibility service unbound.")
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UNLOCK_WITH_PIN") {
            val pin = intent.getStringExtra("pin")
            if (!pin.isNullOrEmpty()) {
                Log.d("JarvisAccessibility", "Received request to unlock with PIN")
                unlockWithPin(pin)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!com.example.core.lifecycle.MaxRuntimeManager.isMaxEnabled(this)) {
            pendingTask = null
            return
        }

        // P1.4 — habit discovery: passively observe foreground app transitions.
        // Cheap, thread-safe enqueue only; all heavy work runs on the recorder's
        // background executor. Never blocks the accessibility callback.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            invalidateIndexedElements()
            val eventPkg = event.packageName?.toString()
            if (!eventPkg.isNullOrBlank()) {
                com.example.automation.state.WorldStateManager.setForegroundApp(eventPkg)
            }
            try {
                com.example.automation.habits.HabitRecorder.initialize(this)
                com.example.automation.habits.HabitRecorder.onForegroundWindow(this, eventPkg)
            } catch (e: Exception) {
                android.util.Log.w("HabitRecorder", "Habit observation failed: ${e.message}")
            }
        }

        val task = pendingTask ?: return
        if (isWaiting) {
            return
        }
        val packageName = event?.packageName?.toString() ?: return
        val eventType = event?.eventType

        // Log events for WhatsApp tasks
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") {
            Log.d("WA_DEBUG", "onAccessibilityEvent: pkg=$packageName eventType=$eventType STEP=${task.step} elapsed=${System.currentTimeMillis()-task.startTime}")
        }

        // 1. Failsafe timeout check (20 seconds)
        if (System.currentTimeMillis() - task.startTime > 20000L) {
            Log.w("JarvisAccessibility", "Failsafe: Automation has timed out (20s limit). Cancelling.")
            Toast.makeText(this, "Jarvis Automation Timed Out ⚠️", Toast.LENGTH_SHORT).show()
            pendingTask = null
            directClickTime = 0L
            return
        }

        // 2. Event Optimization: Wake up on window state, window list, or window content changes
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && 
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        Log.d("JarvisAccessibility", "Processing event for package: $packageName, type: $eventType, task: ${task.type}")

        if (task.type == "GENERIC") {
            handleGenericAutomation(task)
            return
        }

        if (task.type == "SEND_MESSAGE_DEEP_LINK" && (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b")) {
            handleWhatsAppDeepLinkAutomation(task, packageName)
            return
        }

        if ((packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") && (task.appName == "WhatsApp" || task.appName == "WhatsApp Business")) {
            handleWhatsAppAutomation(task, packageName)
        } else if (packageName == "org.telegram.messenger" && task.appName == "Telegram") {
            handleTelegramAutomation(task)
        } else if (packageName == "com.instagram.android" && task.appName == "Instagram") {
            handleInstagramAutomation(task)
        }
    }

    private fun handleWhatsAppDeepLinkAutomation(task: AutomationTask, pkgName: String) {
        val root = rootInActiveWindow ?: run {
            Log.d("WA_DEBUG", "Deep Link: rootInActiveWindow is null")
            return
        }
        val contact = task.contact ?: return
        val message = task.message ?: return

        val timeElapsed = System.currentTimeMillis() - task.startTime
        if (timeElapsed > 12000L) {
            Log.e("WA_DEBUG", "Deep Link Automation Timed Out after ${timeElapsed}ms")
            lastSendVerificationResult = SendVerificationResult.TIMEOUT
            NotificationHelper.showSendFallbackNotification(this, contact, message)
            pendingTask = null
            return
        }

        Log.d("WA_DEBUG", "WhatsApp Deep Link Automation: step=${task.step}, contact=$contact, phone=${task.phone}, timeElapsed=${timeElapsed}ms")

        when (task.step) {
            0 -> {
                // STEP 0: Open / Verify Chat & Type Message
                val entryNode = findWhatsAppEntryNode(root, pkgName)
                if (entryNode == null) {
                    Log.d("WA_DEBUG", "Deep Link Step 0: entryNode is null. Waiting for chat UI (elapsed=${timeElapsed}ms)...")
                    return
                }

                // Verify chat header strictly
                val isCorrect = isCorrectChatOpen(root, task, pkgName)
                Log.d("WA_DEBUG", "Deep Link Step 0: isCorrectChatOpen=$isCorrect")
                if (!isCorrect && timeElapsed < 3500L) {
                    Log.d("WA_DEBUG", "Deep Link Step 0: Waiting for correct chat header to load...")
                    return
                }

                // Type message into entry field (always ensure target message is set)
                val currentText = entryNode.text?.toString() ?: ""
                if (currentText != message) {
                    Log.d("WA_DEBUG", "Deep Link Step 0: Setting message via ACTION_SET_TEXT (current='$currentText', target='$message')")
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                    }
                    val textSet = entryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Log.d("WA_DEBUG", "Deep Link Step 0: ACTION_SET_TEXT result = $textSet")
                } else {
                    Log.d("WA_DEBUG", "Deep Link Step 0: Entry field already contains target text: '$currentText'")
                }

                // Advance state to STEP 1: Waiting for fresh hierarchy with Send Button
                task.step = 1
                Log.d("WA_DEBUG", "Deep Link Step 0 -> Step 1: Message prepared. Waiting for fresh tree for Send button.")
                handler.postDelayed({
                    if (pendingTask === task && task.step == 1) {
                        handleWhatsAppDeepLinkAutomation(task, pkgName)
                    }
                }, 200L)
            }

            1 -> {
                // STEP 1: Fresh hierarchy -> Locate and Click Send Button
                val sendNode = findWhatsAppSendNode(root, pkgName)
                if (sendNode != null) {
                    Log.d("WA_DEBUG", "Deep Link Step 1: Found Send button (id=${sendNode.viewIdResourceName}, desc=${sendNode.contentDescription}, clickable=${sendNode.isClickable}). Performing click...")
                    val clicked = performClickOnNode(sendNode)
                    Log.d("WA_DEBUG", "Deep Link Step 1: Send button click result = $clicked")
                    if (clicked) {
                        lastSendVerificationResult = SendVerificationResult.SEND_ACTION_CONFIRMED
                        task.step = 2
                        Log.d("WA_DEBUG", "Deep Link Step 1 -> Step 2: Send action confirmed. Advancing to post-send verification.")
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 2) {
                                handleWhatsAppDeepLinkAutomation(task, pkgName)
                            }
                        }, 500L)
                        return
                    }
                } else {
                    Log.d("WA_DEBUG", "Deep Link Step 1: Send button not yet found in current hierarchy (elapsed=${timeElapsed}ms). Waiting...")
                }
            }

            2 -> {
                // STEP 2: Post-send UI verification against fresh accessibility hierarchy
                val entryNode = findWhatsAppEntryNode(root, pkgName)
                val textAfterSend = entryNode?.text?.toString() ?: ""
                val isInputCleared = textAfterSend.isBlank() || textAfterSend == "Type a message" || textAfterSend == "Message"

                val hasOutgoingBubble = task.message?.let { msg ->
                    root.findAccessibilityNodeInfosByText(msg).any { it.packageName == pkgName }
                } ?: false

                Log.d("WA_DEBUG", "Deep Link Step 2 (Verification): isInputCleared=$isInputCleared, hasOutgoingBubble=$hasOutgoingBubble, currentInput='$textAfterSend'")

                if (isInputCleared || hasOutgoingBubble) {
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    Log.d("WA_DEBUG", "Deep Link Step 2: Post-Send Verification PASSED (SUCCESS). Clearing pendingTask.")
                    pendingTask = null
                } else if (timeElapsed > 8000L) {
                    lastSendVerificationResult = SendVerificationResult.SEND_ACTION_CONFIRMED
                    Log.w("WA_DEBUG", "Deep Link Step 2: Post-send verification timeout. Keeping SEND_ACTION_CONFIRMED. Clearing pendingTask.")
                    pendingTask = null
                } else {
                    Log.d("WA_DEBUG", "Deep Link Step 2: Message still clearing. Waiting for next event...")
                    handler.postDelayed({
                        if (pendingTask === task && task.step == 2) {
                            handleWhatsAppDeepLinkAutomation(task, pkgName)
                        }
                    }, 400L)
                }
            }
        }
    }

    private fun findWhatsAppEntryNode(root: AccessibilityNodeInfo, pkgName: String): AccessibilityNodeInfo? {
        val entryNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/entry")
        if (entryNodes.isNotEmpty()) return entryNodes[0]

        val textEntryNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/text_entry")
        if (textEntryNodes.isNotEmpty()) return textEntryNodes[0]

        val typeMsgNodes = root.findAccessibilityNodeInfosByText("Type a message")
        if (typeMsgNodes.isNotEmpty()) return typeMsgNodes[0]

        val msgNodes = root.findAccessibilityNodeInfosByText("Message")
        if (msgNodes.isNotEmpty()) return msgNodes[0]

        return findNodeByDescRecursive(root, "Message") ?: findNodeByDescRecursive(root, "Type a message")
    }

    private fun findWhatsAppSendNode(root: AccessibilityNodeInfo, pkgName: String): AccessibilityNodeInfo? {
        val sendNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/send")
        if (sendNodes.isNotEmpty()) return sendNodes[0]

        val sendButtonNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/send_button")
        if (sendButtonNodes.isNotEmpty()) return sendButtonNodes[0]

        val buttonSendNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/button_send")
        if (buttonSendNodes.isNotEmpty()) return buttonSendNodes[0]

        val sendByDesc = findNodeByDescRecursive(root, "Send") ?: findNodeByDescRecursive(root, "send")
        if (sendByDesc != null) return sendByDesc

        val sendByText = root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable || it.parent?.isClickable == true }
        if (sendByText != null) return sendByText

        return null
    }

    private fun isCorrectChatOpen(root: AccessibilityNodeInfo, contact: String, pkgName: String): Boolean {
        return WhatsAppContactResolver.isCorrectChatOpen(root, contact, pkgName)
    }

    private fun isCorrectChatOpen(root: AccessibilityNodeInfo, task: AutomationTask, pkgName: String): Boolean {
        val target = task.contactDisplayName ?: task.contact ?: ""
        return WhatsAppContactResolver.isCorrectChatOpen(root, target, pkgName, task.phone)
    }

    fun getScreenSignature(root: AccessibilityNodeInfo? = rootInActiveWindow): String {
        if (root == null) return "null_root"
        val builder = StringBuilder()
        builder.append(root.packageName ?: "unknown").append(":")
        builder.append(root.windowId).append(":")
        var nodeCount = 0
        fun collectSig(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || nodeCount > 50) return
            if (!node.isVisibleToUser) return
            val res = node.viewIdResourceName
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val isClickable = node.isClickable
            val isCheckable = node.isCheckable
            val isChecked = node.isChecked
            if (isClickable || isCheckable || !text.isNullOrEmpty() || !desc.isNullOrEmpty() || res != null) {
                nodeCount++
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val clsName = node.className?.toString()?.substringAfterLast('.') ?: "View"
                builder.append("[$clsName|${res ?: ""}|${text?.take(20) ?: ""}|${desc?.take(20) ?: ""}|$isClickable|$isChecked|${bounds.left},${bounds.top}]")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectSig(child, depth + 1)
            }
        }
        try {
            collectSig(root, 0)
        } catch (e: Exception) {
            Log.w("JarvisAccessibility", "Error calculating screen signature: ${e.message}")
        }
        return builder.toString()
    }

    fun performClickWithEscalation(
        node: AccessibilityNodeInfo,
        targetExpectedChange: TargetStateSnapshot? = null
    ): ClickEscalationResult {
        val targetDesc = node.viewIdResourceName ?: node.text?.toString() ?: node.contentDescription?.toString() ?: node.className?.toString() ?: "element"
        val preSignature = getScreenSignature()
        val preRoot = rootInActiveWindow
        val prePackage = preRoot?.packageName?.toString()
        val preWindowId = preRoot?.windowId
        val preFocusedNode = preRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val preTarget = targetExpectedChange ?: TargetStateSnapshot(
            isChecked = if (node.isCheckable) node.isChecked else null,
            text = node.text?.toString(),
            bounds = Rect().also { node.getBoundsInScreen(it) }
        )

        Log.d("UI_CLICK", "[UI_CLICK] target=$targetDesc actionClick=true")

        // 1. First attempt polite accessibility ACTION_CLICK
        var clicked = false
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) {
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) break
            }
            current = current.parent
            depth++
        }

        // Bounded wait for UI state change (150ms non-blocking check)
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {}

        val postSignature = getScreenSignature()
        val postRoot = rootInActiveWindow
        val signatureChanged = (preSignature != postSignature) && postSignature != "null_root"

        // Context-sensitive check for toggleable widgets (checkbox/switch/radio)
        var targetStateChanged = false
        if (preTarget.isChecked != null && postRoot != null) {
            val freshNode = if (node.viewIdResourceName != null) {
                postRoot.findAccessibilityNodeInfosByViewId(node.viewIdResourceName).firstOrNull()
            } else null
            if (freshNode != null && freshNode.isCheckable) {
                targetStateChanged = (freshNode.isChecked != preTarget.isChecked)
            }
        }

        // Check for window/package transition or focus changes
        val packageChanged = postRoot != null && prePackage != null && postRoot.packageName?.toString() != prePackage
        val windowChanged = postRoot != null && preWindowId != null && postRoot.windowId != preWindowId
        val postFocusedNode = postRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val focusChanged = postFocusedNode != null && (preFocusedNode == null || postFocusedNode != preFocusedNode || node.isFocused)

        if (clicked && (signatureChanged || targetStateChanged || packageChanged || windowChanged || focusChanged)) {
            Log.d("UI_VERIFY", "[UI_VERIFY] before=${preSignature.take(50)}... after=${postSignature.take(50)}... changed=true (sig=$signatureChanged, state=$targetStateChanged, pkg=$packageChanged, win=$windowChanged, focus=$focusChanged)")
            invalidateIndexedElements()
            return ClickEscalationResult.ACCESSIBILITY_CONFIRMED
        }

        // 2. Escalate: Physical Coordinate Gesture Fallback on unconfirmed/inert clicks
        Log.w("UI_FALLBACK", "[UI_FALLBACK] reason=ACCESSIBILITY_CLICK_UNCONFIRMED (actionResult=$clicked, signatureChanged=$signatureChanged, targetChanged=$targetStateChanged, focusChanged=$focusChanged)")

        // Revalidate node immediately before gesture tap
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) {
            Log.e("JarvisAccessibility", "Cannot perform gesture fallback: target node bounds are empty")
            return if (clicked) ClickEscalationResult.UNCONFIRMED else ClickEscalationResult.FAILED
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val centerX = rect.exactCenterX().coerceIn(0f, (screenWidth - 1f).coerceAtLeast(0f))
        val centerY = rect.exactCenterY().coerceIn(0f, (screenHeight - 1f).coerceAtLeast(0f))

        Log.d("UI_GESTURE", "[UI_GESTURE] x=$centerX y=$centerY target=$targetDesc bounds=$rect")

        val path = Path().apply { moveTo(centerX, centerY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        val gestureSuccess = dispatchGesture(gesture, null, null)

        try {
            Thread.sleep(250)
        } catch (_: InterruptedException) {}

        val postGestureSignature = getScreenSignature()
        val postGestureRoot = rootInActiveWindow
        val gestureSignatureChanged = (preSignature != postGestureSignature) && postGestureSignature != "null_root"
        val postGesturePackageChanged = postGestureRoot != null && prePackage != null && postGestureRoot.packageName?.toString() != prePackage
        val postGestureWindowChanged = postGestureRoot != null && preWindowId != null && postGestureRoot.windowId != preWindowId
        val postGestureFocus = postGestureRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val postGestureFocusChanged = postGestureFocus != null && (preFocusedNode == null || postGestureFocus != preFocusedNode)

        if (gestureSuccess) {
            invalidateIndexedElements()
        }

        return if (gestureSuccess && (gestureSignatureChanged || targetStateChanged || postGesturePackageChanged || postGestureWindowChanged || postGestureFocusChanged)) {
            Log.d("UI_VERIFY", "[UI_VERIFY] postGesture changed=true result=GESTURE_CONFIRMED")
            ClickEscalationResult.GESTURE_CONFIRMED
        } else if (gestureSuccess) {
            Log.d("UI_VERIFY", "[UI_VERIFY] postGesture dispatched result=GESTURE_CONFIRMED")
            ClickEscalationResult.GESTURE_CONFIRMED
        } else {
            Log.e("UI_VERIFY", "[UI_VERIFY] postGesture failed result=FAILED")
            ClickEscalationResult.FAILED
        }
    }

    private var currentIndexedMap: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private var currentIndexedElements: List<IndexedUiElement> = emptyList()
    private var currentIndexedSignature: String = ""

    @Synchronized
    fun invalidateIndexedElements() {
        currentIndexedElements = emptyList()
        currentIndexedMap = emptyMap()
        currentIndexedSignature = ""
        Log.d("JarvisAccessibility", "Invalidated indexed UI elements snapshot")
    }

    @Synchronized
    fun updateIndexedElements(root: AccessibilityNodeInfo? = rootInActiveWindow): List<IndexedUiElement> {
        val rootNode = root ?: rootInActiveWindow ?: return emptyList()
        val (elements, nodeMap) = AccessibilityTreePruner.pruneAndIndexTree(rootNode)
        currentIndexedElements = elements
        currentIndexedMap = nodeMap
        currentIndexedSignature = getScreenSignature(rootNode)
        Log.d("JarvisAccessibility", "Updated indexed UI elements: count=${elements.size}, signature=${currentIndexedSignature.take(40)}")
        return elements
    }

    @Synchronized
    fun getIndexedElements(): List<IndexedUiElement> {
        return if (currentIndexedElements.isNotEmpty() && getScreenSignature() == currentIndexedSignature) {
            currentIndexedElements
        } else {
            updateIndexedElements()
        }
    }

    @Synchronized
    fun resolveIndexedNode(index: Int): AccessibilityNodeInfo? {
        val currentSig = getScreenSignature()
        if (currentSig != currentIndexedSignature) {
            Log.w("UI_INDEX_INVALID", "[UI_INDEX_INVALID] reason=SCREEN_CHANGED (cached=${currentIndexedSignature.take(30)}..., current=${currentSig.take(30)}...)")
            updateIndexedElements()
        }
        return currentIndexedMap[index]
    }

    fun clickIndexedElement(index: Int): Pair<Boolean, String> {
        val node = resolveIndexedNode(index)
        if (node == null) {
            Log.w("JarvisAccessibility", "clickIndexedElement: Element index [$index] not found on active screen")
            return Pair(false, "Element index [$index] not found on current screen")
        }

        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: "no_id"
        Log.d("UI_INDEX", "[UI_INDEX] index=$index text='$text' id=$resId")

        val result = performClickWithEscalation(node)
        val success = (result == ClickEscalationResult.ACCESSIBILITY_CONFIRMED || result == ClickEscalationResult.GESTURE_CONFIRMED)
        return Pair(success, "Click on indexed element [$index] ('$text') outcome: $result")
    }

    private fun tapNodeWithGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) return false

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        // Calculate exact center of the node, bounded within active screen
        val x = rect.exactCenterX().coerceIn(0f, (screenWidth - 1f).coerceAtLeast(0f))
        val y = rect.exactCenterY().coerceIn(0f, (screenHeight - 1f).coerceAtLeast(0f))

        Log.d("GESTURE_DEBUG", "Universal Tap x=$x y=$y bounds=$rect on display (${screenWidth.toInt()}x${screenHeight.toInt()})")
        
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0, 100)
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val result = performClickWithEscalation(node)
        return result == ClickEscalationResult.ACCESSIBILITY_CONFIRMED || result == ClickEscalationResult.GESTURE_CONFIRMED
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo, forceGesture: Boolean = false): Boolean {
        if (forceGesture) {
            val gestureSuccess = tapNodeWithGesture(node)
            Log.d("CLICK_DEBUG", "Forced gesture tap directly on node: $gestureSuccess")
            if (gestureSuccess) return true
        }
        val result = performClickWithEscalation(node)
        return result == ClickEscalationResult.ACCESSIBILITY_CONFIRMED || result == ClickEscalationResult.GESTURE_CONFIRMED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        ServiceRegistry.unregister(com.example.core.registry.ServiceType.ACCESSIBILITY)
        pendingTask = null
        handler.removeCallbacksAndMessages(null)
        Log.d("JarvisAccessibility", "Accessibility service destroyed and cleaned up.")
    }

    private fun findNodeByDescRecursive(node: AccessibilityNodeInfo, keyword: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 25) return null // Safety depth limit
        if (node.contentDescription?.toString()?.contains(keyword, ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByDescRecursive(child, keyword, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun findVisibleContactNode(root: AccessibilityNodeInfo, contact: String, pkgName: String): AccessibilityNodeInfo? {
        val cleanTarget = contact.trim().lowercase()
        if (cleanTarget.isEmpty()) return null

        // 1. Exact text match across all nodes in the active window
        val textNodes = root.findAccessibilityNodeInfosByText(contact)
        for (node in textNodes) {
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val id = (node.viewIdResourceName ?: "").lowercase()
            // Exclude search inputs, tabs, action bars
            if (!id.contains("search") && !id.contains("tab") && !id.contains("action_bar")) {
                if (text == cleanTarget) {
                    Log.d("WA_DEBUG", "Visible Contact Found (Exact Text Match): '$text', id=$id")
                    return node
                }
            }
        }

        // 2. Exact match in standard WhatsApp contact name view IDs
        val knownContactIds = listOf(
            "$pkgName:id/conversations_row_contact_name",
            "$pkgName:id/contact_name",
            "$pkgName:id/name",
            "$pkgName:id/conversation_contact_name"
        )
        for (resId in knownContactIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resId)
            for (node in nodes) {
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""
                if (text == cleanTarget) {
                    Log.d("WA_DEBUG", "Visible Contact Found (Resource ID Exact Match): '$text' at $resId")
                    return node
                }
            }
        }

        // 3. Exact full-word boundary match in contact rows (avoids matching 'Bhaskar' inside 'Bhaskar Reddy' unless requested)
        for (node in textNodes) {
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val id = (node.viewIdResourceName ?: "").lowercase()
            if (!id.contains("search") && !id.contains("tab") && !id.contains("action_bar")) {
                val words = text.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
                if (words.contains(cleanTarget)) {
                    Log.d("WA_DEBUG", "Visible Contact Found (Full-word Match): '$text', id=$id")
                    return node
                }
            }
        }

        // 4. Content description match
        val descNode = findNodeByDescRecursive(root, contact)
        if (descNode != null) {
            val desc = descNode.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            if (!desc.contains("search") && !desc.contains("tab") && !desc.contains("action_bar")) {
                Log.d("WA_DEBUG", "Visible Contact Found (Content Description Match): '$desc'")
                return descNode
            }
        }

        return null
    }

    private fun findClickableRowAncestor(node: AccessibilityNodeInfo, pkgName: String): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        var bestClickable: AccessibilityNodeInfo? = null
        while (current != null && depth < 8) {
            val id = (current.viewIdResourceName ?: "").lowercase()
            if (id.contains("contact_row") || id.contains("conversations_row") || id.contains("row_content") || id.contains("conversation_container")) {
                return current
            }
            if (current.isClickable && bestClickable == null) {
                bestClickable = current
            }
            current = current.parent
            depth++
        }
        return bestClickable ?: node
    }

    private fun clickVisibleContactRow(textNode: AccessibilityNodeInfo, rowNode: AccessibilityNodeInfo): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        // 1. Try precise gesture tap at the exact center of the contact NAME text node (bypasses left avatar entirely)
        val textRect = Rect()
        textNode.getBoundsInScreen(textRect)
        if (!textRect.isEmpty && textRect.width() > 0 && textRect.height() > 0) {
            val tapX = textRect.exactCenterX().coerceIn(0f, (screenWidth - 1f).coerceAtLeast(0f))
            val tapY = textRect.exactCenterY().coerceIn(0f, (screenHeight - 1f).coerceAtLeast(0f))
            val path = Path()
            path.moveTo(tapX, tapY)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            val dispatched = dispatchGesture(gesture, null, null)
            Log.d("WA_DEBUG", "Dispatched gesture tap at text node center ($textRect, tap=($tapX, $tapY)): $dispatched")
            if (dispatched) return true
        }

        // 2. Try standard ACTION_CLICK on the row container
        if (rowNode.isClickable && rowNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d("WA_DEBUG", "ACTION_CLICK succeeded on row container")
            return true
        }

        // 3. Try climbing parent clickable nodes
        var parent = textNode.parent
        var depth = 0
        while (parent != null && depth < 6) {
            val parentId = (parent.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val isAvatar = parentId.contains("contact_photo") || parentId.contains("avatar") || parentId.contains("profile_picture")
            if (!isAvatar && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d("WA_DEBUG", "ACTION_CLICK succeeded on ancestor depth=$depth")
                return true
            }
            parent = parent.parent
            depth++
        }

        // 4. Fallback: Gesture tap on the center-right of the row container (offset to avoid left avatar)
        val rowRect = Rect()
        rowNode.getBoundsInScreen(rowRect)
        if (!rowRect.isEmpty && rowRect.width() > 0 && rowRect.height() > 0) {
            // Tap at 55% across the row width to ensure it hits the text area and not the avatar on the left
            val tapX = (rowRect.left + rowRect.width() * 0.55f).coerceIn(0f, (screenWidth - 1f).coerceAtLeast(0f))
            val tapY = rowRect.exactCenterY().coerceIn(0f, (screenHeight - 1f).coerceAtLeast(0f))
            val path = Path()
            path.moveTo(tapX, tapY)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            val dispatched = dispatchGesture(gesture, null, null)
            Log.d("WA_DEBUG", "Dispatched gesture tap at row center-right ($rowRect, tap=($tapX, $tapY)): $dispatched")
            return dispatched
        }

        return false
    }

    fun clickVisibleText(targetText: String, targetDesc: String? = null): Pair<Boolean, String> {
        val cleanTarget = targetText.trim().lowercase()
        if (cleanTarget.isEmpty() && targetDesc.isNullOrEmpty()) {
            return Pair(false, "Target text or description cannot be empty")
        }

        val targetCandidates = mutableListOf<String>()
        if (cleanTarget.isNotEmpty()) {
            targetCandidates.add(cleanTarget)
            when (cleanTarget) {
                "wi-fi", "wifi", "wlan" -> {
                    listOf("wlan", "wi-fi", "wifi", "network & internet", "internet", "connections").forEach {
                        if (!targetCandidates.contains(it)) targetCandidates.add(it)
                    }
                }
                "bluetooth" -> {
                    listOf("bluetooth", "connected devices", "device connectivity").forEach {
                        if (!targetCandidates.contains(it)) targetCandidates.add(it)
                    }
                }
            }
        }

        var matchedNode: AccessibilityNodeInfo? = null

        // Search across all window roots
        for (root in getSearchRoots()) {
            // 1. Search by text across candidates across active window
            for (candidate in targetCandidates) {
                val textNodes = root.findAccessibilityNodeInfosByText(candidate)
                // Priority 1: Exact text match (case-insensitive)
                for (node in textNodes) {
                    val nodeText = node.text?.toString()?.trim()?.lowercase() ?: ""
                    if (nodeText == candidate) {
                        matchedNode = node
                        break
                    }
                }
                // Priority 2: Full-word match
                if (matchedNode == null) {
                    for (node in textNodes) {
                        val nodeText = node.text?.toString()?.trim()?.lowercase() ?: ""
                        val words = nodeText.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
                        if (words.contains(candidate)) {
                            matchedNode = node
                            break
                        }
                    }
                }
                // Priority 3: Substring match if candidate >= 3 chars
                if (matchedNode == null && candidate.length >= 3) {
                    for (node in textNodes) {
                        val nodeText = node.text?.toString()?.trim()?.lowercase() ?: ""
                        if (nodeText.contains(candidate)) {
                            matchedNode = node
                            break
                        }
                    }
                }
                if (matchedNode != null) break
            }

            // Priority 4: Search by content description
            if (matchedNode == null && !targetDesc.isNullOrEmpty()) {
                matchedNode = findNodeByDescRecursive(root, targetDesc)
            }
            if (matchedNode == null) {
                for (candidate in targetCandidates) {
                    matchedNode = findNodeByDescRecursive(root, candidate)
                    if (matchedNode != null) break
                }
            }
            if (matchedNode != null) break
        }

        if (matchedNode == null) {
            Log.w("JarvisAccessibility", "clickVisibleText: Target '$targetText' (or candidates) not found on current screen")
            return Pair(false, "TARGET_NOT_VISIBLE")
        }

        // Resolve clickable row / container ancestor
        val clickableTarget = findClickableRowAncestor(matchedNode, matchedNode.packageName?.toString() ?: "")
        val escalationResult = performClickWithEscalation(clickableTarget)
        Log.d("JarvisAccessibility", "clickVisibleText: Click executed for target '$targetText', escalationResult=$escalationResult")
        
        val clicked = (escalationResult == ClickEscalationResult.ACCESSIBILITY_CONFIRMED || escalationResult == ClickEscalationResult.GESTURE_CONFIRMED)
        return if (clicked) {
            Pair(true, "Clicked '$targetText' successfully ($escalationResult)")
        } else {
            Pair(false, "Failed to perform click on '$targetText' ($escalationResult)")
        }
    }

    fun scrollScreen(direction: String): Pair<Boolean, String> {
        val isDown = direction.equals("down", ignoreCase = true)

        // 1. Find a scrollable node in the current active window hierarchy
        fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findScrollableNode(child)
                if (found != null) return found
            }
            return null
        }

        for (root in getSearchRoots()) {
            val scrollableNode = findScrollableNode(root)
            if (scrollableNode != null) {
                val action = if (isDown) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val scrolled = scrollableNode.performAction(action)
                Log.d("JarvisAccessibility", "scrollScreen: Performed ACTION_SCROLL on node, isDown=$isDown, result=$scrolled")
                if (scrolled) {
                    return Pair(true, "Scrolled $direction successfully via scrollable node")
                }

                // Fallback: swipe within the bounds of the scrollable container
                val scrollRect = Rect()
                scrollableNode.getBoundsInScreen(scrollRect)
                if (!scrollRect.isEmpty && scrollRect.width() > 0 && scrollRect.height() > 0) {
                    val startX = scrollRect.exactCenterX()
                    val startY = if (isDown) scrollRect.top + scrollRect.height() * 0.75f else scrollRect.top + scrollRect.height() * 0.25f
                    val endY = if (isDown) scrollRect.top + scrollRect.height() * 0.25f else scrollRect.top + scrollRect.height() * 0.75f
                    val path = Path().apply {
                        moveTo(startX, startY)
                        lineTo(startX, endY)
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
                        .build()
                    val dispatched = dispatchGesture(gesture, null, null)
                    if (dispatched) {
                        return Pair(true, "Scrolled $direction successfully via container swipe")
                    }
                }
            }
        }

        // 2. Gesture fallback: vertical swipe based on screen dimensions
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val startX = screenWidth * 0.5f
        val endX = screenWidth * 0.5f
        val startY = if (isDown) screenHeight * 0.75f else screenHeight * 0.25f
        val endY = if (isDown) screenHeight * 0.25f else screenHeight * 0.75f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()

        val dispatched = dispatchGesture(gesture, null, null)
        Log.d("JarvisAccessibility", "scrollScreen: Dispatched swipe gesture from ($startX, $startY) to ($endX, $endY), result=$dispatched")

        return if (dispatched) {
            Pair(true, "Scrolled $direction successfully via swipe gesture")
        } else {
            Pair(false, "Failed to scroll screen $direction")
        }
    }

    private fun handleWhatsAppAutomation(task: AutomationTask, pkgName: String) {
        val root = rootInActiveWindow ?: run {
            Log.d("WA_DEBUG", "Legacy Automation: rootInActiveWindow is null")
            return
        }
        val contact = task.contact ?: return
        val message = task.message ?: ""

        Log.d("WA_DEBUG", "WhatsApp Automation: step=${task.step}, contact=$contact, message='$message', directClickTime=$directClickTime")

        when (task.step) {
            0 -> {
                val timeElapsed = System.currentTimeMillis() - task.startTime
                Log.d("WA_DEBUG", "Step 0: Observation check. timeElapsed=${timeElapsed}ms, directClickTime=${directClickTime}")
                if (timeElapsed < 300L) {
                    return
                }

                // 1. If correct chat is already open, move straight to Step 3
                val messageBoxNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/entry")
                if (messageBoxNodes.isNotEmpty() && isCorrectChatOpen(root, contact, pkgName)) {
                    Log.d("WA_DEBUG", "Step 0: Correct chat is already open, jumping directly to Step 3")
                    directClickTime = 0L
                    task.step = 3
                    handleWhatsAppAutomation(task, pkgName)
                    return
                }

                // 2. If a direct click was already attempted, wait for the actual conversation UI transition (up to 2500ms)
                if (directClickTime > 0L) {
                    val timeSinceClick = System.currentTimeMillis() - directClickTime
                    if (timeSinceClick < 2500L) {
                        Log.d("WA_DEBUG", "Step 0: Waiting for chat UI transition after direct click (${timeSinceClick}ms / 2500ms)...")
                        return
                    } else {
                        Log.w("WA_DEBUG", "Step 0: Direct click did not open chat within 2500ms! Falling back to Search flow.")
                        directClickTime = 0L
                        // Fall through to Search fallback below
                    }
                } else {
                    // 3. REAL-TIME OBSERVATION: Check if requested contact is already visible on current screen!
                    val visibleContactNode = findVisibleContactNode(root, contact, pkgName)
                    if (visibleContactNode != null) {
                        val rowAncestor = findClickableRowAncestor(visibleContactNode, pkgName)
                        Log.d("WA_DEBUG", "Step 0: Contact '$contact' is visible on current screen! Clicking row...")
                        val clicked = clickVisibleContactRow(visibleContactNode, rowAncestor)
                        Log.d("WA_DEBUG", "Step 0: Direct contact row click result=$clicked")
                        if (clicked) {
                            directClickTime = System.currentTimeMillis()
                            // Remain in Step 0 to await actual UI transition via accessibility events
                            return
                        }
                    }
                }

                // 4. Fallback: If contact is not visible on current screen or direct click timed out, proceed with existing search flow
                var searchNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/menuitem_search")
                if (searchNodes.isEmpty()) {
                    searchNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/menu_item_search")
                }
                if (searchNodes.isEmpty()) {
                    searchNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search")
                }
                if (searchNodes.isEmpty()) {
                    searchNodes = root.findAccessibilityNodeInfosByText("Search")
                }
                
                var targetNode = if (searchNodes.isNotEmpty()) searchNodes[0] else findNodeByDescRecursive(root, "search")
                Log.d("WA_DEBUG", "Step 0: Search fallback. searchNodes.size=${searchNodes.size}, targetNodeFound=${targetNode != null}")

                if (targetNode != null) {
                    val clicked = performClickOnNode(targetNode)
                    Log.d("WA_DEBUG", "Step 0: Search icon click result=$clicked")
                    if (clicked) {
                        directClickTime = 0L
                        task.step = 1
                    }
                } else {
                    var searchInputNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search_src_text")
                    if (searchInputNodes.isEmpty()) {
                        searchInputNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search_input")
                    }
                    Log.d("WA_DEBUG", "Step 0: searchInputNodes.size=${searchInputNodes.size}")
                    if (searchInputNodes.isNotEmpty()) {
                        Log.d("WA_DEBUG", "Step 0: Search bar already open, moving to Step 1")
                        task.step = 1
                    } else {
                        val messageBoxNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/entry")
                        Log.d("WA_DEBUG", "Step 0: messageBoxNodes.size=${messageBoxNodes.size}")
                        if (messageBoxNodes.isNotEmpty()) {
                            val isCorrect = isCorrectChatOpen(root, contact, pkgName)
                            Log.d("WA_DEBUG", "Step 0: isCorrectChatOpen=$isCorrect")
                            if (isCorrect) {
                                Log.d("WA_DEBUG", "Step 0: Correct chat already open, jumping to Step 3")
                                task.step = 3
                            } else {
                                val backNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/back")
                                var backButton = if (backNodes.isNotEmpty()) backNodes[0] else findNodeByDescRecursive(root, "Navigate up")
                                if (backButton == null) backButton = findNodeByDescRecursive(root, "Back")
                                if (backButton == null) {
                                    val navNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/navigation_icon")
                                    if (navNodes.isNotEmpty()) backButton = navNodes[0]
                                }
                                
                                Log.d("WA_DEBUG", "Step 0: Wrong chat open, backButtonFound=${backButton != null}")
                                if (backButton != null) {
                                    performClickOnNode(backButton)
                                } else {
                                    performGlobalAction(GLOBAL_ACTION_BACK)
                                }
                            }
                        } else {
                            if (timeElapsed > 4000L) {
                                val chatTabNodes = root.findAccessibilityNodeInfosByText("Chats")
                                Log.d("WA_DEBUG", "Step 0: Stuck? chatTabNodes.size=${chatTabNodes.size}")
                                if (chatTabNodes.isNotEmpty()) {
                                    performClickOnNode(chatTabNodes[0])
                                } else {
                                    val backButton = findNodeByDescRecursive(root, "Navigate up") ?: 
                                                   findNodeByDescRecursive(root, "Back") ?:
                                                   root.findAccessibilityNodeInfosByViewId("$pkgName:id/back").firstOrNull() ?:
                                                   root.findAccessibilityNodeInfosByViewId("$pkgName:id/navigation_icon").firstOrNull()
                                    
                                    Log.d("WA_DEBUG", "Step 0: navigation backButtonFound=${backButton != null}")
                                    if (backButton != null) {
                                        performClickOnNode(backButton)
                                    } else {
                                        performGlobalAction(GLOBAL_ACTION_BACK)
                                    }
                                }
                                task.startTime = System.currentTimeMillis() - 2000L 
                            }
                        }
                    }
                }
            }
            1 -> {
                var searchInputNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search_src_text")
                if (searchInputNodes.isEmpty()) {
                    searchInputNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search_input")
                }
                Log.d("WA_DEBUG", "Step 1: searchInputNodes.size=${searchInputNodes.size}")
                if (searchInputNodes.isNotEmpty()) {
                    val currentText = searchInputNodes[0].text?.toString()
                    Log.d("WA_DEBUG", "Step 1: currentText='$currentText', target='$contact'")
                    if (currentText == contact) {
                        task.step = 2
                    } else {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contact)
                        }
                        val typed = searchInputNodes[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Log.d("WA_DEBUG", "Step 1: Typed text result=$typed")
                        task.step = 2 
                    }
                }
            }
            2 -> {
                val currentRoot = rootInActiveWindow ?: return

                // If chat entry is ALREADY open and correct chat is confirmed, proceed directly to Step 3
                val hasEntry = currentRoot.findAccessibilityNodeInfosByViewId("$pkgName:id/entry").isNotEmpty() ||
                        currentRoot.findAccessibilityNodeInfosByViewId("$pkgName:id/text_entry").isNotEmpty()
                if (hasEntry && isCorrectChatOpen(currentRoot, task, pkgName)) {
                    Log.d("WA_DEBUG", "Step 2: Correct chat already open for '$contact', proceeding to Step 3")
                    task.step = 3
                    return
                }

                val resolution = WhatsAppContactResolver.resolveSearchRow(
                    root = currentRoot,
                    targetContact = task.contactDisplayName ?: contact,
                    pkgName = pkgName,
                    targetPhone = task.phone
                )

                when (resolution) {
                    is WhatsAppContactMatch.Found -> {
                        Log.d("WA_DEBUG", "Step 2: Resolved contact '${resolution.matchedName}' (type=${resolution.matchType}, conf=${resolution.confidence}). Clicking row...")
                        val clicked = performClickOnNode(resolution.nodeToClick, forceGesture = true)
                        Log.d("WA_DEBUG", "Step 2: Contact row click result = $clicked")
                        if (clicked) {
                            task.step = 3
                        }
                    }
                    is WhatsAppContactMatch.Ambiguous -> {
                        Log.w("WA_DEBUG", "Step 2: Ambiguous contact matches for '$contact': ${resolution.candidateNames}. Halting to prevent sending to wrong person.")
                    }
                    is WhatsAppContactMatch.NotFound -> {
                        val timeElapsed = System.currentTimeMillis() - task.startTime
                        Log.d("WA_DEBUG", "Step 2: Contact not found in search results yet (elapsed=${timeElapsed}ms)...")
                    }
                }
            }
            3 -> {
                val messageBoxNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/entry")
                    .ifEmpty { root.findAccessibilityNodeInfosByViewId("$pkgName:id/text_entry") }
                Log.d("WA_DEBUG", "Step 3: messageBoxNodes.size=${messageBoxNodes.size}")
                if (messageBoxNodes.isNotEmpty()) {
                    val isCorrect = isCorrectChatOpen(root, task, pkgName)
                    Log.d("WA_DEBUG", "Step 3: isCorrectChatOpen=$isCorrect")
                    if (!isCorrect) {
                        Log.e("WA_DEBUG", "Step 3: Safety check failed! Top toolbar does not match '$contact'. Refusing to type or send message.")
                        return
                    }

                    if (message.isBlank()) {
                        Log.d("WA_DEBUG", "Step 3: Message is empty, WhatsApp chat for '$contact' opened successfully!")
                        completeTask(task)
                        return
                    }

                    val currentText = messageBoxNodes[0].text?.toString()
                    Log.d("WA_DEBUG", "Step 3: currentText='$currentText', message='$message'")
                    if (currentText != message) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                        }
                        val typed = messageBoxNodes[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Log.d("WA_DEBUG", "Step 3: Entered message text result=$typed")
                    }

                    var sendButtonNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/send")
                        .ifEmpty { root.findAccessibilityNodeInfosByViewId("$pkgName:id/send_button") }
                    Log.d("WA_DEBUG", "Step 3: sendButtonNodes.size=${sendButtonNodes.size} by ID")
                    if (sendButtonNodes.isEmpty()) {
                        val sendNode = findNodeByDescRecursive(root, "send") ?: findNodeByDescRecursive(root, "Send")
                        Log.d("WA_DEBUG", "Step 3: Fallback content desc sendNode found=${sendNode != null}")
                        if (sendNode != null) {
                            sendButtonNodes = listOf(sendNode)
                        }
                    }

                    if (sendButtonNodes.isNotEmpty() && performClickOnNode(sendButtonNodes[0])) {
                        Log.d("WA_DEBUG", "Step 3: Clicking WhatsApp send button")
                        completeTask(task)
                    } else {
                        val sendTextNodes = root.findAccessibilityNodeInfosByText("Send")
                        Log.d("WA_DEBUG", "Step 3: sendTextNodes.size=${sendTextNodes.size} by text")
                        if (sendTextNodes.isNotEmpty() && performClickOnNode(sendTextNodes[0])) {
                            Log.d("WA_DEBUG", "Step 3: Clicked Send button by text")
                            completeTask(task)
                        } else {
                            Log.e("WA_DEBUG", "Step 3: Send button not found! Screen hierarchy:\n${dumpScreenToJson()}")
                        }
                    }
                } else {
                    Log.d("WA_DEBUG", "Step 3: MessageBox node not found yet, waiting for chat view to load...")
                }
            }
        }
    }

    private fun handleTelegramAutomation(task: AutomationTask) {
        val root = rootInActiveWindow ?: return
        val contact = task.contact ?: return
        val message = task.message ?: return
        val pkgName = "org.telegram.messenger"

        Log.d("JarvisAccessibility", "Telegram Automation State: step=${task.step}, contact=$contact")

        when (task.step) {
            0 -> {
                val searchNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search_button")
                if (searchNodes.isNotEmpty()) {
                    Log.d("JarvisAccessibility", "Step 0: Clicking Telegram search button")
                    searchNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    task.step = 1
                } else {
                    val searchEdit = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search_edit")
                    if (searchEdit.isNotEmpty()) {
                        task.step = 1
                    }
                }
            }
            1 -> {
                val searchEdit = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search_edit")
                if (searchEdit.isNotEmpty()) {
                    val currentText = searchEdit[0].text?.toString()
                    if (currentText == contact) {
                        task.step = 2
                    } else {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contact)
                        }
                        searchEdit[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Log.d("JarvisAccessibility", "Step 1: Entered contact name in Telegram")
                        task.step = 2
                    }
                }
            }
            2 -> {
                val resultNodes = root.findAccessibilityNodeInfosByText(contact)
                if (resultNodes.isNotEmpty()) {
                    for (node in resultNodes) {
                        Log.d(
                            "RESULT_DEBUG",
                            "text=${node.text}, id=${node.viewIdResourceName}, clickable=${node.isClickable}"
                        )
                    }
                    for (node in resultNodes) {
                        val isInput = node.viewIdResourceName == "$pkgName:id/search_edit"
                        if (!isInput) {
                            var target = node
                            var depth = 0
                            while (!target.isClickable && depth < 3) {
                                target = target.parent ?: break
                                depth++
                            }
                            if (target.isClickable) {
                                Log.d("JarvisAccessibility", "Step 2: Clicking Telegram search result for $contact")
                                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                task.step = 3
                                return
                            }
                        }
                    }
                }
                if (root.findAccessibilityNodeInfosByViewId("$pkgName:id/message_box").isNotEmpty()) {
                    task.step = 3
                }
            }
            3 -> {
                val messageBox = root.findAccessibilityNodeInfosByViewId("$pkgName:id/message_box")
                if (messageBox.isNotEmpty()) {
                    val currentText = messageBox[0].text?.toString()
                    if (currentText != message) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                        }
                        messageBox[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Log.d("JarvisAccessibility", "Step 3: Message entered in Telegram")
                    }
                    val sendBtn = root.findAccessibilityNodeInfosByViewId("$pkgName:id/send_button")
                    if (sendBtn.isNotEmpty()) {
                        Log.d("JarvisAccessibility", "Step 3: Clicking Telegram send button")
                        sendBtn[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        completeTask(task)
                    }
                }
            }
        }
    }

    private fun handleInstagramAutomation(task: AutomationTask) {
        val root = rootInActiveWindow ?: return
        val contact = task.contact ?: return
        val message = task.message ?: return
        val pkgName = "com.instagram.android"

        Log.d("JarvisAccessibility", "Instagram Automation State: step=${task.step}, contact=$contact")

        when (task.step) {
            0 -> {
                val searchNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/search_row")
                if (searchNodes.isNotEmpty()) {
                    Log.d("JarvisAccessibility", "Step 0: Clicking Instagram search row")
                    searchNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    task.step = 1
                } else {
                    val textEditNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/row_thread_composer_edittext")
                    if (textEditNodes.isNotEmpty()) {
                        task.step = 3
                    }
                }
            }
            1 -> {
                task.step = 2
            }
            2 -> {
                val textEditNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/row_thread_composer_edittext")
                if (textEditNodes.isNotEmpty()) {
                    task.step = 3
                }
            }
            3 -> {
                val textEditNodes = root.findAccessibilityNodeInfosByViewId("$pkgName:id/row_thread_composer_edittext")
                if (textEditNodes.isNotEmpty()) {
                    val currentText = textEditNodes[0].text?.toString()
                    if (currentText != message) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                        }
                        textEditNodes[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Log.d("JarvisAccessibility", "Step 3: Message entered in Instagram")
                    }
                    val sendButton = root.findAccessibilityNodeInfosByViewId("$pkgName:id/row_thread_composer_button_send")
                    if (sendButton.isNotEmpty()) {
                        Log.d("JarvisAccessibility", "Step 3: Clicking Instagram send button")
                        sendButton[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        completeTask(task)
                    }
                }
            }
        }
    }

    private fun findNodesWithFallback(root: AccessibilityNodeInfo, viewId: String): List<AccessibilityNodeInfo> {
        var nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isEmpty()) {
            val altId = when {
                viewId.contains("search_input") -> viewId.replace("search_input", "search_src_text")
                viewId.contains("search_src_text") -> viewId.replace("search_src_text", "search_input")
                viewId.contains("menuitem_search") -> viewId.replace("menuitem_search", "menu_item_search")
                viewId.contains("menu_item_search") -> viewId.replace("menu_item_search", "menuitem_search")
                else -> null
            }
            if (altId != null) {
                nodes = root.findAccessibilityNodeInfosByViewId(altId)
            }
        }
        if (nodes.isEmpty() && viewId.contains("search", ignoreCase = true)) {
            nodes = root.findAccessibilityNodeInfosByText("Search")
        }
        return nodes
    }

    private fun triggerPostStepDelay(task: AutomationTask, delayMs: Long) {
        isWaiting = true
        handler.postDelayed({
            isWaiting = false
            // Trigger evaluation of the next step
            handleGenericAutomation(task)
        }, delayMs)
    }

    private fun handleGenericAutomation(task: AutomationTask) {
        if (isWaiting) return

        if (task.steps.isEmpty() || task.currentStepIndex >= task.steps.size) {
            Log.d("JarvisAccessibility", "Generic automation: No more steps or empty.")
            pendingTask = null
            return
        }

        val step = task.steps[task.currentStepIndex]
        Log.d(
            "JarvisAccessibility",
            "Generic Step [${task.currentStepIndex}]: action=${step.action}, text=${step.text}, id=${step.id}"
        )

        when (step.action.uppercase()) {
            "OPEN_APP" -> {
                val pkgName = step.pkg
                if (pkgName.isNullOrBlank()) {
                    failGenericStep(task, "OPEN_APP requires pkg")
                    return
                }

                val intent = packageManager.getLaunchIntentForPackage(pkgName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    task.currentStepIndex++
                    triggerPostStepDelay(task, 1500L)
                } else {
                    failGenericStep(task, "Failed to open package: $pkgName")
                }
            }

            "CLICK_TEXT" -> {
                val textToClick = step.text
                if (textToClick.isNullOrBlank()) {
                    failGenericStep(task, "CLICK_TEXT requires text")
                    return
                }

                if (genericClickByText(textToClick)) {
                    task.currentStepIndex++
                    triggerPostStepDelay(task, 800L)
                } else {
                    failGenericStep(task, "Could not click text: $textToClick")
                }
            }

            "CLICK_ID" -> {
                val idToClick = step.id
                if (idToClick.isNullOrBlank()) {
                    failGenericStep(task, "CLICK_ID requires id")
                    return
                }

                var clicked = false
                for (root in getSearchRoots()) {
                    for (node in findNodesWithFallback(root, idToClick)) {
                        if (performClickOnNode(node)) {
                            clicked = true
                            break
                        }
                    }
                    if (clicked) break
                }

                if (clicked) {
                    task.currentStepIndex++
                    triggerPostStepDelay(task, 800L)
                } else {
                    failGenericStep(task, "Could not click id: $idToClick")
                }
            }

            "TYPE_TEXT" -> {
                val textToType = step.text
                if (textToType == null) {
                    failGenericStep(task, "TYPE_TEXT requires text")
                    return
                }

                val typed = if (!step.id.isNullOrBlank()) {
                    var success = false
                    for (root in getSearchRoots()) {
                        for (node in findNodesWithFallback(root, step.id!!)) {
                            if (node.isEditable ||
                                node.className?.toString()?.contains("EditText", true) == true
                            ) {
                                if (setNodeText(node, textToType)) {
                                    success = true
                                    break
                                }
                            }
                        }
                        if (success) break
                    }
                    success
                } else {
                    genericTypeText(textToType)
                }

                if (typed) {
                    task.currentStepIndex++
                    triggerPostStepDelay(task, 800L)
                } else {
                    failGenericStep(task, "Could not type text")
                }
            }

            "PRESS_ENTER", "IME_ENTER", "PRESS_SEARCH", "IME_SEARCH" -> {
                val action = if (step.action.uppercase().contains("SEARCH")) {
                    android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                } else {
                    android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                }

                if (pressImeAction(action)) {
                    task.currentStepIndex++
                    triggerPostStepDelay(task, 500L)
                } else {
                    failGenericStep(task, "Could not perform IME action")
                }
            }

            "SCROLL" -> {
                val direction = step.text ?: "DOWN"
                val result = scrollScreen(direction)
                if (result.first) {
                    task.currentStepIndex++
                    triggerPostStepDelay(task, 700L)
                } else {
                    failGenericStep(task, "Could not scroll: ${result.second}")
                }
            }

            "WAIT" -> {
                val delay = if (step.delayMs > 0) step.delayMs else 1000L
                task.currentStepIndex++
                triggerPostStepDelay(task, delay)
            }

            else -> failGenericStep(task, "Unknown step action: ${step.action}")
        }

        if (!isWaiting && task.currentStepIndex >= task.steps.size) {
            Log.d("JarvisAccessibility", "Generic automation: Completed all steps.")
            pendingTask = null
        }
    }

    private fun failGenericStep(task: AutomationTask, reason: String) {
        Log.e(
            "JarvisAccessibility",
            "Generic automation failed at step ${task.currentStepIndex}: $reason"
        )
        pendingTask = null
        directClickTime = 0L
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo, depth: Int = 0): List<AccessibilityNodeInfo> {
        if (depth > 25) return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            list.add(node)
            return list
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditText(child, depth + 1)
            if (found.isNotEmpty()) {
                return found
            }
        }
        return emptyList()
    }

    /**
     * Returns accessibility roots from all relevant windows, with the active
     * application root as a fallback.
     */
    private fun getSearchRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val ownPackage = packageName?.toString()

        try {
            for (window in windows.orEmpty()) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == ownPackage) continue

                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    window.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                ) {
                    roots.add(root)
                }
            }
        } catch (t: Throwable) {
            Log.w("JarvisAccessibility", "Window traversal failed: ${t.message}")
        }

        rootInActiveWindow?.let { active ->
            if (roots.none { it == active }) roots.add(active)
        }

        return roots
    }

    /**
     * Rich flat UI representation for Gemini/agent reasoning.
     * Password fields are redacted.
     */
    fun dumpScreenFlat(): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        val screenRect = Rect(
            0,
            0,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        var index = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val password = node.isPassword
            val intersects = bounds.isEmpty || Rect.intersects(screenRect, bounds)

            if (intersects &&
                (!bounds.isEmpty ||
                 !node.text.isNullOrBlank() ||
                 !node.contentDescription.isNullOrBlank())
            ) {
                result += linkedMapOf(
                    "index" to index++,
                    "text" to if (password) "••••" else node.text?.toString().orEmpty(),
                    "contentDescription" to if (password) "••••" else node.contentDescription?.toString().orEmpty(),
                    "className" to node.className?.toString().orEmpty(),
                    "viewId" to node.viewIdResourceName.orEmpty(),
                    "isClickable" to node.isClickable,
                    "isEditable" to node.isEditable,
                    "isScrollable" to node.isScrollable,
                    "isCheckable" to node.isCheckable,
                    "isChecked" to node.isChecked,
                    "isFocused" to node.isFocused,
                    "isPassword" to password,
                    "bounds" to mapOf(
                        "left" to bounds.left,
                        "top" to bounds.top,
                        "right" to bounds.right,
                        "bottom" to bounds.bottom
                    ),
                    "depth" to depth
                )
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                visit(child, depth + 1)
            }
        }

        for (root in getSearchRoots()) {
            visit(root, 0)
        }

        return result
    }

    /**
     * Generic text click:
     * exact non-editable -> contains non-editable ->
     * exact editable -> contains editable.
     */
    fun genericClickByText(targetText: String): Boolean {
        if (targetText.isBlank()) return false

        fun attempt(contains: Boolean, skipEditable: Boolean): Boolean {
            for (root in getSearchRoots()) {
                val nodes = root.findAccessibilityNodeInfosByText(targetText)

                for (node in nodes) {
                    val nodeText = node.text?.toString().orEmpty()
                    val matches = if (contains) {
                        nodeText.contains(targetText, ignoreCase = true)
                    } else {
                        nodeText.equals(targetText, ignoreCase = true)
                    }

                    if (!matches) continue
                    if (skipEditable && node.isEditable) continue

                    if (performClickOnNode(node)) return true
                }

                val descriptionNode = findNodeByDescRecursive(root, targetText)
                if (descriptionNode != null &&
                    (!skipEditable || !descriptionNode.isEditable) &&
                    performClickOnNode(descriptionNode)
                ) {
                    return true
                }
            }
            return false
        }

        return attempt(false, true) ||
            attempt(true, true) ||
            attempt(false, false) ||
            attempt(true, false)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String, replace: Boolean = true): Boolean {
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val currentText = node.text?.toString().orEmpty()
        val finalText = if (replace || currentText.isBlank()) text else "$currentText$text"

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                finalText
            )
        }

        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Generic editable-field discovery across all visible windows.
     */
    fun genericTypeText(text: String, fieldHint: String? = null, replace: Boolean = true): Boolean {
        for (root in getSearchRoots()) {
            val candidates = mutableListOf<AccessibilityNodeInfo>()

            fun collect(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 30) return

                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.toString().orEmpty()
                } else ""

                val nodeText = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()

                val matchesHint = fieldHint.isNullOrBlank() ||
                    hint.contains(fieldHint, true) ||
                    nodeText.contains(fieldHint, true) ||
                    description.contains(fieldHint, true)

                if (node.isEditable && matchesHint) {
                    candidates += node
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                    collect(child, depth + 1)
                }
            }

            collect(root, 0)

            for (node in candidates) {
                if (setNodeText(node, text, replace)) return true
            }
        }

        return false
    }

    fun clickAtCoordinates(x: Float, y: Float): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()

        val safeX = x.coerceIn(1f, (width - 1f).coerceAtLeast(1f))
        val safeY = y.coerceIn(1f, (height - 1f).coerceAtLeast(1f))

        return tapScreen(safeX, safeY)
    }

    /**
     * Generic IME action. First uses ACTION_IME_ENTER on the focused editable
     * node, then falls back to a visible keyboard button.
     */
    fun pressImeAction(action: Int = android.view.inputmethod.EditorInfo.IME_ACTION_DONE): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (root in getSearchRoots()) {
                val editable = findEditableNode(root)
                if (editable != null &&
                    editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                ) {
                    return true
                }
            }
        }

        val keywords = if (
            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        ) {
            listOf("search", "go")
        } else {
            listOf("done", "enter", "send", "go", "next")
        }

        for (keyword in keywords) {
            if (genericClickByText(keyword)) return true
        }

        return false
    }

    private fun findEditableNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val result = findEditableNode(child, depth + 1)
            if (result != null) return result
        }

        return null
    }

    /**
     * Android 11+ screenshot capture. The callback receives JPEG bytes as
     * Base64 so the existing Gemini pipeline can consume them.
     */
    fun takeScreenshotBase64(
        quality: Int = 70,
        callback: (String?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        var hardwareBuffer: HardwareBuffer? = null
                        val stream = ByteArrayOutputStream()

                        try {
                            hardwareBuffer = screenshot.hardwareBuffer
                            bitmap = Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                screenshot.colorSpace
                            )

                            if (bitmap == null) {
                                callback(null)
                                return
                            }

                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                quality.coerceIn(1, 100),
                                stream
                            )

                            callback(
                                Base64.encodeToString(
                                    stream.toByteArray(),
                                    Base64.NO_WRAP
                                )
                            )
                        } catch (t: Throwable) {
                            Log.e("JarvisAccessibility", "Screenshot conversion failed", t)
                            callback(null)
                        } finally {
                            try { stream.close() } catch (_: Throwable) {}
                            try { bitmap?.recycle() } catch (_: Throwable) {}
                            try { hardwareBuffer?.close() } catch (_: Throwable) {}
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(
                            "JarvisAccessibility",
                            "Screenshot failed: errorCode=$errorCode"
                        )
                        callback(null)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e("JarvisAccessibility", "Screenshot request failed", t)
            callback(null)
        }
    }

    fun performGlobalBack(): Boolean =
        performGlobalAction(GLOBAL_ACTION_BACK)

    fun performGlobalHome(): Boolean =
        performGlobalAction(GLOBAL_ACTION_HOME)

    fun performGlobalRecents(): Boolean =
        performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun performGlobalNotifications(): Boolean =
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun performAnswerCallGesture(): Boolean {
        Log.d("JarvisAccessibility", "Attempting to answer call via Accessibility")
        val answerLabels = listOf("Answer", "Accept", "Pick up", "Swipe up to answer", "Swipe right to answer", "Receive", "లిఫ్ట్", "ఆన్సర్")
        for (label in answerLabels) {
            if (genericClickByText(label)) {
                Log.d("JarvisAccessibility", "Answered call via text label: '$label'")
                return true
            }
        }

        for (root in getSearchRoots()) {
            val nodes = root.findAccessibilityNodeInfosByViewId("com.android.incallui:id/answer")
                .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.google.android.dialer:id/incall_first_button") }
                .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.google.android.dialer:id/answer_button") }
                .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.samsung.android.incallui:id/voice_answer_button") }
                .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.android.dialer:id/answer_button") }
            for (node in nodes) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d("JarvisAccessibility", "Answered call via view ID node click")
                        return true
                    }
                }
            }
        }

        // Swipe up gesture from bottom center
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val startX = width / 2f
        val startY = height * 0.8f
        val endY = height * 0.3f
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val swiped = dispatchGesture(gesture, null, null)
        Log.d("JarvisAccessibility", "Answered call via swipe up gesture: $swiped")
        return swiped
    }

    fun performRejectCallGesture(): Boolean {
        Log.d("JarvisAccessibility", "Attempting to reject call via Accessibility")
        val rejectLabels = listOf("Decline", "Reject", "Dismiss", "Cut call", "Swipe down to decline", "Hang up", "డిస్మిస్", "రిజెక్ట్")
        for (label in rejectLabels) {
            if (genericClickByText(label)) {
                Log.d("JarvisAccessibility", "Rejected call via text label: '$label'")
                return true
            }
        }

        for (root in getSearchRoots()) {
            val nodes = root.findAccessibilityNodeInfosByViewId("com.android.incallui:id/decline")
                .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.google.android.dialer:id/incall_second_button") }
                .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.google.android.dialer:id/decline_button") }
                .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.samsung.android.incallui:id/voice_reject_button") }
                .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.android.dialer:id/decline_button") }
            for (node in nodes) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d("JarvisAccessibility", "Rejected call via view ID node click")
                        return true
                    }
                }
            }
        }

        // Swipe down gesture from bottom center
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val startX = width / 2f
        val startY = height * 0.8f
        val endY = height * 0.95f
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val swiped = dispatchGesture(gesture, null, null)
        Log.d("JarvisAccessibility", "Rejected call via swipe down gesture: $swiped")
        return swiped
    }

    fun dumpScreenToJson(): String {
        val root = rootInActiveWindow ?: return "{\"error\": \"No active window found or permission is not granted.\"}"
        val json = StringBuilder()
        json.append("[\n")
        traverseAndDump(root, json, 0)
        // Remove trailing comma if exists
        if (json.endsWith(",")) {
            json.setLength(json.length - 1)
        }
        json.append("\n]")
        return json.toString()
    }

    private fun traverseAndDump(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 30) return // Safety depth limit
        val text = node.text?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""
        val isClickable = node.isClickable

        if (text.isNotEmpty() || viewId.isNotEmpty()) {
            val indent = "  ".repeat(depth + 1)
            sb.append(indent).append("{\n")
            sb.append(indent).append("  \"class\": \"").append(className.replace("\"", "\\\"")).append("\",\n")
            sb.append(indent).append("  \"id\": \"").append(viewId.replace("\"", "\\\"")).append("\",\n")
            sb.append(indent).append("  \"text\": \"").append(text.replace("\"", "\\\"")).append("\",\n")
            sb.append(indent).append("  \"clickable\": ").append(isClickable).append("\n")
            sb.append(indent).append("},\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndDump(child, sb, depth + 1)
        }
    }

    private fun tapScreen(x: Float, y: Float): Boolean {
        val path = android.graphics.Path()
        path.moveTo(x, y)

        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)
            )
            .build()

        return dispatchGesture(gesture, null, null)
    }

    fun unlockWithPin(pin: String, onComplete: (() -> Unit)? = null): Boolean {
        com.example.security.MaxLockAutomationEngine(this).start(pin, onComplete)
        return true
    }

    private fun completeTask(task: AutomationTask) {
        task.step = 4
        pendingTask = null
        directClickTime = 0L
        if (task.isScheduled) {
            val securitySettings = com.example.security.SecuritySettings(this)
            if (securitySettings.autoRelockEnabled) {
                handler.postDelayed({
                    lockPhone()
                }, securitySettings.lockDelayMs)
            }
        }
    }
    fun lockPhone() {
        val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val component = android.content.ComponentName(this, com.example.receiver.MyDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(component)) {
            dpm.lockNow()
        }
    }

    override fun onInterrupt() {
        Log.d("JarvisAccessibility", "Accessibility service interrupted.")
    }

}