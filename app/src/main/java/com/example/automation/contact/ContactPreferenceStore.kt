package com.example.automation.contact

import android.content.Context
import org.json.JSONObject

/**
 * P1.3 — Contact preference memory.
 *
 * Remembers the contact the user picks when disambiguating an ambiguous query,
 * e.g. query "Ravi" → selected "Ravi Office". Future resolutions of the same
 * query prefer the remembered contact ONLY when that contact is still among
 * the current candidates — genuine ambiguity is never silently resolved.
 *
 * Stored locally; never injected into LLM prompts (privacy by design).
 */
data class ContactPreference(
    val query: String,
    val selectedName: String,
    val selectedPhone: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

class ContactPreferenceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("max_contact_preferences", Context.MODE_PRIVATE)

    companion object {
        /** Contact queries are never persisted in plaintext prompts; keys are normalized. */
        fun normalizeQuery(query: String): String =
            query.trim().lowercase().replace(Regex("\\s+"), " ")

        private fun phoneDigits(raw: String): String = raw.replace(Regex("[^0-9+]"), "")
    }

    /** Remembers the selected contact for a query (last selection wins). */
    fun remember(query: String, selectedName: String, selectedPhone: String = ""): ContactPreference {
        val preference = ContactPreference(
            query = query.trim(),
            selectedName = selectedName.trim(),
            selectedPhone = phoneDigits(selectedPhone)
        )
        val json = JSONObject().apply {
            put("query", preference.query)
            put("selectedName", preference.selectedName)
            put("selectedPhone", preference.selectedPhone)
            put("createdAt", preference.createdAt)
        }
        prefs.edit().putString(normalizeQuery(preference.query), json.toString()).apply()
        return preference
    }

    /** Returns the remembered preference for a query, or null. */
    fun find(query: String): ContactPreference? {
        val raw = prefs.getString(normalizeQuery(query), null) ?: return null
        return try {
            val json = JSONObject(raw)
            ContactPreference(
                query = json.optString("query", query),
                selectedName = json.optString("selectedName", ""),
                selectedPhone = json.optString("selectedPhone", ""),
                createdAt = json.optLong("createdAt", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Forgets a remembered preference. */
    fun forget(query: String) {
        prefs.edit().remove(normalizeQuery(query)).apply()
    }

    fun all(): List<ContactPreference> {
        val result = mutableListOf<ContactPreference>()
        for ((_, value) in prefs.all) {
            if (value is String) {
                try {
                    val json = JSONObject(value)
                    result.add(
                        ContactPreference(
                            query = json.optString("query", ""),
                            selectedName = json.optString("selectedName", ""),
                            selectedPhone = json.optString("selectedPhone", ""),
                            createdAt = json.optLong("createdAt", 0L)
                        )
                    )
                } catch (e: Exception) {
                    // skip corrupt entry
                }
            }
        }
        return result
    }
}
