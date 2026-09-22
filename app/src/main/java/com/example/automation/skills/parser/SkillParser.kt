package com.example.automation.skills.parser

import com.example.automation.skills.model.SkillDefinition
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Result returned by the SkillParser.
 */
data class SkillParseResult(
    val success: Boolean,
    val skill: SkillDefinition? = null,
    val documentationMarkdown: String = "",
    val errorMessage: String? = null
)

/**
 * Deterministic Parser for MAX Automation Skills in .json and .md (YAML frontmatter) formats.
 * Enforces size limits, safe parsing, and strict separation between structured fields and Markdown body.
 */
object SkillParser {

    const val MAX_FILE_SIZE_BYTES = 512 * 1024 // 512 KB
    private val gson: Gson = GsonBuilder().create()

    /**
     * Parses a raw skill string (either JSON or Markdown with YAML frontmatter).
     */
    fun parse(rawContent: String): SkillParseResult {
        if (rawContent.isEmpty()) {
            return SkillParseResult(success = false, errorMessage = "Skill content is empty.")
        }

        if (rawContent.toByteArray(Charsets.UTF_8).size > MAX_FILE_SIZE_BYTES) {
            return SkillParseResult(success = false, errorMessage = "Skill file exceeds maximum allowed size (512KB).")
        }

        val trimmed = rawContent.trimStart('\uFEFF').trim() // Strip UTF-8 BOM if present

        return if (trimmed.startsWith("---")) {
            parseMarkdownSkill(trimmed)
        } else if (trimmed.startsWith("{")) {
            parseJsonSkill(trimmed)
        } else {
            SkillParseResult(
                success = false,
                errorMessage = "Unsupported skill format. File must start with '---' (YAML frontmatter) or '{' (JSON)."
            )
        }
    }

    private fun parseJsonSkill(jsonString: String): SkillParseResult {
        return try {
            val jsonElement = JsonParser.parseString(jsonString)
            if (!jsonElement.isJsonObject) {
                return SkillParseResult(success = false, errorMessage = "Root of JSON skill must be a JSON object.")
            }
            val skill = gson.fromJson(jsonElement, SkillDefinition::class.java)
            if (skill == null || skill.skillId.isNullOrBlank()) {
                return SkillParseResult(success = false, errorMessage = "Parsed skill is missing mandatory skill_id.")
            }
            SkillParseResult(success = true, skill = skill, documentationMarkdown = "")
        } catch (e: Exception) {
            SkillParseResult(success = false, errorMessage = "JSON parsing error: ${e.message}")
        }
    }

    private fun parseMarkdownSkill(markdownString: String): SkillParseResult {
        val parts = markdownString.split("---", limit = 3)
        if (parts.size < 3) {
            return SkillParseResult(
                success = false,
                errorMessage = "Invalid Markdown frontmatter. Expected closing '---' delimiter."
            )
        }

        val frontmatterContent = parts[1].trim()
        val bodyContent = parts[2].trim()

        if (frontmatterContent.isEmpty()) {
            return SkillParseResult(success = false, errorMessage = "YAML frontmatter block is empty.")
        }

        return try {
            val jsonObject = YamlToJsonConverter.convert(frontmatterContent)
            val skill = gson.fromJson(jsonObject, SkillDefinition::class.java)
            if (skill == null || skill.skillId.isNullOrBlank()) {
                return SkillParseResult(success = false, errorMessage = "Parsed skill is missing mandatory skill_id.")
            }
            SkillParseResult(
                success = true,
                skill = skill,
                documentationMarkdown = bodyContent
            )
        } catch (e: Exception) {
            SkillParseResult(success = false, errorMessage = "Frontmatter YAML parse error: ${e.message}")
        }
    }
}

/**
 * Lightweight, safe YAML-to-JSON converter for structured skill frontmatter.
 * Rejects unsafe anchors/aliases and converts indented YAML into a GSON JsonObject.
 */
internal object YamlToJsonConverter {

    fun convert(yamlString: String): JsonObject {
        val lines = yamlString.lines()
        val cleanedLines = lines.map { raw ->
            val trimmed = raw.trim()
            val indent = if (trimmed.isEmpty() || trimmed.startsWith("#")) -1 else raw.indexOfFirst { !it.isWhitespace() }
            LineInfo(indent, raw, trimmed)
        }
        val (parsedMap, _) = parseMapping(cleanedLines, 0, 0)
        return JsonParser.parseString(Gson().toJson(parsedMap)).asJsonObject
    }

    private data class LineInfo(val indent: Int, val raw: String, val trimmed: String)

