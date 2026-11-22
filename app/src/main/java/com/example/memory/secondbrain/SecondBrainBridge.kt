package com.example.memory.secondbrain

import android.content.Context
import android.util.Log
import com.example.data.preferences.SettingsManager
import com.example.domain.policy.AssistantLanguage
import com.example.memory.data.MemoryRepository
import com.example.memory.data.PermanentMemory
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SecondBrainMemoryItem(
    val id: String,
    val path: String,
    val title: String,
    val body: String,
    val plain: String,
    val category: String,
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    val words: Int = 0,
    val lastModified: String = ""
)

data class SecondBrainQueryResult(
    val found: Boolean,
    val matchedMemory: SecondBrainMemoryItem? = null,
    val responseText: String,
    val relatedLinks: List<String> = emptyList()
)

object SecondBrainBridge {

    private const val TAG = "SecondBrainBridge"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val bridgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isConfigured(context: Context): Boolean {
        val settings = SettingsManager.getInstance(context)
        return settings.isSecondBrainEnabled && settings.secondBrainUrl.isNotBlank()
    }

    fun getBaseUrl(context: Context): String {
        val settings = SettingsManager.getInstance(context)
        val url = settings.secondBrainUrl.trim()
        return if (url.endsWith("/")) url.substring(0, url.length - 1) else url
    }

