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
            "na name enti", "naa name enti", "naa peru cheppu", "na peru cheppu",
            "gurthupettukonna name enti", "gurtupettukunna name enti", "gurthupettukonna name",
            "mera naam kya hai", "what do you know about me", "what do you remember about me",
            "naa gurinchi em thelusu", "na gurinchi em thelusu", "na gurinchi cheppu",
            "list my memories", "my memories", "na memories enti"
        )) return true

        // Flexible query detection for user name / identity
        val hasNameInquiry = lower.contains("na peru") || lower.contains("naa peru") || lower.contains("na name") || lower.contains("naa name") || lower.contains("my name")
        val hasInquiryWord = lower.contains("enti") || lower.contains("emiti") || lower.contains("cheppu") || 
                             lower.contains("gurthunda") || lower.contains("gurtunda") || lower.contains("telusa") || 
                             lower.contains("thelusu") || lower.contains("what") || lower.endsWith("?")
        if (hasNameInquiry && hasInquiryWord) return true

        // Question patterns: "what is my ...", "which ... do i ...", "naa ... enti", "tell me my ...", etc.
        if (lower.startsWith("what is my ") || lower.startsWith("what's my ") ||
            lower.startsWith("tell me my ") || lower.startsWith("tell me about my ") ||
            lower.startsWith("do you know my ") || lower.startsWith("do you remember my ") ||
            lower.startsWith("which is my ") || lower.startsWith("which ") ||
            lower.startsWith("where is my ") || lower.startsWith("who is my ") ||
            (lower.startsWith("what ") && (lower.contains("do i have") || lower.contains("do i use") || lower.contains("is my")))
        ) return true

        if ((lower.startsWith("naa ") || lower.startsWith("na ") || lower.startsWith("my ")) &&
            (lower.endsWith("enti") || lower.endsWith("enti?") || lower.endsWith("emiti") || lower.endsWith("emiti?") || lower.endsWith("cheppu") || lower.endsWith("gurthunda") || lower.endsWith("gurtunda") || lower.endsWith("telusa") || lower.endsWith("name") || lower.endsWith("peru"))
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

    /**
     * Extracts personal identity/facts from user text and persists directly to Room DB.
     * Returns a confirmation message in natural Tinglish, or null if text is not a memory storage command.
     */
    suspend fun extractAndSaveMemoryFact(context: Context, text: String): String? {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // Interrogative questions are queries, NOT save commands
        if (isMemoryQuery(trimmed) || trimmed.endsWith("?") || lower.endsWith("enti") || lower.endsWith("enti?") || lower.endsWith("telusa")) {
            return null
        }

        val hasRememberIntent = lower.contains("gurthu") ||
                                lower.contains("gurtu") ||
                                lower.contains("గుర్తు") ||
                                lower.contains("remember") ||
                                lower.contains("save") ||
                                lower.contains("store") ||
                                lower.contains("note") ||
                                lower.contains("keep in mind") ||
                                lower.contains("yaad") ||
                                lower.contains("chesuko") ||
                                lower.contains("chesko")

        // 1. Personal Name Extraction
        val namePatterns = listOf(
            // "cheppu gurthu pettuko na peru Ashwin", "gurthu pettuko na peru Ashwin", "gurthupettuko na peru Ashwin"
            Regex("(?i)(?:cheppu\\s+)?(?:gurthu\\s*pettuko|gurtu\\s*pettuko|gurthunchuko|gurtunchuko|gurthupettukonna|gurtupettukonna|remember|save|note).*?(?:naa|na|my|mera)\\s+(?:peru|name|naam)\\s+(?:is\\s+|enti\\s+ante\\s+|ani\\s+)?([a-zA-Z0-9_]+)"),
            // "naa peru Ashwin ani gurthu pettuko", "na peru Ashwin gurthu pettuko", "na peru Ashwin"
            Regex("(?i)(?:naa|na|my|mera)\\s+(?:peru|name|naam)\\s+(?:is\\s+|enti\\s+ante\\s+|ani\\s+)?([a-zA-Z0-9_]+)[\\.\\s,]*(?:ani\\s+)?(?:gurthu\\s*pettuko|gurtu\\s*pettuko|gurthunchuko|remember|save|note|chesu?ko)?"),
            // "my name is Ashwin"
            Regex("(?i)my\\s+name\\s+is\\s+([a-zA-Z0-9_]+)[\\.\\s,]*(?:remember|save|note)?"),
            // "mera naam Ashwin hai"
            Regex("(?i)mera\\s+naam\\s+([a-zA-Z0-9_]+)\\s+hai"),
            // "memory lo na name save chesuko: Ashwin" / "memory lo na peru save chey Ashwin"
            Regex("(?i)memory\\s+lo\\s+(?:naa|na|my)?\\s*(?:name|peru)\\s+(?:save\\s+chesu?ko|save\\s+chey+|store\\s+chesu?ko|store\\s+chey+|pettu)\\s*[:\\s-]?\\s*([a-zA-Z0-9_]+)"),
            // "na name save chesuko: Ashwin" / "na peru save chey: Ashwin"
            Regex("(?i)(?:naa|na|my)?\\s*(?:name|peru)\\s+(?:save\\s+chesu?ko|save\\s+chey+|store\\s+chesu?ko|store\\s+chey+|pettu)\\s*[:\\s-]?\\s*([a-zA-Z0-9_]+)"),
            // "save my name Ashwin"
            Regex("(?i)save\\s+(?:my\\s+)?(?:name|peru)\\s*[:\\s-]?\\s*([a-zA-Z0-9_]+)"),
            // "i am Ashwin"
            Regex("(?i)(?:i\\s+am|i'm)\\s+([a-zA-Z0-9_]+)[\\.\\s,]*(?:gurthu|remember|save)?")
        )

        for (pattern in namePatterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val candidateName = match.groupValues[1].trim()
                val invalidNames = setOf("enti", "emiti", "what", "kya", "cheppu", "gurthupettuko", "gurtupettuko", "save", "memory", "max", "hello", "hi", "sir", "ani", "adi", "idi")
                if (candidateName.length >= 2 && candidateName.lowercase() !in invalidNames) {
                    val repository = MemoryRepository.getInstance(context)
                    val mem = PermanentMemory(
                        title = "User Name",
                        content = "User name is $candidateName",
                        category = "Personal",
                        type = com.example.memory.data.MemoryType.FACT,
                        source = com.example.memory.data.MemorySource.AUTO,
                        tags = "name,identity,personal"
                    )
                    repository.addMemory(mem)
                    return "Sure sir, mee peru gurthupettukuntaanu."
                }
            }
        }

        // 2. Project Extraction
        if (lower.contains("project") && (hasRememberIntent || lower.startsWith("my project") || lower.startsWith("na project") || lower.startsWith("naa project"))) {
            val projRegex = Regex("(?i)(?:my|na|naa|mera)\\s+(?:main\\s+)?project\\s+(?:is\\s+|enti\\s+ante\\s+|ani\\s+)?([a-zA-Z0-9_\\-\\s]+)", RegexOption.IGNORE_CASE)
            val matchProj = projRegex.find(trimmed)
            if (matchProj != null) {
                val rawProj = matchProj.groupValues[1]
                    .replace(Regex("(?i)[\\.\\s,]*(remember|gurthu\\s*pettuko|gurtu\\s*pettuko|save|note|chesuko).*"), "")
                    .trim()
                if (rawProj.length >= 2) {
                    val repository = MemoryRepository.getInstance(context)
                    val mem = PermanentMemory(
                        title = "Main Project",
                        content = "User main project is $rawProj",
                        category = "Work",
                        type = com.example.memory.data.MemoryType.FACT,
                        source = com.example.memory.data.MemorySource.AUTO,
                        tags = "project,work,max"
                    )
                    repository.addMemory(mem)
                    return "Sure sir, mee project $rawProj ani gurthupettukunnanu."
                }
            }
        }

        // 3. Laptop / Device Extraction
        if ((lower.contains("laptop") || lower.contains("mobile") || lower.contains("phone")) &&
            (hasRememberIntent || lower.startsWith("my laptop") || lower.startsWith("na laptop") || lower.startsWith("naa laptop") || lower.startsWith("my phone") || lower.startsWith("na phone"))
        ) {
            val devRegex = Regex("(?i)(?:my|na|naa|mera)\\s+(?:laptop|mobile|phone)\\s+(?:is\\s+|enti\\s+ante\\s+|ani\\s+)?([a-zA-Z0-9_\\-\\s]+)", RegexOption.IGNORE_CASE)
            val matchDev = devRegex.find(trimmed)
            if (matchDev != null) {
                val rawDev = matchDev.groupValues[1]
                    .replace(Regex("(?i)[\\.\\s,]*(remember|gurthu\\s*pettuko|gurtu\\s*pettuko|save|note|chesuko).*"), "")
                    .trim()
                if (rawDev.length >= 2) {
                    val repository = MemoryRepository.getInstance(context)
                    val memTitle = if (lower.contains("laptop")) "Laptop" else "Device"
                    val mem = PermanentMemory(
                        title = memTitle,
                        content = "User $memTitle is $rawDev",
                        category = "Personal",
                        type = com.example.memory.data.MemoryType.FACT,
                        source = com.example.memory.data.MemorySource.AUTO,
                        tags = "${memTitle.lowercase()},hardware"
                    )
                    repository.addMemory(mem)
                    return "Sure sir, mee ${memTitle.lowercase()} $rawDev ani gurthupettukunnanu."
                }
            }
        }

        // 4. Explicit Memory Command (e.g. "idi memory lo save chey: <fact>")
        if (hasRememberIntent && (lower.contains("memory lo") || lower.contains("note down") || lower.startsWith("save that") || lower.startsWith("remember that"))) {
            val contentFact = trimmed
                .replace(Regex("(?i)^.*?(?:save chey|save chesko|save chesuko|gurthupettuko|gurtupettuko|note down|remember that|remember this)[:\\s-]*"), "")
                .trim()
            if (contentFact.length >= 3) {
                val repository = MemoryRepository.getInstance(context)
                val mem = PermanentMemory(
                    title = "User Fact",
                    content = contentFact,
                    category = "Personal",
                    type = com.example.memory.data.MemoryType.FACT,
                    source = com.example.memory.data.MemorySource.AUTO,
                    tags = "user,fact"
                )
                repository.addMemory(mem)
                return "Sure sir, gurthupettukunnanu: '$contentFact'."
            }
        }

        return null
    }
}
