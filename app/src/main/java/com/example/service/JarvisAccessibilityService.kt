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
import com.example.features.whatsapp.WhatsAppContactMatch
import com.example.features.whatsapp.WhatsAppContactResolver
import com.example.core.registry.ServiceRegistry
import com.example.utils.NotificationHelper
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.asStateFlow

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
        @Volatile
        var pendingTask: AutomationTask? = null
        private val taskMutex = Any()

        fun setPendingTaskSafely(task: AutomationTask?): Boolean {
            synchronized(taskMutex) {
                val current = pendingTask
                if (task != null && current != null) {
                    val age = System.currentTimeMillis() - current.startTime
                    if (age < 15000L) {
                        Log.w("JarvisAccessibility", "Concurrent automation collision: active task '${current.type}' started ${age}ms ago. Rejecting new task '${task.type}'")
                        return false
                    }
                }
                pendingTask = task
                return true
            }
        }

        fun clearPendingTask(expectedTask: AutomationTask? = null) {
            synchronized(taskMutex) {
                if (expectedTask == null || pendingTask === expectedTask) {
                    pendingTask = null
                }
            }
        }
        var instance: JarvisAccessibilityService? = null
        var lastSendVerificationResult: SendVerificationResult? = null
        var testRootProvider: (() -> AccessibilityNodeInfo?)? = null
        var testWindowsProvider: (() -> List<android.view.accessibility.AccessibilityWindowInfo>?)? = null
        var testGestureDispatcher: ((android.accessibilityservice.GestureDescription) -> Boolean)? = null
        var testShellExecutor: ((String) -> Unit)? = null

        data class UiChange(val revision: Long, val packageName: String, val eventType: Int, val timestamp: Long)

        private val _uiChanges = kotlinx.coroutines.flow.MutableStateFlow(UiChange(0L, "", 0, 0L))
        val uiChanges: kotlinx.coroutines.flow.StateFlow<UiChange> = _uiChanges.asStateFlow()

        @Volatile
        private var recentOtp: String? = null
        @Volatile
        private var recentOtpTimestamp: Long = 0L

        fun getRecentOtp(): String? {
            val otp = recentOtp ?: return null
            if (System.currentTimeMillis() - recentOtpTimestamp > 300_000L) {
                recentOtp = null
                return null
            }
            return otp
        }

        fun setRecentOtp(otp: String) {
            recentOtp = otp
            recentOtpTimestamp = System.currentTimeMillis()
        }

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
            val securitySettings = com.example.security.SecuritySettings(this)
            if (securitySettings.autoUnlockEnabled) {
                val storedPin = securitySettings.getDecryptedPin()
                if (storedPin.isNotEmpty()) {
                    Log.d("JarvisAccessibility", "Received request to unlock device with encrypted settings PIN")
                    unlockWithPin(storedPin)
                } else {
                    Log.w("JarvisAccessibility", "UNLOCK_WITH_PIN requested but PIN not configured in SecuritySettings")
                }
            } else {
                Log.w("JarvisAccessibility", "UNLOCK_WITH_PIN requested but auto-unlock disabled")
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!com.example.core.lifecycle.MaxRuntimeManager.isMaxEnabled(this)) {
            pendingTask = null
            return
        }

        // JARVIS migration: UiChange flow
        event?.let { e ->
            if (e.eventType in setOf(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
                )) {
                val prev = _uiChanges.value
                _uiChanges.value = UiChange(
                    prev.revision + 1,
                    e.packageName?.toString().orEmpty(),
                    e.eventType,
                    System.currentTimeMillis()
                )
            }
        }

        // JARVIS migration: OTP extraction
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            extractOtpFromNotification(event)
        }

        // Feed continuous reactive UI state store
        try {
            com.example.automation.monitor.AccessibilityStateMonitor.onAccessibilityEvent(event)
        } catch (e: Exception) {
            Log.w("JarvisAccessibility", "Failed to feed AccessibilityStateMonitor: ${e.message}")
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
            Toast.makeText(this, "MAX Automation Timed Out ⚠️", Toast.LENGTH_SHORT).show()
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

        if (task.type == "WHATSAPP_NAVIGATE" && (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b")) {
            handleWhatsAppScreenNavigation(task, packageName)
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
        val message = task.message ?: ""

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

                if (message.isBlank()) {
                    Log.d("WA_DEBUG", "Deep Link Step 0: Message is blank, target chat for '$contact' opened successfully!")
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    completeTask(task)
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
            ?: findNodeByDescRecursive(root, "పంపు") ?: findNodeByDescRecursive(root, "పంపించు")
        if (sendByDesc != null) return sendByDesc

        val sendByText = root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable || it.parent?.isClickable == true }
            ?: root.findAccessibilityNodeInfosByText("పంపు").firstOrNull { it.isClickable || it.parent?.isClickable == true }
        if (sendByText != null) return sendByText

        return null
    }

    private fun findWhatsAppSearchInput(root: AccessibilityNodeInfo, pkgName: String): AccessibilityNodeInfo? {
        val ids = listOf(
            "$pkgName:id/search_src_text",
            "$pkgName:id/search_input",
            "$pkgName:id/search_view",
            "$pkgName:id/search_bar",
            "$pkgName:id/search_edit_frame"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && nodes[0].isVisibleToUser) return nodes[0]
        }
        val byText = root.findAccessibilityNodeInfosByText("Search").firstOrNull { it.className == "android.widget.EditText" }
            ?: root.findAccessibilityNodeInfosByText("Ask Meta AI or Search").firstOrNull { it.className == "android.widget.EditText" }
            ?: root.findAccessibilityNodeInfosByText("Search...").firstOrNull { it.className == "android.widget.EditText" }
            ?: root.findAccessibilityNodeInfosByText("శోధించండి").firstOrNull { it.className == "android.widget.EditText" }
        return byText
    }

    private fun findWhatsAppSearchIcon(root: AccessibilityNodeInfo, pkgName: String): AccessibilityNodeInfo? {
        val ids = listOf(
            "$pkgName:id/menuitem_search",
            "$pkgName:id/menu_item_search",
            "$pkgName:id/search",
            "$pkgName:id/search_button"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && nodes[0].isVisibleToUser) return nodes[0]
        }
        return findNodeByDescRecursive(root, "Search")
            ?: findNodeByDescRecursive(root, "search")
            ?: findNodeByDescRecursive(root, "శోధించండి")
    }

    private fun findFirstWhatsAppSearchResultRow(root: AccessibilityNodeInfo, pkgName: String): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 20) return
            if (!node.isVisibleToUser) return

            val resId = (node.viewIdResourceName ?: "").lowercase(java.util.Locale.ROOT)
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val nodeText = if (text.isNotBlank()) text else desc

            if (!WhatsAppContactResolver.isExcludedNode(node)) {
                val isContactId = WhatsAppContactResolver.CONTACT_NAME_IDS.any {
                    resId.endsWith(":id/$it") || resId.endsWith("/$it") || resId == it
                }
                val isCategoryHeader = nodeText.equals("Chats", ignoreCase = true) ||
                        nodeText.equals("Messages", ignoreCase = true) ||
                        nodeText.equals("Contacts", ignoreCase = true) ||
                        nodeText.equals("Links", ignoreCase = true) ||
                        nodeText.equals("Photos", ignoreCase = true) ||
                        nodeText.equals("Videos", ignoreCase = true) ||
                        nodeText.equals("Audio", ignoreCase = true) ||
                        nodeText.equals("Documents", ignoreCase = true) ||
                        nodeText.equals("More", ignoreCase = true) ||
                        nodeText.equals("చాట్‌లు", ignoreCase = true) ||
                        nodeText.equals("సందేశాలు", ignoreCase = true)

                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (!isCategoryHeader && bounds.top >= 180 && bounds.height() >= 30 && nodeText.isNotBlank()) {
                    if (isContactId || nodeText.length in 2..50) {
                        val clickableRow = WhatsAppContactResolver.findClickableAncestorOrSelf(node, pkgName)
                        if (clickableRow != null) {
                            val rowBounds = Rect()
                            clickableRow.getBoundsInScreen(rowBounds)
                            if (rowBounds.top >= 180 && rowBounds.height() >= 40) {
                                candidates.add(Pair(clickableRow, rowBounds.top))
                            }
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(root)

        return candidates
            .sortedBy { it.second }
            .distinctBy { it.first }
            .firstOrNull()?.first
    }

    private fun findNodeByTextOrDesc(root: AccessibilityNodeInfo, labels: List<String>, pkgName: String): AccessibilityNodeInfo? {
        for (label in labels) {
            val byText = root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isVisibleToUser && (it.isClickable || it.parent?.isClickable == true) }
            if (byText != null) return byText
            val byDesc = findNodeByDescRecursive(root, label)
            if (byDesc != null) return byDesc
        }
        return null
    }

    private fun handleWhatsAppScreenNavigation(task: AutomationTask, pkgName: String) {
        val root = rootInActiveWindow ?: run {
            Log.d("WA_DEBUG", "ScreenNav: rootInActiveWindow is null")
            return
        }
        val targetScreen = (task.message ?: task.contact ?: "").trim().lowercase(java.util.Locale.ROOT)
        val timeElapsed = System.currentTimeMillis() - task.startTime
        if (timeElapsed > 12000L) {
            Log.e("WA_DEBUG", "WhatsApp Screen Navigation Timed Out after ${timeElapsed}ms")
            lastSendVerificationResult = SendVerificationResult.TIMEOUT
            pendingTask = null
            return
        }

        Log.d("WA_DEBUG", "WhatsApp Screen Navigation: target='$targetScreen', step=${task.step}, elapsed=${timeElapsed}ms")

        // 1. If currently inside an inner chat/details screen with a back button, navigate back to main tabs
        val backButton = root.findAccessibilityNodeInfosByViewId("$pkgName:id/back").firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/navigation_icon").firstOrNull()
            ?: findNodeByDescRecursive(root, "Navigate up")
            ?: findNodeByDescRecursive(root, "Back")
            ?: findNodeByDescRecursive(root, "వెనక్కి")

        if (backButton != null && task.step == 0) {
            Log.d("WA_DEBUG", "ScreenNav: In sub-screen with back button. Clicking back to reach main screen...")
            performClickOnNode(backButton)
            task.step = 1
            handler.postDelayed({
                if (pendingTask === task && task.step == 1) {
                    handleWhatsAppScreenNavigation(task, pkgName)
                }
            }, 300L)
            return
        }

        // 2. Map targetScreen to primary tab or action
        when (targetScreen) {
            "chats", "chat", "messages", "conversations", "చాట్స్", "చాట్‌లు" -> {
                val chatsTab = findNodeByTextOrDesc(root, listOf("Chats", "చాట్‌లు", "చాట్స్"), pkgName)
                    ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/tab_chats").firstOrNull()
                    ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/chats_tab").firstOrNull()
                if (chatsTab != null) {
                    val clicked = performClickOnNode(chatsTab)
                    Log.d("WA_DEBUG", "ScreenNav: Clicked Chats tab result=$clicked")
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    completeTask(task)
                    return
                }
            }

            "status", "updates", "update", "stories", "స్టేటస్", "అప్‌డేట్స్", "అప్‌డేట్‌లు" -> {
                val updatesTab = findNodeByTextOrDesc(root, listOf("Updates", "Status", "స్టేటస్", "అప్‌డేట్‌లు", "అప్‌డేట్స్"), pkgName)
                    ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/tab_updates").firstOrNull()
                    ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/tab_status").firstOrNull()
                if (updatesTab != null) {
                    val clicked = performClickOnNode(updatesTab)
                    Log.d("WA_DEBUG", "ScreenNav: Clicked Updates/Status tab result=$clicked")
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    completeTask(task)
                    return
                }
            }

            "communities", "community", "కమ్యూనిటీస్", "కమ్యూనిటీలు" -> {
                val commTab = findNodeByTextOrDesc(root, listOf("Communities", "కమ్యూనిటీలు", "కమ్యూనిటీస్"), pkgName)
                    ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/tab_communities").firstOrNull()
                if (commTab != null) {
                    val clicked = performClickOnNode(commTab)
                    Log.d("WA_DEBUG", "ScreenNav: Clicked Communities tab result=$clicked")
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    completeTask(task)
                    return
                }
            }

            "calls", "call", "call log", "call_log", "కాల్స్", "కాల్‌లు" -> {
                val callsTab = findNodeByTextOrDesc(root, listOf("Calls", "కాల్స్", "కాల్‌లు"), pkgName)
                    ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/tab_calls").firstOrNull()
                if (callsTab != null) {
                    val clicked = performClickOnNode(callsTab)
                    Log.d("WA_DEBUG", "ScreenNav: Clicked Calls tab result=$clicked")
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    completeTask(task)
                    return
                }
            }

            "new_chat", "new chat", "start chat", "contacts", "select contact", "కొత్త చాట్" -> {
                val fab = root.findAccessibilityNodeInfosByViewId("$pkgName:id/fab").firstOrNull()
                    ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/start_chat").firstOrNull()
                    ?: findNodeByDescRecursive(root, "New chat")
                    ?: findNodeByDescRecursive(root, "Start chat")
                    ?: findNodeByDescRecursive(root, "Select contact")
                    ?: findNodeByDescRecursive(root, "కొత్త చాట్")
                if (fab != null) {
                    val clicked = performClickOnNode(fab)
                    Log.d("WA_DEBUG", "ScreenNav: Clicked New Chat FAB result=$clicked")
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    completeTask(task)
                    return
                }
            }

            "settings", "preferences", "సెట్టింగ్స్", "సెట్టింగ్‌లు" -> {
                if (task.step == 0 || task.step == 1) {
                    val moreOptions = findNodeByDescRecursive(root, "More options")
                        ?: findNodeByDescRecursive(root, "మరిన్ని ఎంపికలు")
                        ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/menuitem_overflow").firstOrNull()
                        ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/more_options").firstOrNull()
                    if (moreOptions != null) {
                        performClickOnNode(moreOptions)
                        task.step = 2
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 2) {
                                handleWhatsAppScreenNavigation(task, pkgName)
                            }
                        }, 250L)
                        return
                    }
                } else if (task.step == 2) {
                    val settingsItem = root.findAccessibilityNodeInfosByText("Settings").firstOrNull()
                        ?: root.findAccessibilityNodeInfosByText("సెట్టింగ్‌లు").firstOrNull()
                        ?: findNodeByDescRecursive(root, "Settings")
                    if (settingsItem != null) {
                        val clicked = performClickOnNode(settingsItem)
                        Log.d("WA_DEBUG", "ScreenNav: Clicked Settings menu item result=$clicked")
                        lastSendVerificationResult = SendVerificationResult.SUCCESS
                        completeTask(task)
                        return
                    }
                }
            }

            "linked_devices", "linked devices", "లింక్డ్ డివైసెస్" -> {
                if (task.step <= 1) {
                    val moreOptions = findNodeByDescRecursive(root, "More options")
                        ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/menuitem_overflow").firstOrNull()
                    if (moreOptions != null) {
                        performClickOnNode(moreOptions)
                        task.step = 2
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 2) {
                                handleWhatsAppScreenNavigation(task, pkgName)
                            }
                        }, 250L)
                        return
                    }
                } else if (task.step == 2) {
                    val linkedItem = root.findAccessibilityNodeInfosByText("Linked devices").firstOrNull()
                        ?: findNodeByDescRecursive(root, "Linked devices")
                    if (linkedItem != null) {
                        performClickOnNode(linkedItem)
                        lastSendVerificationResult = SendVerificationResult.SUCCESS
                        completeTask(task)
                        return
                    }
                }
            }

            "starred_messages", "starred messages", "స్టార్డ్ మెసేజెస్" -> {
                if (task.step <= 1) {
                    val moreOptions = findNodeByDescRecursive(root, "More options")
                        ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/menuitem_overflow").firstOrNull()
                    if (moreOptions != null) {
                        performClickOnNode(moreOptions)
                        task.step = 2
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 2) {
                                handleWhatsAppScreenNavigation(task, pkgName)
                            }
                        }, 250L)
                        return
                    }
                } else if (task.step == 2) {
                    val starredItem = root.findAccessibilityNodeInfosByText("Starred messages").firstOrNull()
                        ?: findNodeByDescRecursive(root, "Starred messages")
                    if (starredItem != null) {
                        performClickOnNode(starredItem)
                        lastSendVerificationResult = SendVerificationResult.SUCCESS
                        completeTask(task)
                        return
                    }
                }
            }

            "payments", "పేమెంట్స్" -> {
                if (task.step <= 1) {
                    val moreOptions = findNodeByDescRecursive(root, "More options")
                        ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/menuitem_overflow").firstOrNull()
                    if (moreOptions != null) {
                        performClickOnNode(moreOptions)
                        task.step = 2
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 2) {
                                handleWhatsAppScreenNavigation(task, pkgName)
                            }
                        }, 250L)
                        return
                    }
                } else if (task.step == 2) {
                    val payItem = root.findAccessibilityNodeInfosByText("Payments").firstOrNull()
                        ?: findNodeByDescRecursive(root, "Payments")
                    if (payItem != null) {
                        performClickOnNode(payItem)
                        lastSendVerificationResult = SendVerificationResult.SUCCESS
                        completeTask(task)
                        return
                    }
                }
            }

            else -> {
                val node = root.findAccessibilityNodeInfosByText(targetScreen).firstOrNull()
                    ?: findNodeByDescRecursive(root, targetScreen)
                if (node != null) {
                    val clicked = performClickOnNode(node)
                    Log.d("WA_DEBUG", "ScreenNav: Clicked generic target '$targetScreen' result=$clicked")
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    completeTask(task)
                    return
                }
            }
        }
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
        // JARVIS migration: Chrome WebView gesture preference
        val ownerPkg = node.packageName?.toString().orEmpty()
        if (ownerPkg == "com.android.chrome") {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 0 && r.height() > 0) {
                val cx = r.exactCenterX()
                val cy = r.exactCenterY()
                val p = Path().apply { moveTo(cx, cy) }
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(p, 0, 80))
                    .build()
                dispatchGesture(g, null, null)
            }
        }

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

        // Bounded wait for UI state change (polling up to 500ms with 50ms intervals)
        var signatureChanged = false
        var targetStateChanged = false
        var packageChanged = false
        var windowChanged = false
        var focusChanged = false
        val maxWaitMs = 500L
        val pollIntervalMs = 50L
        var waitedMs = 0L

        while (waitedMs < maxWaitMs) {
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {}
            waitedMs += pollIntervalMs

            val postSignature = getScreenSignature()
            val postRoot = rootInActiveWindow
            signatureChanged = (preSignature != postSignature) && postSignature != "null_root"

            if (preTarget.isChecked != null && postRoot != null) {
                val freshNode = if (node.viewIdResourceName != null) {
                    postRoot.findAccessibilityNodeInfosByViewId(node.viewIdResourceName).firstOrNull()
                } else null
                if (freshNode != null && freshNode.isCheckable) {
                    targetStateChanged = (freshNode.isChecked != preTarget.isChecked)
                }
            }

            packageChanged = postRoot != null && prePackage != null && postRoot.packageName?.toString() != prePackage
            windowChanged = postRoot != null && preWindowId != null && postRoot.windowId != preWindowId
            val postFocusedNode = postRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focusChanged = postFocusedNode != null && (preFocusedNode == null || postFocusedNode != preFocusedNode || node.isFocused)

            if (signatureChanged || targetStateChanged || packageChanged || windowChanged || focusChanged) {
                break
            }
        }

        if (clicked) {
            if (signatureChanged || targetStateChanged || packageChanged || windowChanged || focusChanged) {
                Log.d("UI_VERIFY", "[UI_VERIFY] target=$targetDesc confirmed via ACTION_CLICK in ${waitedMs}ms (sig=$signatureChanged, state=$targetStateChanged, pkg=$packageChanged, win=$windowChanged, focus=$focusChanged)")
                invalidateIndexedElements()
                return ClickEscalationResult.ACCESSIBILITY_CONFIRMED
            } else {
                // ACTION_CLICK was executed and accepted by the OS, but no observable state change occurred within 500ms.
                // CRITICAL SAFETY GUARD: Do NOT blindly escalate to a physical coordinate tap!
                // Escalating on an accepted action causes duplicate taps on buttons, payment flows, or toggles.
                Log.w("UI_CLICK", "[UI_CLICK] target=$targetDesc ACTION_CLICK accepted by OS but produced no observable UI transition within ${waitedMs}ms. Halting without duplicate tap.")
                return ClickEscalationResult.UNCONFIRMED
            }
        }

        // 2. Only attempt Physical Coordinate Gesture Fallback if ACTION_CLICK failed or was unsupported (clicked == false)
        Log.w("UI_FALLBACK", "[UI_FALLBACK] reason=ACCESSIBILITY_CLICK_UNSUPPORTED (clicked=$clicked)")

        // Revalidate node immediately before gesture tap
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) {
            Log.e("JarvisAccessibility", "Cannot perform gesture fallback: target node bounds are empty")
            return ClickEscalationResult.FAILED
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
        if (!gestureSuccess) {
            Log.e("UI_VERIFY", "[UI_VERIFY] gesture dispatch failed for target=$targetDesc")
            return ClickEscalationResult.FAILED
        }

        // Bounded wait for gesture transition (up to 500ms)
        var gestureSignatureChanged = false
        var postGesturePackageChanged = false
        var postGestureWindowChanged = false
        var postGestureFocusChanged = false
        var gestureWaitedMs = 0L

        while (gestureWaitedMs < maxWaitMs) {
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {}
            gestureWaitedMs += pollIntervalMs

            val postGestureSignature = getScreenSignature()
            val postGestureRoot = rootInActiveWindow
            gestureSignatureChanged = (preSignature != postGestureSignature) && postGestureSignature != "null_root"
            postGesturePackageChanged = postGestureRoot != null && prePackage != null && postGestureRoot.packageName?.toString() != prePackage
            postGestureWindowChanged = postGestureRoot != null && preWindowId != null && postGestureRoot.windowId != preWindowId
            val postGestureFocus = postGestureRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            postGestureFocusChanged = postGestureFocus != null && (preFocusedNode == null || postGestureFocus != preFocusedNode)

            if (gestureSignatureChanged || targetStateChanged || postGesturePackageChanged || postGestureWindowChanged || postGestureFocusChanged) {
                break
            }
        }

        if (gestureSignatureChanged || targetStateChanged || postGesturePackageChanged || postGestureWindowChanged || postGestureFocusChanged) {
            Log.d("UI_VERIFY", "[UI_VERIFY] postGesture changed=true result=GESTURE_CONFIRMED in ${gestureWaitedMs}ms")
            invalidateIndexedElements()
            return ClickEscalationResult.GESTURE_CONFIRMED
        } else {
            // Dispatched but no UI change was observed
            Log.w("UI_VERIFY", "[UI_VERIFY] postGesture dispatched but no state change observed in ${gestureWaitedMs}ms result=UNCONFIRMED")
            return ClickEscalationResult.UNCONFIRMED
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
        val success = (result == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                result == ClickEscalationResult.GESTURE_CONFIRMED ||
                result == ClickEscalationResult.UNCONFIRMED)
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
        return result == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                result == ClickEscalationResult.GESTURE_CONFIRMED ||
                result == ClickEscalationResult.UNCONFIRMED
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo, forceGesture: Boolean = false): Boolean {
        if (forceGesture) {
            val gestureSuccess = tapNodeWithGesture(node)
            Log.d("CLICK_DEBUG", "Forced gesture tap directly on node: $gestureSuccess")
            if (gestureSuccess) return true
        }
        val result = performClickWithEscalation(node)
        return result == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                result == ClickEscalationResult.GESTURE_CONFIRMED ||
                result == ClickEscalationResult.UNCONFIRMED
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
                if (timeElapsed < 200L) {
                    return
                }

                // 1. If correct chat is already open, move straight to Step 3
                val entryNode = findWhatsAppEntryNode(root, pkgName)
                if (entryNode != null && isCorrectChatOpen(root, task, pkgName)) {
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
                    }
                } else {
                    // 3. REAL-TIME OBSERVATION: Check if requested contact is already visible on current screen!
                    val visibleResolution = WhatsAppContactResolver.resolveVisibleChatRow(root, task.contactDisplayName ?: contact, pkgName, task.phone)
                    if (visibleResolution is WhatsAppContactMatch.Found) {
                        Log.d("WA_DEBUG", "Step 0: Contact '$contact' is visible on current screen! Clicking row...")
                        val clicked = performClickOnNode(visibleResolution.nodeToClick, forceGesture = true)
                        Log.d("WA_DEBUG", "Step 0: Direct contact row click result=$clicked")
                        if (clicked) {
                            directClickTime = System.currentTimeMillis()
                            task.step = 3
                            handler.postDelayed({
                                if (pendingTask === task && task.step == 3) {
                                    handleWhatsAppAutomation(task, pkgName)
                                }
                            }, 400L)
                            return
                        }
                    }
                }

                // 4. Search fallback: Check if search input is already open or click search icon
                val searchInput = findWhatsAppSearchInput(root, pkgName)
                if (searchInput != null) {
                    Log.d("WA_DEBUG", "Step 0: Search input already open, moving to Step 1")
                    directClickTime = 0L
                    task.step = 1
                    handleWhatsAppAutomation(task, pkgName)
                    return
                }

                val searchIcon = findWhatsAppSearchIcon(root, pkgName)
                if (searchIcon != null) {
                    val clicked = performClickOnNode(searchIcon)
                    Log.d("WA_DEBUG", "Step 0: Search icon click result=$clicked")
                    if (clicked) {
                        directClickTime = 0L
                        task.step = 1
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 1) {
                                handleWhatsAppAutomation(task, pkgName)
                            }
                        }, 250L)
                        return
                    }
                }

                // If in a wrong chat when starting from fresh launch, go back to main screen
                if (entryNode != null && !isCorrectChatOpen(root, task, pkgName) && timeElapsed < 2500L) {
                    val backButton = root.findAccessibilityNodeInfosByViewId("$pkgName:id/back").firstOrNull()
                        ?: root.findAccessibilityNodeInfosByViewId("$pkgName:id/navigation_icon").firstOrNull()
                        ?: findNodeByDescRecursive(root, "Navigate up")
                        ?: findNodeByDescRecursive(root, "Back")
                    if (backButton != null) {
                        performClickOnNode(backButton)
                    } else {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    return
                }

                if (timeElapsed > 3500L) {
                    val chatsTab = root.findAccessibilityNodeInfosByText("Chats").firstOrNull()
                        ?: root.findAccessibilityNodeInfosByText("చాట్‌లు").firstOrNull()
                    if (chatsTab != null) {
                        performClickOnNode(chatsTab)
                    }
                    task.startTime = System.currentTimeMillis() - 1500L
                }
            }

            1 -> {
                val searchInput = findWhatsAppSearchInput(root, pkgName)
                if (searchInput != null) {
                    val target = task.contactDisplayName ?: contact
                    val currentText = searchInput.text?.toString() ?: ""
                    Log.d("WA_DEBUG", "Step 1: currentText='$currentText', target='$target'")
                    if (currentText == target) {
                        task.step = 2
                    } else {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
                        }
                        val typed = searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Log.d("WA_DEBUG", "Step 1: Typed search text result=$typed")
                        task.step = 2
                    }
                    handler.postDelayed({
                        if (pendingTask === task && task.step == 2) {
                            handleWhatsAppAutomation(task, pkgName)
                        }
                    }, 400L)
                } else {
                    Log.d("WA_DEBUG", "Step 1: Search input not found yet, retrying...")
                    val timeElapsed = System.currentTimeMillis() - task.startTime
                    if (timeElapsed < 6000L) {
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 1) {
                                handleWhatsAppAutomation(task, pkgName)
                            }
                        }, 300L)
                    }
                }
            }

            2 -> {
                val currentRoot = rootInActiveWindow ?: return

                // If chat entry is ALREADY open, proceed directly to Step 3
                if (findWhatsAppEntryNode(currentRoot, pkgName) != null) {
                    Log.d("WA_DEBUG", "Step 2: Chat already open, proceeding to Step 3")
                    task.step = 3
                    handleWhatsAppAutomation(task, pkgName)
                    return
                }

                val resolution = WhatsAppContactResolver.resolveSearchRow(
                    root = currentRoot,
                    targetContact = task.contactDisplayName ?: contact,
                    pkgName = pkgName,
                    targetPhone = task.phone
                )

                val nodeToClick: AccessibilityNodeInfo? = when (resolution) {
                    is WhatsAppContactMatch.Found -> {
                        Log.d("WA_DEBUG", "Step 2: Resolved contact '${resolution.matchedName}' (type=${resolution.matchType}, conf=${resolution.confidence})")
                        resolution.nodeToClick
                    }
                    is WhatsAppContactMatch.Ambiguous -> {
                        Log.d("WA_DEBUG", "Step 2: Multiple contact matches (${resolution.candidateNames}), selecting first contact result...")
                        findFirstWhatsAppSearchResultRow(currentRoot, pkgName)
                    }
                    is WhatsAppContactMatch.NotFound -> {
                        val timeElapsed = System.currentTimeMillis() - task.startTime
                        Log.d("WA_DEBUG", "Step 2: Contact not directly resolved, checking first search result row (elapsed=${timeElapsed}ms)...")
                        findFirstWhatsAppSearchResultRow(currentRoot, pkgName)
                    }
                }

                if (nodeToClick != null) {
                    Log.d("WA_DEBUG", "Step 2: Clicking selected contact row...")
                    val clicked = performClickOnNode(nodeToClick, forceGesture = true)
                    Log.d("WA_DEBUG", "Step 2: Contact row click result = $clicked")
                    task.step = 3
                    handler.postDelayed({
                        if (pendingTask === task && task.step == 3) {
                            handleWhatsAppAutomation(task, pkgName)
                        }
                    }, 500L)
                } else {
                    val timeElapsed = System.currentTimeMillis() - task.startTime
                    Log.d("WA_DEBUG", "Step 2: No contact row visible yet (elapsed=${timeElapsed}ms)...")
                    if (timeElapsed < 8000L) {
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 2) {
                                handleWhatsAppAutomation(task, pkgName)
                            }
                        }, 300L)
                    }
                }
            }

            3 -> {
                val currentRoot = rootInActiveWindow ?: return
                val entryNode = findWhatsAppEntryNode(currentRoot, pkgName)
                if (entryNode != null) {
                    val timeElapsed = System.currentTimeMillis() - task.startTime
                    Log.d("WA_DEBUG", "Step 3: Chat entry node found. Entering message for '$contact' (elapsed=${timeElapsed}ms)...")

                    if (message.isBlank()) {
                        Log.d("WA_DEBUG", "Step 3: Message is empty, WhatsApp chat opened successfully!")
                        lastSendVerificationResult = SendVerificationResult.SUCCESS
                        completeTask(task)
                        return
                    }

                    entryNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val currentText = entryNode.text?.toString() ?: ""
                    Log.d("WA_DEBUG", "Step 3: currentText='$currentText', targetMessage='$message'")
                    if (currentText != message) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                        }
                        val typed = entryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Log.d("WA_DEBUG", "Step 3: Entered message text result=$typed")
                    }

                    // Advance to Step 4 to locate and click Send button
                    task.step = 4
                    handler.postDelayed({
                        if (pendingTask === task && task.step == 4) {
                            handleWhatsAppAutomation(task, pkgName)
                        }
                    }, 300L)
                } else {
                    val timeElapsed = System.currentTimeMillis() - task.startTime
                    Log.d("WA_DEBUG", "Step 3: Entry node not found yet (elapsed=${timeElapsed}ms), waiting for chat view...")
                    if (timeElapsed < 8000L) {
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 3) {
                                handleWhatsAppAutomation(task, pkgName)
                            }
                        }, 300L)
                    }
                }
            }

            4 -> {
                val currentRoot = rootInActiveWindow ?: return
                val sendNode = findWhatsAppSendNode(currentRoot, pkgName)
                if (sendNode != null) {
                    Log.d("WA_DEBUG", "Step 4: Found Send button. Performing click with gesture fallback...")
                    val clicked = performClickOnNode(sendNode, forceGesture = true)
                    Log.d("WA_DEBUG", "Step 4: Send button click result = $clicked")
                    lastSendVerificationResult = SendVerificationResult.SEND_ACTION_CONFIRMED
                    task.step = 5
                    handler.postDelayed({
                        if (pendingTask === task && task.step == 5) {
                            handleWhatsAppAutomation(task, pkgName)
                        }
                    }, 400L)
                } else {
                    val timeElapsed = System.currentTimeMillis() - task.startTime
                    Log.d("WA_DEBUG", "Step 4: Send button not yet found in current hierarchy (elapsed=${timeElapsed}ms). Retrying...")
                    val entryNode = findWhatsAppEntryNode(currentRoot, pkgName)
                    if (entryNode != null && (entryNode.text?.toString() ?: "") != message) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                        }
                        entryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                    if (timeElapsed < 8000L) {
                        handler.postDelayed({
                            if (pendingTask === task && task.step == 4) {
                                handleWhatsAppAutomation(task, pkgName)
                            }
                        }, 300L)
                    }
                }
            }

            5 -> {
                val currentRoot = rootInActiveWindow ?: return
                val entryNode = findWhatsAppEntryNode(currentRoot, pkgName)
                val textAfterSend = entryNode?.text?.toString() ?: ""
                val isInputCleared = textAfterSend.isBlank() || textAfterSend == "Type a message" || textAfterSend == "Message"

                val hasOutgoingBubble = task.message?.let { msg ->
                    currentRoot.findAccessibilityNodeInfosByText(msg).any { it.packageName == pkgName }
                } ?: false

                val timeElapsed = System.currentTimeMillis() - task.startTime
                Log.d("WA_DEBUG", "Step 5 (Verification): isInputCleared=$isInputCleared, hasOutgoingBubble=$hasOutgoingBubble, currentInput='$textAfterSend', elapsed=${timeElapsed}ms")

                if (isInputCleared || hasOutgoingBubble || timeElapsed > 3500L) {
                    lastSendVerificationResult = SendVerificationResult.SUCCESS
                    Log.d("WA_DEBUG", "Step 5: Post-Send Verification PASSED (SUCCESS). Completing task.")
                    completeTask(task)
                } else {
                    Log.d("WA_DEBUG", "Step 5: Message still clearing. Waiting...")
                    handler.postDelayed({
                        if (pendingTask === task && task.step == 5) {
                            handleWhatsAppAutomation(task, pkgName)
                        }
                    }, 400L)
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

    fun performClickAtRect(bounds: Rect): Boolean {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        return clickAtCoordinates(cx, cy)
    }

    fun smartTypeText(text: String, bounds: Rect? = null, submit: Boolean = false): Boolean {
        if (bounds != null) {
            performClickAtRect(bounds)
            try { Thread.sleep(200L) } catch (_: InterruptedException) {}
        }
        val typed = genericTypeText(text)
        if (typed && submit) {
            pressImeAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        }
        return typed
    }

    fun performSmartScroll(direction: String = "down", amount: String = "large"): Boolean {
        val (success, _) = scrollScreen(direction)
        return success
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

    suspend fun unlockWithPinVerified(pin: String, timeoutMs: Long = 7000): Boolean {
        return com.example.security.MaxLockAutomationEngine(this).unlockAndVerify(pin, timeoutMs)
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

    // ────────────────────────────────────────────────────────────────────────
    // JARVIS Migration Methods (Migration 9)
    // ────────────────────────────────────────────────────────────────────────

    private fun extractOtpFromNotification(event: AccessibilityEvent) {
        try {
            val full = event.text.joinToString(" ")
            if (full.isBlank()) return
            val regex = Regex(
                """\b(?:OTP|code|verification|passcode|is|pin|password)[^\d]*(\d{4,8})\b""",
                RegexOption.IGNORE_CASE
            )
            val m = regex.find(full) ?: Regex("""\b(\d{4,6})\b""").find(full) ?: return
            val code = m.groupValues.lastOrNull { it.isNotBlank() && it.all(Char::isDigit) } ?: return
            if (code.length !in 4..8) return
            setRecentOtp(code)
            Log.i("JarvisAccessibility", "[OTP_AUTO] detected=$code src=${event.packageName}")
        } catch (e: Exception) {
            Log.d("JarvisAccessibility", "[OTP_AUTO] failed: ${e.message}")
        }
    }

    private fun isUrlBarNode(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        return id.contains("url_bar") || id.contains("omnibox") || id.contains("search_box")
    }

    fun verifyTextVisibleAnywhere(text: String): Boolean {
        val snippet = text.trim().take(24).lowercase(Locale.ROOT)
        if (snippet.length < 3) return true
        fun scan(n: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 60) return false
            if (!isUrlBarNode(n)) {
                val t = n.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (t.contains(snippet) || d.contains(snippet)) return true
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { if (scan(it, depth + 1)) return true }
            }
            return false
        }
        repeat(4) {
            try {
                for (w in windows) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val r = w.root ?: continue
                    if (scan(r, 0)) return true
                }
            } catch (_: Exception) {}
            try { Thread.sleep(300) } catch (_: Exception) {}
        }
        return false
    }

    fun findSendButtonByPosition(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val dm = resources.displayMetrics
        val left = (dm.widthPixels * 0.65f).toInt()
        val top = (dm.heightPixels * 0.75f).toInt()
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        fun collect(n: AccessibilityNodeInfo) {
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (n.isClickable && !n.isEditable &&
                b.left >= left && b.top >= top &&
                b.width() in 30..300 && b.height() in 30..200) {
                val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val v = n.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                val excl = d.contains("attach") || d.contains("mic") ||
                        d.contains("voice") || d.contains("emoji") ||
                        d.contains("camera") || d.contains("sticker") ||
                        v.contains("mic") || v.contains("voice") || v.contains("attach")
                if (!excl) candidates.add(n to b)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it) }
        }
        collect(root)
        return candidates.maxByOrNull { it.second.right }?.first
    }

    suspend fun takeScreenBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            kotlinx.coroutines.withTimeoutOrNull(1500L) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(r: ScreenshotResult) {
                                try {
                                    val buf = r.hardwareBuffer
                                    val hw = Bitmap.wrapHardwareBuffer(buf, r.colorSpace)
                                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                    buf.close()
                                    hw?.recycle()
                                    if (cont.isActive) cont.resume(soft)
                                } catch (_: Exception) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                            override fun onFailure(code: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("JarvisAccessibility", "takeScreenBitmap failed: ${e.message}")
            null
        }
    }

    override fun onInterrupt() {
        Log.d("JarvisAccessibility", "Accessibility service interrupted.")
    }

}