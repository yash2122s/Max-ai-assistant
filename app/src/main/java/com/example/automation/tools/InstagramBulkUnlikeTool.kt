package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.ui.SignatureCalculator
import com.example.automation.ui.UiStateWaiter
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class InstagramBulkUnlikeTool : Tool {
    override val name: String = "instagram_bulk_unlike"
    override val supportedActions: Set<String> = setOf("BULK_UNLIKE")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = true,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult =
        withContext(Dispatchers.IO) {
            val service = JarvisAccessibilityService.instance
                ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
            if (service == null && JarvisAccessibilityService.testRootProvider == null) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "SERVICE_UNAVAILABLE",
                    message = "Accessibility Service is not running."
                )
            }

            val root = service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root?.packageName?.toString()?.contains("instagram", true) != true) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "WRONG_APP",
                    message = "Instagram is not the foreground app."
                )
            }

            val elements = ScreenGroundingEngine.scanTargets(root)
            val onLikedList = elements.any { e ->
                val l = e.primaryLabel.lowercase(Locale.ROOT)
                l.contains("posts you've liked") || l.contains("liked posts") ||
                        l.contains("liked reels") || l == "likes"
            }
            if (!onLikedList) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "WRONG_SCREEN",
                    message = "Stopped safely: not on the Instagram liked-items list."
                )
            }

            var processed = 0
            var unchangedScrolls = 0
            val processedIds = mutableSetOf<String>()

            for (iteration in 0 until MAX_ITERATIONS) {
                if (Thread.interrupted() || request.cancellationToken.isCancelled) break
                val currentRoot = service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot() ?: break
                if (currentRoot.packageName?.toString()?.contains("instagram", true) != true) break

                val current = ScreenGroundingEngine.scanTargets(currentRoot)
                val signature = SignatureCalculator.compute(currentRoot)
                val unlikes = current.filter {
                    it.isClickable && Regex("^unlike(?:\\b|\\s)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(it.primaryLabel.trim())
                }.sortedBy { it.bounds?.top ?: 0 }

                val target = unlikes.firstOrNull()
                if (target != null) {
                    val nearby = current.filter { e ->
                        val ty = target.bounds?.centerY() ?: 0
                        val ey = e.bounds?.centerY() ?: 0
                        !e.isEditable && e.bounds != target.bounds &&
                                abs(ey - ty) <= 110 &&
                                e.hasTextOrDesc &&
                                !e.primaryLabel.contains("unlike", true) &&
                                !e.primaryLabel.equals("like", true)
                    }.map { it.primaryLabel.trim() }.filter { it.length >= 3 }.distinct()

                    if (nearby.isEmpty()) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "NO_IDENTITY",
                            message = "Unlike control has no accessible item identity."
                        )
                    }
                    val identity = nearby.joinToString("|").lowercase(Locale.ROOT)
                    if (identity in processedIds) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "REPEAT_ITEM",
                            message = "Refusing to toggle a previously processed item."
                        )
                    }
                    val b = target.bounds ?: continue
                    val dispatched = service?.performClickAtRect(b) ?: false
                    if (!dispatched) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "DISPATCH_FAILED",
                            message = "Could not dispatch the Unlike action."
                        )
                    }
                    val wait = UiStateWaiter.waitForUiState(signature, "instagram", 6_000L)
                    if (!wait.ready) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "UNVERIFIED",
                            message = "Instagram list did not settle after Unlike."
                        )
                    }
                    val after = ScreenGroundingEngine.scanTargets(wait.root)
                    val afterCount = after.count {
                        it.isClickable && Regex("^unlike(?:\\b|\\s)", RegexOption.IGNORE_CASE)
                            .containsMatchIn(it.primaryLabel.trim())
                    }
                    val beforeCount = unlikes.size
                    val verified = afterCount < beforeCount
                    if (!verified) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "UNVERIFIED",
                            message = "Post-action state could not be verified."
                        )
                    }
                    processedIds.add(identity)
                    processed++
                    continue
                }

                // No visible Unlike — scroll
                service?.performSmartScroll("down", "large")
                val wait = UiStateWaiter.waitForUiState(signature, "instagram", 8_000L)
                val afterSig = wait.signature
                if (afterSig == signature) unchangedScrolls++ else unchangedScrolls = 0

                if (unchangedScrolls >= 2) {
                    val after = ScreenGroundingEngine.scanTargets(wait.root)
                    val endMarkers = listOf(
                        "end of list", "no more posts", "no more reels",
                        "you've seen all", "you're all caught up", "nothing to show"
                    )
                    val actualEnd = after.any { e ->
                        endMarkers.any(e.primaryLabel.lowercase(Locale.ROOT)::contains)
                    }
                    if (!actualEnd) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "NO_END_MARKER",
                            message = "Stopped after no-progress. Instagram did not expose an end-of-list marker."
                        )
                    }
                    break
                }
            }

            return@withContext ToolResult(
                success = processed > 0,
                toolName = name,
                message = "Processed $processed Instagram item(s).",
                verified = processed > 0
            )
        }

    companion object {
        private const val MAX_ITERATIONS = 400
    }
}
