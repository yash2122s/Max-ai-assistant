package com.example.memory.universal

import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ImportParsers {

    fun parse(
        provider: ImportProviderType,
        rawContent: String,
        errors: MutableList<String>
    ): List<ImportCandidate> {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            when (provider) {
                ImportProviderType.MAX_BACKUP -> parseMaxBackup(trimmed, errors)
                ImportProviderType.CHATGPT -> parseChatGPT(trimmed, errors)
                ImportProviderType.CLAUDE -> parseClaude(trimmed, errors)
                ImportProviderType.GEMINI -> parseGemini(trimmed, errors)
                ImportProviderType.OBSIDIAN -> parseObsidian(trimmed, errors)
                ImportProviderType.MARKDOWN -> parseMarkdown(trimmed, errors)
                ImportProviderType.GENERIC_TEXT -> parseGenericText(trimmed, errors)
            }
        } catch (e: Exception) {
            errors.add("Parser failure for provider $provider: ${e.localizedMessage}")
            parseGenericText(trimmed, errors)
        }
    }

    // -------------------------------------------------------------------------
    // 1. MAX Backup Parser (JSON or Projection Markdown)
    // -------------------------------------------------------------------------

    private fun parseMaxBackup(content: String, errors: MutableList<String>): List<ImportCandidate> {
        val candidates = mutableListOf<ImportCandidate>()

        if (content.startsWith("{") || content.startsWith("[")) {
            val jsonArray = if (content.startsWith("{")) {
                val obj = JSONObject(content)
                obj.optJSONArray("memories") ?: obj.optJSONArray("items") ?: JSONArray()
            } else {
                JSONArray(content)
            }

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val memContent = item.optString("content", "").trim()
                if (title.isEmpty() && memContent.isEmpty()) continue

                val cleanTitle = if (title.isEmpty()) memContent.take(40) else title
                val category = item.optString("category", "Personal").trim().ifEmpty { "Personal" }
                val tags = item.optString("tags", "").trim()
                val typeStr = item.optString("type", "FACT").trim().uppercase()
                val type = runCatching { MemoryType.valueOf(typeStr) }.getOrElse { MemoryType.FACT }
                val sourceRecordId = item.optString("memoryId", item.optString("id", "")).takeIf { it.isNotEmpty() }
                
                val rawForChecksum = item.toString()
                val importChecksum = item.optString("importChecksum", "").takeIf { it.isNotEmpty() }
                    ?: PermanentMemory.computeImportChecksum(rawForChecksum)
                val contentHash = PermanentMemory.computeContentHash(cleanTitle, memContent, category, tags)

                candidates.add(
                    ImportCandidate(
                        title = cleanTitle,
                        content = memContent,
                        category = category,
                        type = type,
                        provider = ImportProviderType.MAX_BACKUP,
                        sourceRecordId = sourceRecordId,
                        sourceTimestamp = item.optLong("createdAt", System.currentTimeMillis()),
                        tags = tags,
                        confidence = 1.0f,
                        importChecksum = importChecksum,
                        contentHash = contentHash
                    )
                )
            }
            if (candidates.isNotEmpty()) return candidates
        }

        // Fallback: Parse MAX_MEMORY.md format
        return parseMarkdown(content, errors)
    }

    // -------------------------------------------------------------------------
    // 2. ChatGPT Parser
    // -------------------------------------------------------------------------

    private fun parseChatGPT(content: String, errors: MutableList<String>): List<ImportCandidate> {
        val candidates = mutableListOf<ImportCandidate>()

        if (content.startsWith("{") || content.startsWith("[")) {
            if (content.startsWith("{")) {
                val root = JSONObject(content)

                // Custom instructions
                val userInstructions = root.optString("user_instructions", "").trim()
                if (userInstructions.isNotEmpty()) {
                    val rawStr = "Custom User Instructions: $userInstructions"
                    candidates.add(
                        ImportCandidate(
                            title = "User Instructions",
                            content = userInstructions,
                            category = "Preferences",
                            type = MemoryType.PREFERENCE,
                            provider = ImportProviderType.CHATGPT,
                            sourceRecordId = "chatgpt_custom_user_instructions",
                            tags = "chatgpt,instructions,preference",
                            confidence = 1.0f,
                            importChecksum = PermanentMemory.computeImportChecksum(rawStr),
                            contentHash = PermanentMemory.computeContentHash("User Instructions", userInstructions, "Preferences", "chatgpt,instructions,preference")
                        )
                    )
                }

                val customInstructions = root.optString("custom_instructions", "").trim()
                if (customInstructions.isNotEmpty() && customInstructions != userInstructions) {
                    val rawStr = "Custom Assistant Instructions: $customInstructions"
                    candidates.add(
                        ImportCandidate(
                            title = "Assistant Persona Instructions",
                            content = customInstructions,
                            category = "Preferences",
                            type = MemoryType.PREFERENCE,
                            provider = ImportProviderType.CHATGPT,
                            sourceRecordId = "chatgpt_custom_model_instructions",
                            tags = "chatgpt,instructions",
                            confidence = 1.0f,
                            importChecksum = PermanentMemory.computeImportChecksum(rawStr),
                            contentHash = PermanentMemory.computeContentHash("Assistant Persona Instructions", customInstructions, "Preferences", "chatgpt,instructions")
                        )
                    )
                }

                // Explicit memory entries array
                val memoryEntries = root.optJSONArray("memory_entries")
                if (memoryEntries != null) {
                    for (i in 0 until memoryEntries.length()) {
                        val entry = memoryEntries.opt(i)
                        val text = when (entry) {
                            is String -> entry.trim()
                            is JSONObject -> entry.optString("content", entry.optString("text", "")).trim()
                            else -> ""
                        }
                        if (text.isNotEmpty()) {
                            val title = text.take(50).substringBefore("\n")
                            val raw = entry.toString()
                            candidates.add(
                                ImportCandidate(
                                    title = title,
                                    content = text,
                                    category = "Personal",
                                    type = MemoryType.FACT,
                                    provider = ImportProviderType.CHATGPT,
                                    sourceRecordId = "chatgpt_mem_$i",
                                    tags = "chatgpt,memory",
                                    confidence = 0.95f,
                                    importChecksum = PermanentMemory.computeImportChecksum(raw),
                                    contentHash = PermanentMemory.computeContentHash(title, text, "Personal", "chatgpt,memory")
                                )
                            )
                        }
                    }
                }
            } else {
                // Array of conversations
                val convArray = JSONArray(content)
                for (i in 0 until convArray.length()) {
                    val conv = convArray.optJSONObject(i) ?: continue
                    val convId = conv.optString("id", conv.optString("conversation_id", "conv_$i"))
                    val title = conv.optString("title", "Conversation $i").trim()
                    val mapping = conv.optJSONObject("mapping")

                    if (mapping != null) {
                        val messages = mutableListOf<String>()
                        val keys = mapping.keys()
                        while (keys.hasNext()) {
                            val node = mapping.optJSONObject(keys.next()) ?: continue
                            val msg = node.optJSONObject("message") ?: continue
                            val author = msg.optJSONObject("author")?.optString("role", "") ?: ""
                            val parts = msg.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null && (author == "user" || author == "assistant")) {
                                val text = (0 until parts.length()).mapNotNull { parts.optString(it) }.joinToString("\n").trim()
                                if (text.isNotEmpty() && text.length < 500) {
                                    messages.add("$author: $text")
                                }
                            }
                        }

                        if (messages.isNotEmpty()) {
                            val summaryContent = messages.take(6).joinToString("\n")
                            val rawStr = "$convId|$title|$summaryContent"
                            candidates.add(
                                ImportCandidate(
                                    title = title,
                                    content = summaryContent,
                                    category = "Projects",
                                    type = MemoryType.FACT,
                                    provider = ImportProviderType.CHATGPT,
                                    sourceRecordId = convId,
                                    sourceTimestamp = conv.optLong("create_time", System.currentTimeMillis()),
                                    tags = "chatgpt,conversation",
                                    confidence = 0.85f,
                                    importChecksum = PermanentMemory.computeImportChecksum(rawStr),
                                    contentHash = PermanentMemory.computeContentHash(title, summaryContent, "Projects", "chatgpt,conversation")
                                )
                            )
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return parseMarkdown(content, errors)
        }

        return candidates
    }

    // -------------------------------------------------------------------------
    // 3. Claude Parser
    // -------------------------------------------------------------------------

    private fun parseClaude(content: String, errors: MutableList<String>): List<ImportCandidate> {
        val candidates = mutableListOf<ImportCandidate>()

        if (content.startsWith("{") || content.startsWith("[")) {
            val jsonArray = if (content.startsWith("[")) JSONArray(content) else {
                val obj = JSONObject(content)
                obj.optJSONArray("claude_memories") ?: obj.optJSONArray("chat_messages") ?: JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val uuid = item.optString("uuid", item.optString("id", "claude_$i"))
                val name = item.optString("name", item.optString("title", "")).trim()
                val text = item.optString("content", item.optString("text", "")).trim()
                if (text.isEmpty()) continue

                val cleanTitle = if (name.isNotEmpty()) name else text.take(45).substringBefore("\n")
                val tags = "claude,memory"
                val rawStr = "$uuid|$cleanTitle|$text"

                candidates.add(
                    ImportCandidate(
                        title = cleanTitle,
                        content = text,
                        category = "Personal",
                        type = MemoryType.FACT,
                        provider = ImportProviderType.CLAUDE,
                        sourceRecordId = uuid,
                        tags = tags,
                        confidence = 0.95f,
                        importChecksum = PermanentMemory.computeImportChecksum(rawStr),
                        contentHash = PermanentMemory.computeContentHash(cleanTitle, text, "Personal", tags)
                    )
                )
            }
            if (candidates.isNotEmpty()) return candidates
        }

        // Markdown Claude Memory format (e.g. combined_memories.md or .claude/memory)
        return parseMarkdown(content, errors, defaultProvider = ImportProviderType.CLAUDE, defaultTag = "claude")
    }

    // -------------------------------------------------------------------------
    // 4. Gemini Parser
    // -------------------------------------------------------------------------

    private fun parseGemini(content: String, errors: MutableList<String>): List<ImportCandidate> {
        val candidates = mutableListOf<ImportCandidate>()

        if (content.startsWith("{") || content.startsWith("[")) {
            val array = if (content.startsWith("[")) JSONArray(content) else {
                val obj = JSONObject(content)
                obj.optJSONArray("gemini_conversations") ?: obj.optJSONArray("conversations") ?: JSONArray()
            }

            for (i in 0 until array.length()) {
                val conv = array.optJSONObject(i) ?: continue
                val id = conv.optString("id", "gemini_$i")
                val title = conv.optString("title", "Gemini Memory $i").trim()
                val text = conv.optString("content", conv.optString("snippet", "")).trim()
                if (text.isEmpty()) continue

                val tags = "gemini,takeout"
                val raw = "$id|$title|$text"

                candidates.add(
                    ImportCandidate(
                        title = title,
                        content = text,
                        category = "Personal",
                        type = MemoryType.FACT,
                        provider = ImportProviderType.GEMINI,
                        sourceRecordId = id,
                        tags = tags,
                        confidence = 0.9f,
                        importChecksum = PermanentMemory.computeImportChecksum(raw),
                        contentHash = PermanentMemory.computeContentHash(title, text, "Personal", tags)
                    )
                )
            }
            if (candidates.isNotEmpty()) return candidates
        }

        return parseMarkdown(content, errors, defaultProvider = ImportProviderType.GEMINI, defaultTag = "gemini")
    }

    // -------------------------------------------------------------------------
    // 5. Obsidian Parser (YAML Frontmatter & Wikilinks)
    // -------------------------------------------------------------------------

    private fun parseObsidian(content: String, errors: MutableList<String>): List<ImportCandidate> {
        val candidates = mutableListOf<ImportCandidate>()
        var text = content

        val extractedTags = mutableListOf<String>()
        var extractedCategory = "Personal"

        // Strip and parse YAML frontmatter
        if (text.startsWith("---")) {
            val endIndex = text.indexOf("\n---", 3)
            if (endIndex != -1) {
                val yamlBlock = text.substring(3, endIndex)
                text = text.substring(endIndex + 4).trim()

                for (line in yamlBlock.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("tags:")) {
                        val tagVal = trimmed.removePrefix("tags:").trim().removeSurrounding("[", "]")
                        tagVal.split(",").forEach { t ->
                            val clean = t.trim().removePrefix("#")
                            if (clean.isNotEmpty()) extractedTags.add(clean)
                        }
                    }
                    if (trimmed.startsWith("category:")) {
                        extractedCategory = trimmed.removePrefix("category:").trim().replaceFirstChar { it.uppercase() }
                    }
                }
            }
        }

        // Clean Obsidian wikilinks [[Topic|Display]] -> Display, [[Topic]] -> Topic
        text = text.replace(Regex("\\[\\[(?:[^|\\]]*\\|)?([^\\]]+)\\]\\]")) { it.groupValues[1] }

        val tagsStr = (extractedTags + listOf("obsidian")).distinct().joinToString(",")

        // Parse remaining content into structured blocks
        val blocks = parseMarkdown(text, errors, defaultProvider = ImportProviderType.OBSIDIAN, defaultTag = tagsStr)
        for (b in blocks) {
            candidates.add(
                b.copy(
                    category = if (b.category == "Personal" && extractedCategory.isNotEmpty()) extractedCategory else b.category,
                    tags = tagsStr
                )
            )
        }

        return candidates
    }

    // -------------------------------------------------------------------------
    // 6. Generic Markdown Parser
    // -------------------------------------------------------------------------

    fun parseMarkdown(
        content: String,
        errors: MutableList<String>,
        defaultProvider: ImportProviderType = ImportProviderType.MARKDOWN,
        defaultTag: String = ""
    ): List<ImportCandidate> {
        val candidates = mutableListOf<ImportCandidate>()
        val lines = content.lines()

        var currentHeading = ""
        var currentCategory = "Personal"
        val buffer = mutableListOf<String>()

        fun flushBuffer() {
            val body = buffer.filter { !it.trim().startsWith(">") && !it.trim().startsWith("---") }.joinToString("\n").trim()
            buffer.clear()
            if (body.isEmpty()) return

            val cleanTitle = if (currentHeading.isNotEmpty()) currentHeading else body.take(50).substringBefore("\n")

            // Skip top-level document titles and category section headers as standalone memory items
            val lowerTitle = cleanTitle.lowercase()
            if (lowerTitle == "max memory" || lowerTitle == "claude memory" ||
                lowerTitle == "profile" || lowerTitle == "preferences" || lowerTitle == "instructions" ||
                lowerTitle == "projects & work" || lowerTitle == "goals" || lowerTitle == "conversations" ||
                lowerTitle == "imported knowledge" || lowerTitle == "other" || lowerTitle == "personal") {
                return
            }

            val tags = defaultTag.ifEmpty { defaultProvider.name.lowercase() }
            val raw = "$cleanTitle|$body|$currentCategory"

            candidates.add(
                ImportCandidate(
                    title = cleanTitle,
                    content = body,
                    category = currentCategory,
                    type = if (currentCategory.contains("Preference", ignoreCase = true)) MemoryType.PREFERENCE else MemoryType.FACT,
                    provider = defaultProvider,
                    sourceRecordId = null,
                    tags = tags,
                    confidence = 1.0f,
                    importChecksum = PermanentMemory.computeImportChecksum(raw),
                    contentHash = PermanentMemory.computeContentHash(cleanTitle, body, currentCategory, tags)
                )
            )
        }

        for (line in lines) {
            val trimmed = line.trim()

            // Header level 1, 2, 3
            if (trimmed.startsWith("#")) {
                flushBuffer()
                val headingText = trimmed.replace(Regex("^#+\\s*"), "").trim()
                currentHeading = headingText

                // Map category names from headers
                val lower = headingText.lowercase()
                currentCategory = when {
                    lower.contains("preference") -> "Preferences"
                    lower.contains("work") || lower.contains("career") -> "Work"
                    lower.contains("project") -> "Projects"
                    lower.contains("device") -> "Devices"
                    lower.contains("goal") -> "Goals"
                    lower.contains("education") -> "Education"
                    lower.contains("programming") || lower.contains("code") -> "Programming"
                    lower.contains("instruction") -> "Preferences"
                    else -> "Personal"
                }
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("★ ")) {
                // Bullet item: check if formatted as "Title: Content"
                val bulletContent = trimmed.replace(Regex("^[-*★]\\s*"), "").trim()
                if (bulletContent.contains(": ") && bulletContent.indexOf(": ") < 60) {
                    val parts = bulletContent.split(Regex(":\\s+"), limit = 2)
                    val itemTitle = parts[0].trim()
                    var itemBody = parts[1].trim()
                    var itemTags = defaultTag

                    if (itemBody.contains("(Tags:") || itemBody.contains("(tags:")) {
                        val tagMatch = Regex("\\((?:Tags|tags):\\s*([^)]+)\\)").find(itemBody)
                        if (tagMatch != null) {
                            itemTags = tagMatch.groupValues[1].trim()
                            itemBody = itemBody.replace(tagMatch.value, "").trim()
                        }
                    }

                    val raw = "$itemTitle|$itemBody|$currentCategory"
                    candidates.add(
                        ImportCandidate(
                            title = itemTitle,
                            content = itemBody,
                            category = currentCategory,
                            type = MemoryType.FACT,
                            provider = defaultProvider,
                            sourceRecordId = null,
                            tags = itemTags,
                            confidence = 1.0f,
                            importChecksum = PermanentMemory.computeImportChecksum(raw),
                            contentHash = PermanentMemory.computeContentHash(itemTitle, itemBody, currentCategory, itemTags)
                        )
                    )
                } else {
                    buffer.add(bulletContent)
                }
            } else if (trimmed.isNotEmpty()) {
                buffer.add(trimmed)
            } else {
                // Empty line acts as separator for paragraph items
                if (buffer.isNotEmpty() && currentHeading.isEmpty()) {
                    flushBuffer()
                }
            }
        }

        flushBuffer()
        return candidates
    }

    // -------------------------------------------------------------------------
    // 7. Generic Text Fallback Parser
    // -------------------------------------------------------------------------

    private fun parseGenericText(content: String, errors: MutableList<String>): List<ImportCandidate> {
        val candidates = mutableListOf<ImportCandidate>()
        val paragraphs = content.split(Regex("\n\\s*\n+")).map { it.trim() }.filter { it.isNotEmpty() }

        for ((i, p) in paragraphs.withIndex()) {
            val title = p.take(45).substringBefore("\n").trim()
            val raw = "text_$i|$p"
            candidates.add(
                ImportCandidate(
                    title = if (title.isNotEmpty()) title else "Imported Fact ${i + 1}",
                    content = p,
                    category = "Personal",
                    type = MemoryType.FACT,
                    provider = ImportProviderType.GENERIC_TEXT,
                    sourceRecordId = null,
                    tags = "imported",
                    confidence = 0.8f,
                    importChecksum = PermanentMemory.computeImportChecksum(raw),
                    contentHash = PermanentMemory.computeContentHash(title, p, "Personal", "imported")
                )
            )
        }

        return candidates
    }
}
