package com.example.features.youtube

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay

object YoutubeAutomation {
    private const val TAG = "YoutubeAutomation"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val DEFAULT_TIMEOUT_MS = 8000L
    private const val POLL_INTERVAL_MS = 150L
    private val DURATION_REGEX = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")

    suspend fun searchAndPlay(context: Context, query: String): Boolean {
        Log.d(TAG, "Starting YouTube automation for query: '$query'")
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return false

        // 1. Launch YouTube directly into search results via direct results URL
        val launched = openYoutubeSearchDirect(context, cleanQuery)
        if (!launched) {
            Log.e(TAG, "Failed to launch YouTube app via any intent")
            return false
        }

        // 2. Dynamically poll for YouTube search results UI and click the first playable video
        val startTime = System.currentTimeMillis()
        var clickedVideo = false

        // Initial delay to let the window / activity transition commence
        delay(300L)

        while (System.currentTimeMillis() - startTime < DEFAULT_TIMEOUT_MS) {
            val root = getFreshRoot()
            if (root != null) {
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.equals(YOUTUBE_PACKAGE, ignoreCase = true)) {
                    // Check and dismiss any blocking popups first
                    dismissPopupsIfPresent(root)

                    if (clickFirstVideo(root, context)) {
                        clickedVideo = true
                        Log.d(TAG, "Successfully clicked first YouTube video result after ${System.currentTimeMillis() - startTime}ms!")
                        break
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }

        if (!clickedVideo) {
            val root = getFreshRoot()
            if (root == null) {
                Log.e(TAG, "Failed YouTube automation: Accessibility Service is disconnected or root is null")
            } else {
                Log.e(TAG, "Failed YouTube automation: Opened search for '$cleanQuery', but could not locate/click playable video within ${DEFAULT_TIMEOUT_MS}ms")
            }
            return false
        }

        return true
    }

    /**
     * Dismisses YouTube promotional, update, or informational dialogs that might block the UI.
     */
    fun dismissPopupsIfPresent(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val dismissKeywords = listOf(
            "no thanks", "dismiss", "not now", "cancel", "skip", "close", "got it", "remind me later",
            "maybe later", "skip trial", "don't allow", "deny",
            "వద్దు", "రద్దు చేయి", "తర్వాత", "మూసివేయి",
            "नहीं", "रद्द करें", "बाद में", "खारिज करें", "बंद करें"
        )

        fun searchAndDismiss(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val id = (node.viewIdResourceName ?: "").lowercase()

            val matchesDismiss = dismissKeywords.any { kw ->
                text.contains(kw) || desc.contains(kw)
            } || id.contains("dismiss_button") || id.contains("cancel_button") || id.contains("close_button")

            if (matchesDismiss) {
                if (performClick(node)) {
                    Log.d(TAG, "Dismissed blocking YouTube popup via button: text='$text', desc='$desc', id='$id'")
                    return true
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (searchAndDismiss(child)) return true
            }
            return false
        }

        return searchAndDismiss(root)
    }

    suspend fun pausePlayback(context: Context): Boolean {
        Log.d(TAG, "Executing pausePlayback")
        val root = getFreshRoot()
        if (root != null && root.packageName?.toString().equals(YOUTUBE_PACKAGE, ignoreCase = true)) {
            val pauseButton = findNodeByKeywords(
                root,
                listOf("pause video", "pause", "పాజ్ చేయండి", "వీడియో పాజ్ చేయండి", "వీడియోని పాజ్ చేయండి", "పాజ్", "वीडियो रोकें", "रोकें"),
                listOf("com.google.android.youtube:id/player_control_play_pause", "com.google.android.youtube:id/play_pause_button")
            )
            if (pauseButton != null && performClick(pauseButton)) {
                Log.d(TAG, "Clicked pause button in YouTube player")
                return true
            }

            // If controls are hidden, tap player surface to reveal controls
            val playerSurface = findPlayerSurface(root)
            if (playerSurface != null) {
                performClick(playerSurface)
                delay(250)
                val freshRoot = getFreshRoot()
                val revealedPause = freshRoot?.let {
                    findNodeByKeywords(
                        it,
                        listOf("pause video", "pause", "పాజ్ చేయండి", "వీడియో పాజ్ చేయండి", "వీడియోని పాజ్ చేయండి", "పాజ్", "वीडियो रोकें", "रोकें"),
                        listOf("com.google.android.youtube:id/player_control_play_pause", "com.google.android.youtube:id/play_pause_button")
                    )
                }
                if (revealedPause != null && performClick(revealedPause)) {
                    Log.d(TAG, "Clicked revealed pause button in YouTube player")
                    return true
                }
            }
        }

        // Fallback: Dispatch Media Key
        return sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    suspend fun resumePlayback(context: Context): Boolean {
        Log.d(TAG, "Executing resumePlayback")
        val root = getFreshRoot()
        if (root != null && root.packageName?.toString().equals(YOUTUBE_PACKAGE, ignoreCase = true)) {
            val playButton = findNodeByKeywords(
                root,
                listOf("play video", "play", "ప్లే చేయండి", "వీడియో ప్లే చేయండి", "వీడియోని ప్లే చేయండి", "ప్లే", "वीडियो चलाएं", "चलाएं"),
                listOf("com.google.android.youtube:id/player_control_play_pause", "com.google.android.youtube:id/play_pause_button")
            )
            if (playButton != null && performClick(playButton)) {
                Log.d(TAG, "Clicked play button in YouTube player")
                return true
            }

            // If controls are hidden, tap player surface to reveal controls
            val playerSurface = findPlayerSurface(root)
            if (playerSurface != null) {
                performClick(playerSurface)
                delay(250)
                val freshRoot = getFreshRoot()
                val revealedPlay = freshRoot?.let {
                    findNodeByKeywords(
                        it,
                        listOf("play video", "play", "ప్లే చేయండి", "వీడియో ప్లే చేయండి", "వీడియోని ప్లే చేయండి", "ప్లే", "वीडियो चलाएं", "चलाएं"),
                        listOf("com.google.android.youtube:id/player_control_play_pause", "com.google.android.youtube:id/play_pause_button")
                    )
                }
                if (revealedPlay != null && performClick(revealedPlay)) {
                    Log.d(TAG, "Clicked revealed play button in YouTube player")
                    return true
                }
            }
        }

        // Fallback: Dispatch Media Key
        return sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    suspend fun nextVideo(context: Context): Boolean {
        Log.d(TAG, "Executing nextVideo")
        val root = getFreshRoot()
        if (root != null && root.packageName?.toString().equals(YOUTUBE_PACKAGE, ignoreCase = true)) {
            val nextButton = findNodeByKeywords(
                root,
                listOf("next video", "next", "తదుపరి వీడియో", "తరువాతి వీడియో", "తదుపరి", "अगला वीडियो", "अगला"),
                listOf("com.google.android.youtube:id/next_button", "com.google.android.youtube:id/player_control_next")
            )
            if (nextButton != null && performClick(nextButton)) {
                Log.d(TAG, "Clicked next video button")
                return true
            }

            val playerSurface = findPlayerSurface(root)
            if (playerSurface != null) {
                performClick(playerSurface)
                delay(250)
                val freshRoot = getFreshRoot()
                val revealedNext = freshRoot?.let {
                    findNodeByKeywords(
                        it,
                        listOf("next video", "next", "తదుపరి వీడియో", "తరువాతి వీడియో", "తదుపరి", "अगला वीडियो", "अगला"),
                        listOf("com.google.android.youtube:id/next_button", "com.google.android.youtube:id/player_control_next")
                    )
                }
                if (revealedNext != null && performClick(revealedNext)) {
                    Log.d(TAG, "Clicked revealed next video button")
                    return true
                }
            }
        }
        return sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    suspend fun previousVideo(context: Context): Boolean {
        Log.d(TAG, "Executing previousVideo")
        val root = getFreshRoot()
        if (root != null && root.packageName?.toString().equals(YOUTUBE_PACKAGE, ignoreCase = true)) {
            val prevButton = findNodeByKeywords(
                root,
                listOf("previous video", "previous", "మునుపటి వీడియో", "మునుపటి", "पिछला वीडियो", "पिछला"),
                listOf("com.google.android.youtube:id/previous_button", "com.google.android.youtube:id/player_control_previous")
            )
            if (prevButton != null && performClick(prevButton)) {
                Log.d(TAG, "Clicked previous video button")
                return true
            }

            val playerSurface = findPlayerSurface(root)
            if (playerSurface != null) {
                performClick(playerSurface)
                delay(250)
                val freshRoot = getFreshRoot()
                val revealedPrev = freshRoot?.let {
                    findNodeByKeywords(
                        it,
                        listOf("previous video", "previous", "మునుపటి వీడియో", "మునుపటి", "पिछला वीडियो", "पिछला"),
                        listOf("com.google.android.youtube:id/previous_button", "com.google.android.youtube:id/player_control_previous")
                    )
                }
                if (revealedPrev != null && performClick(revealedPrev)) {
                    Log.d(TAG, "Clicked revealed previous video button")
                    return true
                }
            }
        }
        return sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    suspend fun toggleFullscreen(context: Context): Boolean {
        Log.d(TAG, "Executing toggleFullscreen")
        val root = getFreshRoot() ?: return false
        val fsButton = findNodeByKeywords(
            root,
            listOf("full screen", "fullscreen", "enter full screen", "exit full screen", "పూర్తి స్క్రీన్", "पूर्ण स्क्रीन"),
            listOf("com.google.android.youtube:id/fullscreen_button", "com.google.android.youtube:id/player_fullscreen_button")
        )
        if (fsButton != null && performClick(fsButton)) {
            Log.d(TAG, "Clicked fullscreen button")
            return true
        }
        val playerSurface = findPlayerSurface(root)
        if (playerSurface != null) {
            performClick(playerSurface)
            delay(250)
            val freshRoot = getFreshRoot()
            val revealedFs = freshRoot?.let {
                findNodeByKeywords(
                    it,
                    listOf("full screen", "fullscreen", "enter full screen", "exit full screen", "పూర్తి స్క్రీన్", "पूर्ण स्क्रीन"),
                    listOf("com.google.android.youtube:id/fullscreen_button", "com.google.android.youtube:id/player_fullscreen_button")
                )
            }
            if (revealedFs != null && performClick(revealedFs)) {
                Log.d(TAG, "Clicked revealed fullscreen button")
                return true
            }
        }
        return false
    }

    fun skipAdIfPresent(context: Context): Boolean {
        Log.d(TAG, "Checking and skipping ad if present")
        val root = getFreshRoot() ?: return false
        val skipButton = findNodeByKeywords(
            root,
            listOf("skip ad", "skip ads", "skip", "యాడ్ దాటవేయి", "దాటవేయి", "विज्ञापन छोड़ें"),
            listOf("com.google.android.youtube:id/skip_ad_button", "com.google.android.youtube:id/ad_skip_button", "com.google.android.youtube:id/modern_skip_ad_button")
        )
        if (skipButton != null && performClick(skipButton)) {
            Log.d(TAG, "Successfully clicked skip ad button")
            return true
        }
        return false
    }

    private fun findPlayerSurface(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidateIds = listOf(
            "com.google.android.youtube:id/player_view",
            "com.google.android.youtube:id/movie_player",
            "com.google.android.youtube:id/watch_panel",
            "com.google.android.youtube:id/player_fragment_container",
            "com.google.android.youtube:id/inline_player"
        )
        for (id in candidateIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) return nodes.first()
        }
        fun findSurface(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val cls = node.className?.toString()?.lowercase() ?: ""
            if (cls.contains("surfaceview") || cls.contains("textureview") || cls.contains("playerview")) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findSurface(child)
                if (res != null) return res
            }
            return null
        }
        return findSurface(root)
    }

    private fun findNodeByKeywords(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        viewIds: List<String>
    ): AccessibilityNodeInfo? {
        for (vid in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(vid)
            if (nodes.isNotEmpty()) return nodes.first()
        }
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            if (keywords.any { kw -> desc.contains(kw) || text.contains(kw) }) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child)
                if (res != null) return res
            }
            return null
        }
        return search(root)
    }

    private fun sendMediaKey(context: Context, keyCode: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                audioManager.dispatchMediaKeyEvent(eventDown)
                audioManager.dispatchMediaKeyEvent(eventUp)
                Log.d(TAG, "Dispatched media key $keyCode via AudioManager")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dispatch media key $keyCode: ${e.message}")
            false
        }
    }

    private fun getFreshRoot(): AccessibilityNodeInfo? {
        val root = JarvisAccessibilityService.getActiveRoot()
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)?.rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "Accessibility root is not currently available")
        }
        return root
    }

    private fun openYoutubeSearchDirect(context: Context, query: String): Boolean {
        val encodedQuery = Uri.encode(query)

        // Tier 1 (PREFERRED): Direct HTTPS search-results URL.
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")).apply {
                setPackage(YOUTUBE_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (context.packageManager.resolveActivity(webIntent, 0) != null) {
                context.startActivity(webIntent)
                Log.d(TAG, "Launched YouTube search '$query' via HTTPS results URL ($encodedQuery)")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 HTTPS results URL failed: ${e.message}")
        }

        // Tier 2: Custom YouTube URI Intent (vnd.youtube)
        try {
            val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://results?search_query=$encodedQuery")).apply {
                setPackage(YOUTUBE_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (context.packageManager.resolveActivity(uriIntent, 0) != null) {
                context.startActivity(uriIntent)
                Log.d(TAG, "Launched YouTube via vnd.youtube URI intent")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 vnd.youtube failed: ${e.message}")
        }

        // Tier 3: YouTube Search Action Intent
        try {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(YOUTUBE_PACKAGE)
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (context.packageManager.resolveActivity(searchIntent, 0) != null) {
                context.startActivity(searchIntent)
                Log.d(TAG, "Launched YouTube via ACTION_SEARCH intent")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 3 ACTION_SEARCH failed: ${e.message}")
        }

        // Tier 4: Fallback standard app launcher
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.d(TAG, "Launched YouTube via standard launch intent")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tier 4 standard launch failed: ${e.message}")
        }

        return false
    }

    private fun clickFirstVideo(root: AccessibilityNodeInfo, context: Context): Boolean {
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)

        val searchScope = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/results").firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/pane_fragment_contents").firstOrNull()
            ?: root

        val descNode = findVideoByDescOrText(searchScope, rootBounds)
        if (descNode != null && performClick(descNode)) {
            Log.d(TAG, "Clicked video node by verified description/text: ${descNode.contentDescription ?: descNode.text}")
            return true
        }

        val targetIds = listOf(
            "com.google.android.youtube:id/compact_renderer",
            "com.google.android.youtube:id/video_info",
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/title",
            "com.google.android.youtube:id/inline_player"
        )

        for (resId in targetIds) {
            val nodes = searchScope.findAccessibilityNodeInfosByViewId(resId)
            for (node in nodes) {
                if (!isAdOrChannelOrNav(node, rootBounds) && performClick(node)) {
                    Log.d(TAG, "Clicked video node by ID: $resId")
                    return true
                }
            }
        }

        for (i in 0 until searchScope.childCount) {
            val item = searchScope.getChild(i) ?: continue
            if (!isAdOrChannelOrNav(item, rootBounds)) {
                if (performClick(item)) {
                    Log.d(TAG, "Clicked video card item in results list at index $i")
                    return true
                }
            }
        }

        return false
    }

    private fun isAdOrChannelOrNav(node: AccessibilityNodeInfo?, rootBounds: Rect): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val id = (node.viewIdResourceName ?: "").lowercase()

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val headerThreshold = if (rootBounds.height() > 0) rootBounds.top + (rootBounds.height() * 0.12f) else 300f
        if (bounds.bottom <= headerThreshold && bounds.height() < rootBounds.height() * 0.15f) {
            return true
        }

        if (desc.contains("sponsored") || desc.contains("advertisement") || desc.contains("link opens in new window") ||
            text.equals("ad", ignoreCase = true) || text.contains("sponsored") || text.contains("advertisement") ||
            desc.contains("ప్రాయోజిత") || desc.contains("ప్రకటన") || text.contains("ప్రాయోజిత") || text.contains("ప్రకటన") ||
            desc.contains("प्रायोजित") || desc.contains("विज्ञापन") || text.contains("प्रायोजित") || text.contains("विज्ञापन") ||
            id.contains("ad_") || id.contains("promoted") || id.contains("ads_")) {
            return true
        }

        if (desc.contains("subscribe") || desc.contains("go to channel") || desc.contains("explore menu") ||
            desc.contains("సబ్‌స్క్రైబ్") || desc.contains("ఛానల్") || desc.contains("सदस्यता लें") || desc.contains("चैनल") ||
            text.contains("subscribe") || text.contains("సబ్‌స్క్రైబ్") || text.contains("सदस्यता लें") ||
            id.contains("avatar") || id.contains("channel_header") || id.contains("subscribe") || id.contains("channel_name")) {
            return true
        }

        if (desc.contains("play short") || desc.contains("shorts") || desc.contains("filter") || desc.contains("explore") ||
            desc.contains("షార్ట్‌లు") || desc.contains("ఫిల్టర్") || desc.contains("शॉर्ट्स") || desc.contains("फ़िल्टर") ||
            id.contains("search") || id.contains("filter") || id.contains("chip") ||
            id.contains("tab") || id.contains("pivot") || id.contains("action_bar") ||
            id.contains("toolbar") || id.contains("menu") || id.contains("header") ||
            id.contains("bar") || id.contains("logo") || id.contains("short")) {
            return true
        }
        return false
    }

    private fun findVideoByDescOrText(node: AccessibilityNodeInfo?, rootBounds: Rect): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        if (!isAdOrChannelOrNav(node, rootBounds)) {
            val combined = "$desc $text"
            val hasVideoIndicators = combined.contains("play video") ||
                combined.contains("వీడియోను ప్లే చేయండి") ||
                combined.contains("వీడియో ప్లే చేయండి") ||
                combined.contains("వీడియోని ప్లే చేయండి") ||
                combined.contains("वीडियो चलाएं") ||
                (combined.contains("ago") && (combined.contains("view") || combined.contains("crore") || combined.contains("lakh") || combined.contains("streamed") || combined.contains("views"))) ||
                (combined.contains("క్రితం") && (combined.contains("వీక్షణలు") || combined.contains("వ్యూలు") || combined.contains("లైవ్"))) ||
                (combined.contains("पहले") && (combined.contains("व्यू") || combined.contains("बार देखा गया") || combined.contains("लाइव"))) ||
                ((combined.contains("minute") || combined.contains("second") || combined.contains("hour") || combined.contains("నిమిషాలు") || combined.contains("గంటలు") || combined.contains("मिनट") || combined.contains("घंटे")) &&
                    (combined.contains("view") || combined.contains("ago") || combined.contains("·") || combined.contains("-") || combined.contains("క్రితం") || combined.contains("వీక్షణలు") || combined.contains("पहले"))) ||
                (combined.contains("by ") && (combined.contains("view") || combined.contains("ago") || combined.contains("views"))) ||
                (combined.contains("watch") && (combined.contains("view") || combined.contains("ago"))) ||
                DURATION_REGEX.containsMatchIn(combined)

            if (hasVideoIndicators) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findVideoByDescOrText(child, rootBounds)
            if (result != null) return result
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable && child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            val cls = parent.className?.toString()?.lowercase() ?: ""
            val isOuterScrollContainer = cls.contains("recyclerview") || cls.contains("scrollview") ||
                cls.contains("listview") || cls.contains("viewpager") || cls.contains("decorview")

            if (!isOuterScrollContainer && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            if (isOuterScrollContainer) {
                break
            }
            parent = parent.parent
            depth++
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = android.graphics.Path().apply { moveTo(x, y) }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80)
                val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                if (JarvisAccessibilityService.dispatchGestureAction(gesture)) {
                    return true
                }
            }
        }
        return false
    }
}
