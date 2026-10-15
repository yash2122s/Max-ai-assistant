package com.example.automation.tools

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.ExecutionRequest
import com.example.automation.resolver.UiTargetResolver
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.ui.ScreenSummaryFormatter
import com.example.automation.ui.SignatureCalculator
import com.example.automation.ui.UiStateWaiter
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.ClickEscalationResult
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class ClickUiTool : Tool {

    private val TAG = "ClickUiTool"

    override val name: String = "click_element"
    override val supportedActions: Set<String> = setOf(
        "CLICK_TEXT", "CLICK_ELEMENT", "CLICK_UI", "CLICK_INDEX", "TAP"
    )
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    // JARVIS migration — debounce & anti-repeat state
    private var lastClickTimestamp = 0L
    private var lastClickPhysicalX = -1f
    private var lastClickPhysicalY = -1f
    private var lastUnverifiedClickTarget = ""
    private var lastUnverifiedClickSignature: String? = null

    override fun validate(request: ExecutionRequest): Boolean {
        val args = request.arguments
        val index = args.get("index")?.asInt ?: args.get("element_index")?.asInt
        val text = args.get("text")?.asString
            ?: args.get("target")?.asString
            ?: args.get("query")?.asString
            ?: ""
        val desc = args.get("description")?.asString
            ?: args.get("desc")?.asString
            ?: ""
        val resId = args.get("resourceId")?.asString
            ?: args.get("id")?.asString
            ?: ""
        return (index != null && index > 0) || text.isNotBlank() ||
                desc.isNotBlank() || resId.isNotBlank()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult =
        withContext(Dispatchers.IO) {

            val args = request.arguments
            val index = args.get("index")?.asInt ?: args.get("element_index")?.asInt
            val targetName = args.get("text")?.asString
                ?: args.get("target")?.asString
                ?: args.get("query")?.asString
                ?: ""
            val targetDesc = args.get("description")?.asString
                ?: args.get("desc")?.asString
                ?: ""
            val resId = args.get("resourceId")?.asString
                ?: args.get("id")?.asString
                ?: ""
            val isToggle = args.get("is_toggle")?.asBoolean ?: false
            val taskGoal = args.get("task_goal")?.asString
                ?: args.get("goal")?.asString
                ?: ""

            val service = JarvisAccessibilityService.instance
                ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
                ?: return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "SERVICE_UNAVAILABLE",
                    message = "Accessibility service is not active"
                )

            // ─── JARVIS MIGRATION #1: Sensitive-action gate ──────────────────
            if (isSensitiveTarget(targetName, targetDesc)) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "SENSITIVE_ACTION",
                    message = "Sensitive action detected ('$targetName'). " +
                            "Please ask the user for explicit confirmation before proceeding.",
                    requiresUserConfirmation = true,
                    verified = false
                )
            }

            // ─── JARVIS MIGRATION #2: Debounce ────────────────────────────────
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastClickTimestamp
            if (timeSinceLast < DEBOUNCE_MS) {
                delay(DEBOUNCE_MS - timeSinceLast)
            }

            // ─── JARVIS MIGRATION #3: Sensitive app & flow guards ──────────────
            val preRoot = service.rootInActiveWindow
            val prePkg = preRoot?.packageName?.toString().orEmpty()
            val preSignature = SignatureCalculator.compute(preRoot)

            val guardResult = evaluateAppGuards(
                service, preRoot, prePkg, targetName, targetDesc, taskGoal
            )
            if (guardResult != null) return@withContext guardResult

            // ─── JARVIS MIGRATION #4: Search-bar reroute ──────────────────────
            if (isBrowserSearchScreen(service) &&
                (targetName.equals("search", true) ||
                 targetName.contains("search bar", true) ||
                 targetName.contains("url_bar", true))
            ) {
                val searchRoot = service.rootInActiveWindow
                val elements = ScreenGroundingEngine.scanTargets(searchRoot)
                val resultLinks = elements.filter {
                    it.isClickable && !it.isEditable &&
                            (it.bounds?.top ?: 0) > 250 &&
                            !it.text.isNullOrBlank()
                }
                if (resultLinks.isNotEmpty()) {
                    val best = resultLinks.first()
                    val bounds = best.bounds
                    if (bounds != null) {
                        val clickOk = service.performClickAtRect(bounds)
                        val afterRoot = service.rootInActiveWindow
                        return@withContext ToolResult(
                            success = clickOk,
                            toolName = name,
                            message = "Navigating to website: automatically clicked search result link '${best.primaryLabel}'.",
                            verified = clickOk,
                            currentStateSummary = ScreenSummaryFormatter.format(
                                ScreenGroundingEngine.scanTargets(afterRoot),
                                afterRoot
                            )
                        )
                    }
                }
            }

            // ─── Direct indexed click if provided ─────────────────────────────
            if (index != null && index > 0) {
                // JARVIS INDEX SAFETY GUARD: Check if the indexed element still matches requested targetName
                val candidateNode = service.resolveIndexedNode(index)
                val targetQuery = targetName.trim().lowercase(Locale.ROOT)
                val label = (candidateNode?.text?.toString() ?: candidateNode?.contentDescription?.toString() ?: "").trim().lowercase(Locale.ROOT)
                val isLabelMismatch = targetQuery.length >= 3 && label.isNotBlank() && !label.contains(targetQuery) && !targetQuery.contains(label)

                if (isLabelMismatch) {
                    Log.w(TAG, "Indexed element #$index ('$label') does not match requested label '$targetName'. Screen likely updated; falling back to label resolution.")
                } else {
                    Log.d(TAG, "Executing indexed click for index=[$index]")
                    val (indexSuccess, indexMessage) = service.clickIndexedElement(index)
                    if (!indexSuccess) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "INDEX_CLICK_FAILED",
                            message = indexMessage,
                            verified = false
                        )
                    }
                    lastClickTimestamp = System.currentTimeMillis()
                    val uiWait = UiStateWaiter.waitForUiState(preSignature, prePkg, 6_000L)
                    val postRoot = uiWait.root
                    val elementsAfter = ScreenGroundingEngine.scanTargets(postRoot)
                    return@withContext ToolResult(
                        success = true,
                        toolName = name,
                        message = indexMessage,
                        verified = uiWait.changedFromPrevious,
                        currentStateSummary = ScreenSummaryFormatter.format(elementsAfter, postRoot)
                    )
                }
            }

            // ─── Resolve the target ───────────────────────────────────────────
            val query = targetName.ifEmpty { targetDesc }.ifEmpty { resId }
            val resolveStart = System.currentTimeMillis()
            var lastMessage = "Target '$query' not found on current screen"
            var clickSuccess = false
            var isAmbiguous = false
            var matchedBounds: Rect? = null
            var matchedLabel = query
            var initialCheckedState: Boolean? = null
            var wasCheckable = false

            while (System.currentTimeMillis() - resolveStart < RESOLVE_TIMEOUT_MS) {
                val rootNode = service.rootInActiveWindow ?: run {
                    delay(200L); continue
                }

                val resolverResult = UiTargetResolver.resolve(
                    query = query,
                    explicitResourceId = resId.ifEmpty { null },
                    explicitContentDescription = targetDesc.ifEmpty { null },
                    requireClickable = true,
                    rootNode = rootNode
                )

                if (resolverResult.success &&
                    resolverResult.confidence >= UiTargetResolver.SAFE_CONFIDENCE_THRESHOLD &&
                    resolverResult.target != null &&
                    resolverResult.matchedNode != null
                ) {
                    val target = resolverResult.target
                    matchedBounds = target.bounds
                    matchedLabel = target.primaryLabel

                    // ─── JARVIS MIGRATION #5: Anti-repeat-tap guard ──────────
                    val normalizedTarget = targetName.lowercase(Locale.ROOT).trim()
                    if (normalizedTarget.isNotBlank() &&
                        normalizedTarget == lastUnverifiedClickTarget &&
                        lastUnverifiedClickSignature != null &&
                        lastUnverifiedClickSignature == preSignature
                    ) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "REPEAT_UNVERIFIED",
                            message = "Refusing to repeat '$targetName': the previous tap " +
                                    "did not change this screen. No second tap was made.",
                            verified = false,
                            currentStateSummary = ScreenSummaryFormatter.format(
                                ScreenGroundingEngine.scanTargets(rootNode), rootNode
                            )
                        )
                    }

                    // ─── JARVIS MIGRATION #6: Duplicate coordinate suppression ─
                    val cx = target.center.x
                    val cy = target.center.y
                    if (abs(cx - lastClickPhysicalX) < 30f &&
                        abs(cy - lastClickPhysicalY) < 30f &&
                        (now - lastClickTimestamp) < DUPLICATE_COORD_MS
                    ) {
                        return@withContext ToolResult(
                            success = true,
                            toolName = name,
                            message = "Already tapped at this location. Waiting for UI update.",
                            verified = null
                        )
                    }
                    lastClickTimestamp = System.currentTimeMillis()
                    lastClickPhysicalX = cx
                    lastClickPhysicalY = cy

                    // Publish HUD grounded event
                    try {
                        com.example.automation.event.AutomationEventBus.publish(
                            com.example.automation.event.AutomationEvent.TargetGrounded(
                                executionId = request.executionId,
                                bounds = matchedBounds ?: Rect(),
                                label = matchedLabel,
                                actionType = "CLICK"
                            )
                        )
                    } catch (_: Throwable) {}

                    // ─── Dispatch the tap ─────────────────────────────────────
                    initialCheckedState = target.isChecked
                    wasCheckable = resolverResult.matchedNode.isCheckable
                    val escalation = com.example.automation.engine.SerializedActionExecutor.execute("click:$matchedLabel") {
                        service.performClickWithEscalation(resolverResult.matchedNode)
                    }
                    clickSuccess = escalation == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                            escalation == ClickEscalationResult.GESTURE_CONFIRMED ||
                            escalation == ClickEscalationResult.UNCONFIRMED
                    lastMessage = if (escalation != null) "Clicked '$matchedLabel' ($escalation)" else "Action cancelled or locked"
                    break
                }

                if (resolverResult.candidateCount > 1 &&
                    resolverResult.confidence < UiTargetResolver.SAFE_CONFIDENCE_THRESHOLD
                ) {
                    isAmbiguous = true
                    lastMessage = resolverResult.reason
                    break
                }

                lastMessage = resolverResult.reason
                delay(200L)
            }

            if (!clickSuccess) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = if (isAmbiguous) "AMBIGUOUS_TARGET" else "CLICK_FAILED",
                    message = lastMessage,
                    verified = false
                )
            }

            // ─── JARVIS MIGRATION #7: Closed-loop verification ────────────────
            val maxWatchMs = if (isWebOrLink(matchedLabel)) 12_000L else 8_000L
            val uiWait = UiStateWaiter.waitForUiState(
                previousSignature = preSignature,
                expectedPackage = prePkg,
                timeoutMs = maxWatchMs
            )
            val postRoot = uiWait.root
            val postSignature = uiWait.signature
            val stateChanged = postSignature != null && postSignature != preSignature

            val elementsAfter = ScreenGroundingEngine.scanTargets(postRoot)

            // ─── JARVIS MIGRATION #8: Toggle state verification ──────────────
            var toggleVerified = false
            val detectedToggle = isToggle ||
                wasCheckable ||
                elementsAfter.firstOrNull { it.bounds == matchedBounds }?.let { it.isChecked != null || it.role == "switch" || it.role == "checkbox" } == true

            if (detectedToggle) {
                val updatedTarget = elementsAfter.firstOrNull {
                    it.bounds == matchedBounds || it.primaryLabel == matchedLabel
                }
                val nowChecked = updatedTarget?.isChecked
                if (initialCheckedState != null && nowChecked != null) {
                    toggleVerified = nowChecked != initialCheckedState
                }
            }

            // ─── JARVIS MIGRATION #9: Playback verification ─────────────────
            val playbackVerified = verifyPlaybackIfExpected(
                taskGoal, matchedLabel, elementsAfter
            )

            val intendedResultVerified = toggleVerified || playbackVerified

            // ─── JARVIS MIGRATION #10: Anti-repeat tracking ──────────────────
            if (!stateChanged && !detectedToggle) {
                lastUnverifiedClickTarget = targetName.lowercase(Locale.ROOT).trim()
                lastUnverifiedClickSignature = postSignature
            } else {
                lastUnverifiedClickTarget = ""
                lastUnverifiedClickSignature = null
            }

            // ─── JARVIS MIGRATION #11: Prompt-shaped response ───────────────
            val returnMessage = when {
                toggleVerified ->
                    "Clicked '$matchedLabel' and verified its checked state changed."
                playbackVerified ->
                    "Clicked '$matchedLabel'; the requested track is visible and " +
                            "the player exposes an active playback state."
                stateChanged ->
                    "Clicked '$matchedLabel' and observed a UI change."
                else ->
                    "Clicked '$matchedLabel' successfully."
            }

            ToolResult(
                success = clickSuccess,
                toolName = name,
                message = returnMessage,
                verified = intendedResultVerified || stateChanged,
                currentStateSummary = ScreenSummaryFormatter.format(elementsAfter, postRoot),
                metadata = JSONObject().apply {
                    put("status", if (intendedResultVerified || stateChanged) "VERIFIED" else "UNVERIFIED")
                    put("stateChanged", stateChanged)
                    put("toggleVerified", toggleVerified)
                }
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers (JARVIS migration)
    // ────────────────────────────────────────────────────────────────────────

    fun isSensitiveTarget(target: String, desc: String): Boolean {
        val combined = "$target $desc".lowercase(Locale.ROOT)
        return combined.contains("pay") || combined.contains("payment") ||
                combined.contains("delete all") || combined.contains("factory reset") ||
                combined.contains("format") || combined.contains("purchase") ||
                combined.contains("transfer money") || combined.contains("send money")
    }

    private fun isWebOrLink(label: String): Boolean {
        val l = label.lowercase(Locale.ROOT)
        return l.contains("http") || l.contains(".com") || l.contains("www.") ||
                l.contains("search") || l.contains("flow")
    }

    private fun verifyPlaybackIfExpected(
        taskGoal: String,
        targetLabel: String,
        elementsAfter: List<com.example.automation.models.UiTarget>
    ): Boolean {
        val goal = taskGoal.lowercase(Locale.ROOT)
        val playbackExpected = listOf("play", "play it", "play karo", "bajao").any(goal::contains)
        if (!playbackExpected) return false

        val requestedTokens = goal.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .filterNot { it in setOf(
                "please", "spotify", "youtube", "playlist", "meri", "mera",
                "par", "mein", "song", "track", "find", "search", "play",
                "karo", "kar", "do", "the", "for", "and", "with"
            ) }
        if (requestedTokens.isEmpty()) return false

        val targetLabelLower = targetLabel.lowercase(Locale.ROOT)
        val trackVisible = requestedTokens.all { targetLabelLower.contains(it) }
        val controlsVisible = elementsAfter.any {
            val l = it.primaryLabel.lowercase(Locale.ROOT)
            l.contains("pause") || l.contains("now playing") || l.contains("currently playing")
        }
        return trackVisible && controlsVisible
    }

    internal fun evaluateAppGuards(
        service: JarvisAccessibilityService,
        root: AccessibilityNodeInfo?,
        pkg: String,
        targetName: String,
        targetDesc: String,
        taskGoal: String
    ): ToolResult? {
        val pkgLower = pkg.lowercase(Locale.ROOT)
        val targetLower = targetName.lowercase(Locale.ROOT).trim()

        // Gmail Send guard
        if (pkgLower.contains("com.google.android.gm") &&
            (targetLower == "send" || targetLower == "send email" || targetLower == "send message")
        ) {
            val goal = taskGoal.lowercase(Locale.ROOT)
            val explicitEmailRequest = (goal.contains("email") || goal.contains("gmail")) &&
                    (goal.contains("send") || goal.contains("email this"))
            if (!explicitEmailRequest) {
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "GMAIL_SEND_BLOCKED",
                    message = "Blocked Gmail Send: the active user request did not " +
                            "explicitly ask to send an email.",
                    verified = false
                )
            }
        }

        // Google Flow settings-chip block
        val flowSettingsBlocklist = setOf(
            "16:9", "9:16", "360p", "480p", "720p", "1080p",
            "4s", "6s", "8s", "10s", "12s", "15s",
            "x1", "x2", "x3", "x4", "x5",
            "frames", "ingredients", "omni 1.1 flash", "omni", "veo 2"
        )
        if (flowSettingsBlocklist.contains(targetLower) ||
            targetLower.matches(Regex("\\d+[sp]|x\\d+|\\d+[sS]ec?"))
        ) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "FLOW_SETTINGS_BLOCKED",
                message = "BLOCKED: '$targetName' is a Google Flow video settings control. " +
                        "Do NOT click these. Type the prompt into the prompt field instead.",
                verified = false
            )
        }

        return null
    }

    private fun isBrowserSearchScreen(service: JarvisAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()
        if (!pkg.contains("chrome", true) && !pkg.contains("browser", true)) return false
        val elements = ScreenGroundingEngine.scanTargets(root)
        return ScreenSummaryFormatter.isGoogleSearchResults(elements)
    }

    companion object {
        private const val DEBOUNCE_MS = 280L
        private const val DUPLICATE_COORD_MS = 1800L
        private const val RESOLVE_TIMEOUT_MS = 2500L
    }
}
