package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.ui.SignatureCalculator
import com.example.automation.ui.UiStateWaiter
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

class SpotifySearchTool : Tool {
    override val name: String = "spotify_smart_search"
    override val supportedActions: Set<String> = setOf("PLAY_SPOTIFY")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = true,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val query = request.arguments.get("query")?.asString
            ?: request.arguments.get("text")?.asString
            ?: request.arguments.get("song")?.asString
        return !query.isNullOrBlank()
    }

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
            if (root?.packageName?.toString()?.contains("spotify", true) != true) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "WRONG_APP",
                    message = "Spotify is not the foreground app."
                )
            }

            val query = request.arguments.get("query")?.asString
                ?: request.arguments.get("text")?.asString
                ?: request.arguments.get("song")?.asString
                ?: ""

            val cleanQuery = cleanSpotifyQuery(query)
            if (cleanQuery.length < 2) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "NO_QUERY",
                    message = "Could not identify a song title in the request."
                )
            }

            // Step 1: Scan for a single editable search field
            var elements = ScreenGroundingEngine.scanTargets(root)
            if (elements.none { it.isEditable }) {
                val searchButtons = elements.filter {
                    it.isClickable && !it.isEditable &&
                            it.primaryLabel.contains("search", true)
                }
                if (searchButtons.size != 1) {
                    return@withContext ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "AMBIGUOUS_SEARCH",
                        message = "Spotify Search is not uniquely visible."
                    )
                }
                val b = searchButtons.single().bounds ?: return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "NO_BOUNDS",
                    message = "Search button has no bounds."
                )
                service?.performClickAtRect(b)
                delay(500)
                elements = ScreenGroundingEngine.scanTargets(service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot())
            }

            val editable = elements.filter { it.isEditable }
            if (editable.size != 1) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "AMBIGUOUS_INPUT",
                    message = "Spotify search field is ambiguous."
                )
            }

            // Step 2: Type the query
            val inputBounds = editable.single().bounds ?: return@withContext ToolResult(
                success = false,
                toolName = name,
                errorCode = "NO_BOUNDS",
                message = "Search field has no bounds."
            )
            service?.smartTypeText(cleanQuery, inputBounds, submit = true)

            // Step 3: Wait for results
            val preSig = SignatureCalculator.compute(service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot())
            val wait1 = UiStateWaiter.waitForUiState(preSig, "spotify", 8_000L)
            if (!wait1.ready) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "RESULTS_TIMEOUT",
                    message = "Spotify search results did not stabilize."
                )
            }

            // Step 4: Find a unique matching track
            val results = ScreenGroundingEngine.scanTargets(wait1.root)
            val tokens = cleanQuery.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 2 }
            val candidates = results.filter {
                it.hasTextOrDesc && !it.isEditable && it.isClickable &&
                        tokens.isNotEmpty() && tokens.all { t ->
                            it.primaryLabel.lowercase(Locale.ROOT).contains(t)
                        } &&
                        !it.primaryLabel.contains("search results", true)
            }
            val unique = candidates.distinctBy { it.primaryLabel.lowercase(Locale.ROOT) }
            if (unique.size != 1) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "NO_UNIQUE_MATCH",
                    message = "Could not find a unique matching Spotify result for '$cleanQuery'."
                )
            }

            // Step 5: Tap the match
            val match = unique.single()
            val bounds = match.bounds ?: return@withContext ToolResult(
                success = false,
                toolName = name,
                errorCode = "NO_BOUNDS",
                message = "Match has no bounds."
            )
            val clicked = service?.performClickAtRect(bounds) ?: false
            if (!clicked) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "CLICK_FAILED",
                    message = "Could not tap the Spotify result."
                )
            }

            // Step 6: Verify playback
            delay(1_500)
            val playback = ScreenGroundingEngine.scanTargets(service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot())
            val requestedTokens = cleanQuery.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
            val trackVisible = playback.any {
                requestedTokens.all { t -> it.primaryLabel.lowercase(Locale.ROOT).contains(t) }
            }
            val isPlaying = playback.any {
                val l = it.primaryLabel.lowercase(Locale.ROOT)
                l == "pause" || l.contains("pause playback") ||
                        l.contains("now playing") || l.contains("currently playing")
            }
            val verified = trackVisible && isPlaying

            ToolResult(
                success = verified,
                toolName = name,
                message = if (verified)
                    "Verified Spotify is playing '${match.primaryLabel}'."
                else
                    "Selected '${match.primaryLabel}', but playback could not be verified.",
                verified = verified
            )
        }

    fun cleanSpotifyQuery(goal: String): String {
        return goal
            .replace(Regex(
                "(?i)\\b(jarvis|max|spotify|please|favorite|find|search|look for|for|play|song|track|music|playlist|" +
                "bajao|chalao|karo|kar do|do|dhundo|gaana|gana|meri|mera|par|mein|ko|the|a|an|on|in|my)\\b"
            ), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', '!', '?', '"', '\'')
    }
}