    suspend fun testConnection(context: Context, customUrl: String? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val baseUrl = customUrl?.trim()?.trimEnd('/') ?: getBaseUrl(context)
        if (baseUrl.isBlank()) {
            return@withContext Pair(false, "Server URL is empty")
        }

        try {
            val request = Request.Builder()
                .url("$baseUrl/api/memories")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Pair(false, "Server returned HTTP ${response.code}")
                }
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val count = json.optInt("count", 0)
                Pair(true, "Connected! Found $count memories on Second Brain.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Test connection failed: ${e.message}")
            Pair(false, "Cannot connect: ${e.message ?: "Server unreachable"}")
        }
    }

    suspend fun fetchAllMemories(context: Context): List<SecondBrainMemoryItem> = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext emptyList()

        val baseUrl = getBaseUrl(context)
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/memories")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val arr = json.optJSONArray("memories") ?: return@withContext emptyList()

                val result = mutableListOf<SecondBrainMemoryItem>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val tagsArr = item.optJSONArray("tags") ?: JSONArray()
                    val tagsList = mutableListOf<String>()
                    for (t in 0 until tagsArr.length()) tagsList.add(tagsArr.getString(t))

                    val linksArr = item.optJSONArray("links") ?: JSONArray()
                    val linksList = mutableListOf<String>()
                    for (l in 0 until linksArr.length()) linksList.add(linksArr.getString(l))

                    val aliasesArr = item.optJSONArray("aliases") ?: JSONArray()
                    val aliasesList = mutableListOf<String>()
                    for (a in 0 until aliasesArr.length()) aliasesList.add(aliasesArr.getString(a))

                    result.add(
                        SecondBrainMemoryItem(
                            id = item.optString("id", ""),
                            path = item.optString("path", ""),
                            title = item.optString("title", ""),
                            body = item.optString("body", ""),
                            plain = item.optString("plain", ""),
                            category = item.optString("category", "general"),
                            summary = item.optString("summary", ""),
                            tags = tagsList,
                            aliases = aliasesList,
                            links = linksList,
                            words = item.optInt("words", 0),
                            lastModified = item.optString("lastModified", "")
                        )
                    )
                }
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Second Brain memories: ${e.message}")
            emptyList()
        }
    }

    fun syncMemoryAsync(context: Context, memory: PermanentMemory) {
        if (!isConfigured(context)) return
        bridgeScope.launch {
            try {
                syncMemory(context, memory)
            } catch (e: Exception) {
                Log.w(TAG, "Async sync to Second Brain non-fatal: ${e.message}")
            }
        }
    }

    suspend fun syncMemory(context: Context, memory: PermanentMemory): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext false

        val baseUrl = getBaseUrl(context)
        try {
            AssistantEventBus.emit(AssistantEvent.ToolProgress("SecondBrain", "🧠 Connecting memory to Second Brain..."))

            val titleLower = memory.title.lowercase()
            val contentLower = memory.content.lowercase()
            val catLower = memory.category.lowercase()

            val category = when {
                catLower.contains("people") || catLower.contains("person") || catLower.contains("family") ||
                titleLower.contains("wife") || titleLower.contains("husband") || titleLower.contains("lover") ||
                titleLower.contains("friend") || contentLower.contains("wife") || contentLower.contains("lover") ||
                contentLower.contains("sarika") -> "people"

                catLower.contains("pref") || titleLower.contains("favourite") || titleLower.contains("food") ||
                titleLower.contains("routine") || contentLower.contains("preference") -> "profile"

                catLower.contains("proj") || titleLower.contains("project") || titleLower.contains("app") ||
                contentLower.contains("project") || contentLower.contains("code") -> "projects"

                else -> "personal"
            }

            val sanitizedTitle = memory.title.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifBlank { "Memory-${System.currentTimeMillis()}" }
            val filename = sanitizedTitle.lowercase().replace(" ", "-") + ".md"

            // Smart Auto-Linking: Check existing notes and find cross-references
            val existingMemories = fetchAllMemories(context)
            val detectedLinks = mutableSetOf<String>()

            // If it's a person or relationship note (e.g. Sarika, friend, wife), always link to Yash's Profile
            val isRelationship = category == "people" || contentLower.contains("wife") || contentLower.contains("lover") ||
                                 contentLower.contains("husband") || contentLower.contains("friend") || contentLower.contains("sarika")
            if (isRelationship) {
                detectedLinks.add("Yash's Profile")
            }

            // Cross-check with existing note titles
            for (item in existingMemories) {
                if (item.title.isNotBlank() && item.title.length >= 3) {
                    if (contentLower.contains(item.title.lowercase()) || titleLower.contains(item.title.lowercase())) {
                        detectedLinks.add(item.title)
                    }
                }
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateStr = dateFormat.format(Date(memory.createdAt))

            val tagsList = mutableListOf<String>()
            if (memory.tags.isNotBlank()) {
                tagsList.addAll(memory.tags.split(",").map { it.trim().removePrefix("#") }.filter { it.isNotBlank() })
            }
            if (category == "people" && !tagsList.contains("people")) tagsList.add("people")
            if (isRelationship && !tagsList.contains("relationship")) tagsList.add("relationship")

            val connectionsMd = if (detectedLinks.isNotEmpty()) {
                "\n\n## Related Connections\n" + detectedLinks.joinToString("\n") { "- [[$it]]" }
            } else {
                ""
            }

            val markdownContent = buildString {
                append("---\n")
                append("title: \"${memory.title}\"\n")
                append("category: \"$category\"\n")
                if (tagsList.isNotEmpty()) {
                    append("tags: [${tagsList.joinToString(", ") { "\"$it\"" }}]\n")
                }
                append("created: \"$dateStr\"\n")
                append("---\n\n")
                append("# ${memory.title}\n\n")
                append(memory.content.trim())
                append(connectionsMd)
                append("\n")
            }

            // Save new memory
            val payload = JSONObject().apply {
                put("category", category)
                put("filename", filename)
                put("content", markdownContent)
            }

            val request = Request.Builder()
                .url("$baseUrl/api/save")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val saveSuccess = httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }

            if (!saveSuccess) return@withContext false

            // Auto-link back into profile/name.md if this is Sarika or a relationship
            if (isRelationship) {
                val profileMem = existingMemories.firstOrNull { it.path.contains("profile/name.md", ignoreCase = true) }
                if (profileMem != null && !profileMem.body.contains("[[${memory.title}]]", ignoreCase = true)) {
                    val updatedBody = if (profileMem.body.contains("## Connections", ignoreCase = true)) {
                        profileMem.body.replace("## Connections", "## Connections\n- [[${memory.title}]]")
                    } else {
                        profileMem.body.trim() + "\n\n## Connections\n- [[${memory.title}]]\n"
                    }

                    val profilePayload = JSONObject().apply {
                        put("category", "profile")
                        put("filename", "name.md")
                        put("content", updatedBody)
                    }

                    val updateProfileReq = Request.Builder()
                        .url("$baseUrl/api/save")
                        .post(profilePayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    httpClient.newCall(updateProfileReq).execute().close()
                    AssistantEventBus.emit(
                        AssistantEvent.ToolProgress("SecondBrain", "🔗 Bidirectional link created: [[${memory.title}]] ⟷ [[Yash's Profile]]")
                    )
                }
            }

            AssistantEventBus.emit(
                AssistantEvent.ToolProgress("SecondBrain", "✓ Synced: $category/$filename")
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sync to Second Brain failed: ${e.message}")
            false
        }
    }

    suspend fun queryMemoriesWithVisualFeedback(
        context: Context,
        userQuery: String,
        targetLang: AssistantLanguage = AssistantLanguage.TINGLISH
    ): SecondBrainQueryResult? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext null

        val memories = fetchAllMemories(context)
        if (memories.isEmpty()) return@withContext null

        val lowerQuery = userQuery.trim().lowercase()
        val stopWords = setOf("what", "is", "my", "the", "a", "an", "who", "where", "how", "naa", "na", "enti", "cheppu", "gurinchi", "em", "details")
        val queryTokens = lowerQuery.replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it !in stopWords && it.length >= 2 }

        var bestMatch: SecondBrainMemoryItem? = null
        var highestScore = 0

        // Live Visual HUD Scanning across Second Brain Markdown files
        for (mem in memories) {
            // Emits real-time visual progress on user's HUD
            AssistantEventBus.emit(AssistantEvent.ToolProgress("SecondBrain", "🔍 Reading: ${mem.path}"))
            delay(110) // Smooth visual animation feedback

            val titleLower = mem.title.lowercase()
            val plainLower = mem.plain.lowercase()
            val tagsLower = mem.tags.joinToString(" ").lowercase()
            val linksLower = mem.links.joinToString(" ").lowercase()

            var score = 0

            // Specific person search e.g. "sarika", "wife", "lover"
            if (lowerQuery.contains("sarika") && (titleLower.contains("sarika") || plainLower.contains("sarika"))) {
                score += 20
            }
            if ((lowerQuery.contains("wife") || lowerQuery.contains("lover") || lowerQuery.contains("bharya")) &&
                (titleLower.contains("sarika") || plainLower.contains("wife") || plainLower.contains("lover"))) {
                score += 15
            }

            // Profile / Identity search
            if ((lowerQuery.contains("name") || lowerQuery.contains("peru") || lowerQuery.contains("who am i")) &&
                (mem.path.contains("name.md") || titleLower.contains("profile") || titleLower.contains("yash"))) {
                score += 18
            }

            // General token scoring
            for (token in queryTokens) {
                if (titleLower.contains(token)) score += 6
                if (tagsLower.contains(token)) score += 4
                if (plainLower.contains(token)) score += 2
                if (linksLower.contains(token)) score += 3
            }

            if (score > highestScore) {
                highestScore = score
                bestMatch = mem
            }
        }

        if (bestMatch != null && highestScore >= 3) {
            AssistantEventBus.emit(
                AssistantEvent.ToolProgress("SecondBrain", "✓ Found connection in: ${bestMatch.path}")
            )

            val linksJoined = if (bestMatch.links.isNotEmpty()) {
                " [Links: " + bestMatch.links.joinToString(", ") { "[[$it]]" } + "]"
            } else ""

            val response = when (targetLang) {
                AssistantLanguage.TELUGU -> "Second Brain నుండి సమాచారం: ${bestMatch.title} — ${bestMatch.plain}$linksJoined"
                AssistantLanguage.ENGLISH -> "From Second Brain: ${bestMatch.title} — ${bestMatch.plain}$linksJoined"
                AssistantLanguage.HINDI -> "Second Brain से जानकारी: ${bestMatch.title} — ${bestMatch.plain}$linksJoined"
                AssistantLanguage.TINGLISH -> "Second Brain nundi: ${bestMatch.title} — ${bestMatch.plain}$linksJoined"
            }

            return@withContext SecondBrainQueryResult(
                found = true,
                matchedMemory = bestMatch,
                responseText = response,
                relatedLinks = bestMatch.links
            )
        }

        null
    }

    suspend fun syncAllLocalMemories(context: Context): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            return@withContext Pair(0, "Second Brain is disabled or server URL is not configured.")
        }

        try {
            val repository = MemoryRepository.getInstance(context)
            val allLocal = repository.getAllEnabled()
            if (allLocal.isEmpty()) {
                return@withContext Pair(0, "No local memories to sync.")
            }

            var syncedCount = 0
            for (mem in allLocal) {
                val ok = syncMemory(context, mem)
                if (ok) syncedCount++
            }
            Pair(syncedCount, "Successfully synced $syncedCount memories to Second Brain!")
        } catch (e: Exception) {
            Pair(0, "Batch sync failed: ${e.message}")
        }
    }
}
