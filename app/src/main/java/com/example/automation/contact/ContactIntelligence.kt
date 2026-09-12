package com.example.automation.contact

import android.content.Context
import android.util.Log

/**
 * P1.3 — Advanced contact intelligence facade.
 *
 * Extends MAX's existing [ContactResolver] with preference learning. All
 * contact-consuming flows (WhatsApp, calls, SMS, generic contact actions) go
 * through [ContactResolver.resolve], so preference consultation is automatic.
 * This object exposes the "remember the user's disambiguation choice" API.
 *
 * Security/privacy: only a query→contact-name mapping is stored locally. No
 * phone numbers/contact data is ever injected into LLM prompts; resolution is
 * always done locally and only the minimal resolved data reaches the action.
 */
object ContactIntelligence {
    private const val TAG = "ContactIntelligence"

    /** Actions whose paused/ambiguous input is a contact name/number. */
    val CONTACT_ACTIONS = setOf(
        "SEARCH_CONTACT", "CALL_PHONE", "SEND_WHATSAPP", "SEND_WHATSAPP_MESSAGE",
        "OPEN_WHATSAPP_CHAT", "SEND_SMS", "REPLY_NOTIFICATION"
    )

    /**
     * Learns that the user resolved [originalQuery] by choosing [chosenName].
     * Conservative by design:
     *  - only meaningful refinements are stored (query != chosen),
     *  - numeric/direct-phone queries are never stored as ambiguous queries,
     *  - later lookups still require the remembered contact to be a current
     *    candidate, so ambiguity is never resolved silently to a stale pick.
     */
    fun rememberSelection(context: Context, originalQuery: String, chosenName: String): Boolean {
        val query = originalQuery.trim()
        val chosen = chosenName.trim()
        if (query.isEmpty() || chosen.isEmpty()) return false
        if (query.equals(chosen, ignoreCase = true)) return false
        if (ContactResolver.isDirectPhoneNumber(query)) return false

        val store = ContactPreferenceStore(context)
        store.remember(query, chosen)
        Log.d(TAG, "Remembered contact preference: '$query' → '$chosen'")
        return true
    }

    /**
     * Remembers a contact selection when it happens during plan resume
     * (NeedsInput disambiguation). Returns true when a preference was stored.
     */
    fun rememberIfContactSelection(
        context: Context,
        actionType: String,
        originalQuery: String?,
        chosenInput: String
    ): Boolean {
        val actionKey = actionType.uppercase().trim()
        if (actionKey !in CONTACT_ACTIONS) return false
        if (originalQuery.isNullOrBlank() || chosenInput.isBlank()) return false
        return rememberSelection(context, originalQuery, chosenInput)
    }
}
