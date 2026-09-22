package com.example.memory.retrieval

import android.content.Context
import com.example.memory.data.MemoryRepository
import com.example.memory.data.PermanentMemory

data class MemoryFactResult(
    val found: Boolean,
    val matchedMemory: PermanentMemory? = null,
    val responseText: String
)

object MemoryFactResolver {

    private val STOP_WORDS = setOf(
        "what", "is", "my", "your", "the", "a", "an", "tell", "me", "about",
        "do", "you", "know", "remember", "which", "who", "where", "how",
        "naa", "na", "enti", "cheppu", "gurthunda", "gurinchi", "em", "thelusu", "telusa",
        "meri", "mera", "kya", "hai", "batao", "sir", "please", "bro"
    )

    fun isMemoryQuery(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return false

        // Exact personal identity / summary questions
        if (lower in setOf(
            "who am i", "what is my name", "naa peru enti", "na peru enti", "na peru",
            "mera naam kya hai", "what do you know about me", "what do you remember about me",
            "naa gurinchi em thelusu", "na gurinchi em thelusu", "na gurinchi cheppu",
            "list my memories", "my memories", "na memories enti"
        )) return true

        // Question patterns: "what is my ...", "which ... do i ...", "naa ... enti", "tell me my ...", etc.
        if (lower.startsWith("what is my ") || lower.startsWith("what's my ") ||
            lower.startsWith("tell me my ") || lower.startsWith("tell me about my ") ||
            lower.startsWith("do you know my ") || lower.startsWith("do you remember my ") ||
            lower.startsWith("which is my ") || lower.startsWith("which ") ||
            lower.startsWith("where is my ") || lower.startsWith("who is my ") ||
            (lower.startsWith("what ") && (lower.contains("do i have") || lower.contains("do i use") || lower.contains("is my")))
        ) return true

        if ((lower.startsWith("naa ") || lower.startsWith("na ")) &&
            (lower.endsWith("enti") || lower.endsWith("enti?") || lower.endsWith("cheppu") || lower.endsWith("gurthunda") || lower.endsWith("telusa"))
        ) return true

        if (lower.startsWith("mera ") || lower.startsWith("meri ")) {
            if (lower.endsWith("kya hai") || lower.endsWith("kya hai?") || lower.endsWith("batao")) return true
        }

        return false
    }

    /**
     * Resolves a personal memory query directly against Room DB in real-time.
     */
    suspend fun resolve(context: Context, userText: String): MemoryFactResult? {
        if (!isMemoryQuery(userText)) return null

        val repository = MemoryRepository.getInstance(context)
        val memories = repository.getAllEnabled().filter { !it.isPrivate }

        val lower = userText.trim().lowercase()

        // 1. Full Memory Summary query: "what do you know about me", "naa gurinchi em thelusu"
        val isSummaryQuery = lower in setOf(
            "what do you know about me", "what do you remember about me",
            "naa gurinchi em thelusu", "na gurinchi em thelusu", "na gurinchi cheppu",
            "list my memories", "my memories", "na memories enti", "tell me everything you know about me"
        )

        if (isSummaryQuery) {
            if (memories.isEmpty()) {
                return MemoryFactResult(
                    found = false,
                    responseText = "Sir, naa daggara meeku sambandhinchina permanent memories emi levu."
                )
            }
            val sb = StringBuilder("Mee gurinchi naa memory lo unnavi:\n")
            memories.take(5).forEach { mem ->
                sb.append("• ${mem.title}: ${mem.content}\n")
            }
            return MemoryFactResult(
                found = true,
                responseText = sb.toString().trim()
            )
        }

        // 2. Specific fact inquiry (e.g. "What is my favourite food?", "What is my name?")
        if (memories.isEmpty()) {
            return MemoryFactResult(
                found = false,
                responseText = "Sir, adi naa memory lo ledu."
            )
        }

        // Extract query terms without stop words
        val rawTokens = lower.replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val contentTokens = rawTokens.filter { it !in STOP_WORDS && it.length >= 2 }

        // Score memories
        var bestMatch: PermanentMemory? = null
        var highestScore = 0

        for (mem in memories) {
            val titleLower = mem.title.lowercase()
            val contentLower = mem.content.lowercase()
            val categoryLower = mem.category.lowercase()
            val tagsLower = mem.tags.lowercase()

            var score = 0

            // Specific check for "name" / "peru"
            val isNameQuery = "name" in rawTokens || "peru" in rawTokens || "naam" in rawTokens || lower.contains("who am i")
            if (isNameQuery && (titleLower.contains("name") || titleLower.contains("peru") || categoryLower.contains("personal"))) {
                score += 10
            }

            // Specific check for "food" / "biryani" / "tiffin" / "breakfast" / "favourite"
            val isFoodQuery = "food" in rawTokens || "favourite" in rawTokens || "favorite" in rawTokens || "tiffin" in rawTokens || "dinner" in rawTokens || "lunch" in rawTokens
            if (isFoodQuery && (titleLower.contains("food") || titleLower.contains("favourite") || titleLower.contains("favorite") || contentLower.contains("biryani") || categoryLower.contains("preference"))) {
                score += 8
            }

            // Keyword matching
            for (token in contentTokens) {
                if (titleLower.contains(token)) score += 5
                if (contentLower.contains(token)) score += 3
                if (tagsLower.contains(token)) score += 2
                if (categoryLower.contains(token)) score += 1
            }

            // Handle synonym / domain matching
            if ("laptop" in contentTokens && (titleLower.contains("laptop") || contentLower.contains("victus") || contentLower.contains("hp") || contentLower.contains("pc"))) score += 6
            if ("phone" in contentTokens && (titleLower.contains("phone") || contentLower.contains("mobile") || contentLower.contains("device"))) score += 6
            if ("project" in contentTokens && (titleLower.contains("project") || contentLower.contains("max") || contentLower.contains("work"))) score += 6

            if (score > highestScore) {
                highestScore = score
                bestMatch = mem
            }
        }

        if (bestMatch != null && highestScore >= 3) {
            val formattedResponse = formatMemoryFactResponse(bestMatch, userText)
            return MemoryFactResult(
                found = true,
                matchedMemory = bestMatch,
                responseText = formattedResponse
            )
        }

        return MemoryFactResult(
            found = false,
            responseText = "Sir, adi naa memory lo ledu."
        )
    }

    private fun formatMemoryFactResponse(memory: PermanentMemory, userQuery: String): String {
        val title = memory.title.trim()
        val content = memory.content.trim()

        // If content already forms a complete sentence (e.g. "User name is Yashwanth" or "Yashwanth")
        if (content.contains("is ", ignoreCase = true) || content.contains("are ", ignoreCase = true)) {
            val clean = content.replace("User name is ", "Mee peru ", ignoreCase = true)
                .replace("User is ", "Meeru ", ignoreCase = true)
                .replace("User loves ", "Meeku ", ignoreCase = true)
                .replace("User prefers ", "Meeku ", ignoreCase = true)
            return "$clean, sir."
        }

        // Title-value formatting (e.g. Title: "Favourite Food", Content: "Dilkush Biryani")
        return "Mee $title $content, sir."
    }
}