    private fun parseMapping(lines: List<LineInfo>, startIdx: Int, minIndent: Int): Pair<Map<String, Any?>, Int> {
        val result = mutableMapOf<String, Any?>()
        var i = startIdx

        while (i < lines.size) {
            val line = lines[i]
            if (line.indent == -1) {
                i++
                continue
            }
            if (line.indent < minIndent) {
                break
            }
            if (line.trimmed.startsWith("-")) {
                break
            }

            val colonIdx = line.trimmed.indexOf(':')
            if (colonIdx == -1) {
                i++
                continue
            }

            val key = line.trimmed.substring(0, colonIdx).trim()
            val afterColon = line.trimmed.substring(colonIdx + 1).trim()

            if (afterColon.isNotEmpty()) {
                result[key] = parseScalarOrInline(afterColon)
                i++
            } else {
                var nextNonEmptyIdx = i + 1
                while (nextNonEmptyIdx < lines.size && lines[nextNonEmptyIdx].indent == -1) {
                    nextNonEmptyIdx++
                }

                if (nextNonEmptyIdx >= lines.size || lines[nextNonEmptyIdx].indent <= line.indent) {
                    result[key] = null
                    i = nextNonEmptyIdx
                } else {
                    val childIndent = lines[nextNonEmptyIdx].indent
                    if (lines[nextNonEmptyIdx].trimmed.startsWith("-")) {
                        val (childList, nextI) = parseList(lines, nextNonEmptyIdx, childIndent)
                        result[key] = childList
                        i = nextI
                    } else {
                        val (childMap, nextI) = parseMapping(lines, nextNonEmptyIdx, childIndent)
                        result[key] = childMap
                        i = nextI
                    }
                }
            }
        }

        return Pair(result, i)
    }

    private fun parseList(lines: List<LineInfo>, startIdx: Int, listIndent: Int): Pair<List<Any?>, Int> {
        val result = mutableListOf<Any?>()
        var i = startIdx

        while (i < lines.size) {
            val line = lines[i]
            if (line.indent == -1) {
                i++
                continue
            }
            if (line.indent < listIndent) {
                break
            }
            if (line.indent > listIndent && !line.trimmed.startsWith("-")) {
                break
            }
            if (!line.trimmed.startsWith("-")) {
                break
            }

            val afterDash = line.trimmed.substring(1).trim()
            if (afterDash.isEmpty()) {
                var nextNonEmptyIdx = i + 1
                while (nextNonEmptyIdx < lines.size && lines[nextNonEmptyIdx].indent == -1) {
                    nextNonEmptyIdx++
                }
                if (nextNonEmptyIdx >= lines.size || lines[nextNonEmptyIdx].indent <= line.indent) {
                    result.add(null)
                    i = nextNonEmptyIdx
                } else {
                    val childIndent = lines[nextNonEmptyIdx].indent
                    if (lines[nextNonEmptyIdx].trimmed.startsWith("-")) {
                        val (childList, nextI) = parseList(lines, nextNonEmptyIdx, childIndent)
                        result.add(childList)
                        i = nextI
                    } else {
                        val (childMap, nextI) = parseMapping(lines, nextNonEmptyIdx, childIndent)
                        result.add(childMap)
                        i = nextI
                    }
                }
            } else if (afterDash.contains(":") && !afterDash.startsWith("{") && !afterDash.startsWith("[")) {
                val objLines = mutableListOf<LineInfo>()
                val syntheticIndent = listIndent + 2
                objLines.add(LineInfo(syntheticIndent, " ".repeat(syntheticIndent) + afterDash, afterDash))
                var j = i + 1
                while (j < lines.size) {
                    val nextLine = lines[j]
                    if (nextLine.indent == -1) {
                        objLines.add(nextLine)
                        j++
                        continue
                    }
                    if (nextLine.indent < syntheticIndent) {
                        break
                    }
                    if (nextLine.indent == listIndent && nextLine.trimmed.startsWith("-")) {
                        break
                    }
                    objLines.add(nextLine)
                    j++
                }
                val (itemMap, _) = parseMapping(objLines, 0, syntheticIndent)
                result.add(itemMap)
                i = j
            } else {
                result.add(parseScalarOrInline(afterDash))
                i++
            }
        }

        return Pair(result, i)
    }

    private fun parseScalarOrInline(value: String): Any? {
        val trimmed = value.trim()
        if (trimmed == "null" || trimmed == "~" || trimmed.isEmpty()) return null
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false

        // Inline array: [a, b, c]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return emptyList<Any>()
            return inner.split(",").map { parseScalarOrInline(it.trim()) }
        }

        // Inline empty map: {}
        if (trimmed == "{}") {
            return emptyMap<String, Any>()
        }

        // Numbers
        trimmed.toLongOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { return it }

        // Quoted string
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length - 1)
        }

        return trimmed
    }
}
