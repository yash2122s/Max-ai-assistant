package com.example.automation.engine.planning

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * High-speed Tier-2 acceleration layer that transforms high-frequency user intents
 * (Maps navigation, Web searches, Media launches) into direct Android Intents.
 *
 * Invariant: Intent is an optimization, NOT the foundation. If an intent is not applicable
 * or if subsequent in-app UI interactions are required, the agent seamlessly transitions
 * to Tier-3 generic UI automation.
 */
object IntentDeepLinkRouter {
    private const val TAG = "IntentDeepLinkRouter"

    /**
     * Checks if the given goal or action can be accelerated via an Android Intent.
     */
    fun canAccelerate(goal: String, targetEntity: String? = null): Boolean {
        val lower = goal.lowercase().trim()
        return lower.contains("navigate") ||
                lower.contains("direction") ||
                lower.contains("route") ||
                (lower.startsWith("open ") && (lower.contains("http://") || lower.contains("https://") || lower.contains(".com") || lower.contains(".org"))) ||
                (lower.contains("youtube") && lower.contains("search"))
    }

    /**
     * Attempts to launch an acceleration intent. Returns true if intent was dispatched successfully.
     */
    fun tryLaunch(context: Context, goal: String, parameters: Map<String, String> = emptyMap()): Boolean {
        val lower = goal.lowercase().trim()

        try {
            // 1. Google Maps Navigation / Directions
            if (lower.contains("navigate") || lower.contains("direction") || lower.contains("maps")) {
                val destination = parameters["destination"]
                    ?: parameters["query"]
                    ?: parameters["location"]
                    ?: extractMapsDestination(goal)

                if (!destination.isNullOrBlank()) {
                    Log.i(TAG, "Launching Maps deep-link for destination: '$destination'")
                    val uri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
                    val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (mapIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(mapIntent)
                        return true
                    } else {
                        // Fallback to web maps intent
                        val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination))
                        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(webIntent)
                        return true
                    }
                }
            }

            // 2. Direct Web URL or Search
            if (lower.contains("http://") || lower.contains("https://") || lower.contains("www.")) {
                val url = extractUrl(goal) ?: parameters["url"]
                if (!url.isNullOrBlank()) {
                    Log.i(TAG, "Launching Browser deep-link for URL: '$url'")
                    val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    return true
                }
            }

            // 3. YouTube Search
            if (lower.contains("youtube") && (lower.contains("search") || lower.contains("play"))) {
                val query = parameters["query"]
                    ?: parameters["search"]
                    ?: extractYoutubeQuery(goal)

                if (!query.isNullOrBlank()) {
                    Log.i(TAG, "Launching YouTube deep-link for search query: '$query'")
                    val ytUri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
                    val ytIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(ytIntent)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Intent deep-link acceleration failed: ${e.message}. Falling back to Tier-3 UI automation.")
        }

        return false
    }

    private fun extractMapsDestination(goal: String): String? {
        val toMatch = Regex("""(?i)(?:to|towards|navigate to|directions to)\s+([^,]+)""").find(goal)
        return toMatch?.groupValues?.get(1)?.trim()
    }

    private fun extractUrl(goal: String): String? {
        val urlMatch = Regex("""(https?://[^\s]+|www\.[^\s]+|[a-zA-Z0-9-]+\.(?:com|org|io|net|edu|gov|in|co|ai)[^\s]*)""").find(goal)
        return urlMatch?.groupValues?.get(1)?.trim()
    }

    private fun extractYoutubeQuery(goal: String): String? {
        val ytMatch = Regex("""(?i)(?:search|play|find)\s+(.+?)(?:\s+on\s+youtube|\s+in\s+youtube|$)""").find(goal)
        return ytMatch?.groupValues?.get(1)?.replace(Regex("(?i)youtube"), "")?.trim()
    }
}
