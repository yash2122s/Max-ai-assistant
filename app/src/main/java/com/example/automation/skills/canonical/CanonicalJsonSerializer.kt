package com.example.automation.skills.canonical

import com.example.automation.skills.model.SkillDefinition
import com.google.gson.*
import java.security.MessageDigest

/**
 * Deterministic Canonical JSON Serializer & SHA-256 Checksum Engine.
 * Produces byte-equivalent canonical JSON representations across Kotlin and Python.
 */
object CanonicalJsonSerializer {

    private val gson: Gson = GsonBuilder().create()

    /**
     * Serializes a SkillDefinition into a deterministic canonical JSON string:
     * - Strips `checksum` and `signature`
     * - Recursively sorts all JSON object keys lexicographically
     * - Formats compact JSON with no insignificant whitespace
     * - Normalizes string line endings to \n
     */
    fun toCanonicalJson(skill: SkillDefinition): String {
        val rawElement = gson.toJsonTree(skill)
        val stripped = stripChecksumAndSignature(rawElement)
        val sorted = canonicalizeJsonElement(stripped)
        return renderCompactJson(sorted)
    }

    /**
     * Computes the SHA-256 checksum over the canonical JSON bytes.
     */
    fun computeChecksum(skill: SkillDefinition): String {
        val canonicalJson = toCanonicalJson(skill)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(canonicalJson.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies that the skill's declared checksum matches its computed canonical SHA-256.
     * Returns true if valid or if declared checksum is empty (unsigned), false if mismatched.
     */
    fun verifyChecksum(skill: SkillDefinition): Boolean {
        val checksum = skill.checksum ?: ""
        if (checksum.isBlank()) {
            return true
        }
        val computed = computeChecksum(skill)
        return checksum.equals(computed, ignoreCase = true)
    }

    private fun stripChecksumAndSignature(element: JsonElement): JsonElement {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val copy = JsonObject()
            for ((key, value) in obj.entrySet()) {
                if (key != "checksum" && key != "signature") {
                    copy.add(key, stripChecksumAndSignature(value))
                }
            }
            return copy
        } else if (element.isJsonArray) {
            val array = element.asJsonArray
            val copy = JsonArray()
            for (item in array) {
                copy.add(stripChecksumAndSignature(item))
            }
            return copy
        }
        return element
    }

    private fun canonicalizeJsonElement(element: JsonElement): JsonElement {
        return when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val sortedObj = JsonObject()
                // Sort keys lexicographically
                val sortedKeys = obj.keySet().sorted()
                for (key in sortedKeys) {
                    val value = obj.get(key)
                    if (value != null && !value.isJsonNull) {
                        sortedObj.add(key, canonicalizeJsonElement(value))
                    }
                }
                sortedObj
            }
            element.isJsonArray -> {
                val array = element.asJsonArray
                val sortedArray = JsonArray()
                for (item in array) {
                    sortedArray.add(canonicalizeJsonElement(item))
                }
                sortedArray
            }
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                if (prim.isString) {
                    // Normalize line endings to \n
                    val normalized = prim.asString.replace("\r\n", "\n").replace("\r", "\n")
                    JsonPrimitive(normalized)
                } else {
                    prim
                }
            }
            else -> element
        }
    }

    private fun renderCompactJson(element: JsonElement): String {
        return when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val entries = obj.entrySet().map { (k, v) ->
                    "\"$k\":${renderCompactJson(v)}"
                }
                "{${entries.joinToString(",")}}"
            }
            element.isJsonArray -> {
                val array = element.asJsonArray
                val items = array.map { renderCompactJson(it) }
                "[${items.joinToString(",")}]"
            }
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                when {
                    prim.isString -> gson.toJson(prim.asString)
                    prim.isNumber -> {
                        val num = prim.asNumber
                        if (num.toDouble() == num.toLong().toDouble()) {
                            num.toLong().toString()
                        } else {
                            num.toString()
                        }
                    }
                    prim.isBoolean -> prim.asBoolean.toString()
                    else -> prim.toString()
                }
            }
            element.isJsonNull -> "null"
            else -> element.toString()
        }
    }
}
