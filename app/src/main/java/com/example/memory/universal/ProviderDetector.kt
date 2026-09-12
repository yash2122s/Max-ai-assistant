package com.example.memory.universal

import org.json.JSONArray
import org.json.JSONObject

object ProviderDetector {

    fun detect(rawContent: String, fileName: String? = null): ImportProviderType {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return ImportProviderType.GENERIC_TEXT

        val lowerName = fileName?.lowercase() ?: ""

        // Check if content is valid JSON
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || 
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return detectFromJson(trimmed, lowerName)
        }

        // Markdown / Obsidian / Plaintext detection
        return detectFromText(trimmed, lowerName)
    }

    private fun detectFromJson(jsonStr: String, fileName: String): ImportProviderType {
        try {
            if (jsonStr.startsWith("{")) {
                val obj = JSONObject(jsonStr)

                // MAX backup detection
                if (obj.has("max_backup_version") || 
                    obj.optString("export_source") == "MAX" || 
                    (obj.has("memories") && obj.has("backup_timestamp"))) {
                    return ImportProviderType.MAX_BACKUP
                }

                // ChatGPT detection
                if (obj.has("custom_instructions") || 
                    obj.has("user_instructions") || 
                    obj.has("memory_entries") || 
                    obj.has("conversations")) {
                    return ImportProviderType.CHATGPT
                }

                // Claude detection
                if (obj.has("claude_conversations") || 
                    obj.has("claude_memories") || 
                    obj.has("claude_export")) {
                    return ImportProviderType.CLAUDE
                }

                // Gemini detection
                if (obj.has("gemini_conversations") || 
                    obj.has("gemini_export") || 
                    obj.has("takeout_gemini")) {
                    return ImportProviderType.GEMINI
                }

                // Generic array container inside object
                if (obj.has("memories") || obj.has("items") || obj.has("facts")) {
                    return ImportProviderType.MAX_BACKUP
                }
            } else if (jsonStr.startsWith("[")) {
                val array = JSONArray(jsonStr)
                if (array.length() > 0) {
                    val first = array.optJSONObject(0)
                    if (first != null) {
                        // MAX memory JSON items
                        if (first.has("memoryId") || (first.has("title") && first.has("content") && first.has("category"))) {
                            return ImportProviderType.MAX_BACKUP
                        }

                        // ChatGPT conversations array
                        if (first.has("mapping") && (first.has("current_node") || first.has("create_time"))) {
                            return ImportProviderType.CHATGPT
                        }

                        // Claude chats array
                        if (first.has("chat_messages") || first.has("uuid") && first.has("name")) {
                            return ImportProviderType.CLAUDE
                        }

                        // Gemini parts array
                        if (first.has("parts") || first.has("candidates")) {
                            return ImportProviderType.GEMINI
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Not valid JSON, continue with text detection
        }

        // Fallback for JSON named files
        if (fileName.contains("chatgpt") || fileName.contains("conversations")) return ImportProviderType.CHATGPT
        if (fileName.contains("claude")) return ImportProviderType.CLAUDE
        if (fileName.contains("gemini")) return ImportProviderType.GEMINI
        if (fileName.contains("max_backup") || fileName.contains("memories")) return ImportProviderType.MAX_BACKUP

        return ImportProviderType.MAX_BACKUP
    }

    private fun detectFromText(text: String, fileName: String): ImportProviderType {
        // Obsidian check (YAML frontmatter with tags or wikilinks)
        val hasYamlFrontmatter = text.startsWith("---") && text.indexOf("\n---", 3) != -1
        val hasWikilinks = text.contains("[[") && text.contains("]]")
        val hasObsidianTags = text.contains("#tag/") || text.contains("tags:") || text.contains("aliases:")

        if (hasYamlFrontmatter && (hasObsidianTags || hasWikilinks || fileName.endsWith(".md"))) {
            return ImportProviderType.OBSIDIAN
        }

        // Claude memory markdown export check
        if (text.contains("## Claude Memories") || 
            text.contains("# Claude Memory") || 
            text.contains("## From .claude/memory") || 
            text.contains("[Claude Memory]")) {
            return ImportProviderType.CLAUDE
        }

        // MAX memory markdown projection check
        if (text.startsWith("# MAX Memory") || 
            text.contains("### User Permanent Memory") || 
            text.contains("## Imported Knowledge")) {
            return ImportProviderType.MAX_BACKUP
        }

        // General Markdown detection
        val lines = text.lines()
        val hasHeadings = lines.any { it.trim().startsWith("#") }
        val hasBullets = lines.count { it.trim().startsWith("- ") || it.trim().startsWith("* ") } >= 2

        if (hasHeadings || hasBullets || fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            return ImportProviderType.MARKDOWN
        }

        return ImportProviderType.GENERIC_TEXT
    }
}
