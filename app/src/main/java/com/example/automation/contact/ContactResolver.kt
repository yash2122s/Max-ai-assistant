package com.example.automation.contact

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Contact data model for contact resolution and disambiguation.
 */
data class Contact(
    val name: String,
    val phoneNumber: String,
    val type: String = "Mobile",
    val matchScore: Int = 0
)

/**
 * Result of a contact resolution attempt.
 */
sealed class ContactResolution {
    /** Single unambiguous contact found */
    data class Found(val contact: Contact, val normalizedNumber: String) : ContactResolution()

    /** Multiple potential matching contacts found — requires user selection / disambiguation */
    data class Ambiguous(val query: String, val matches: List<Contact>) : ContactResolution()

    /** No contact found */
    data class NotFound(val query: String) : ContactResolution()
}

/**
 * Canonical ContactResolver for MAX.
 * Resolves contact names, direct numbers, and relationship aliases to normalized E.164 phone numbers.
 */
object ContactResolver {
    private const val TAG = "ContactResolver"

    private val RELATIONSHIP_ALIASES = mapOf(
        "mom" to listOf("mom", "mother", "amma", "mummy", "mommy", "maa"),
        "mother" to listOf("mom", "mother", "amma", "mummy", "mommy", "maa"),
        "amma" to listOf("mom", "mother", "amma", "mummy", "mommy", "maa"),
        "mummy" to listOf("mom", "mother", "amma", "mummy", "mommy", "maa"),
        "dad" to listOf("dad", "father", "nanna", "daddy", "papa", "appa"),
        "father" to listOf("dad", "father", "nanna", "daddy", "papa", "appa"),
        "nanna" to listOf("dad", "father", "nanna", "daddy", "papa", "appa"),
        "daddy" to listOf("dad", "father", "nanna", "daddy", "papa", "appa"),
        "wife" to listOf("wife", "wifey", "bhaarya"),
        "husband" to listOf("husband", "hubby", "bharta"),
        "brother" to listOf("brother", "anna", "tammudu", "bro"),
        "anna" to listOf("brother", "anna", "tammudu", "bro"),
        "bro" to listOf("brother", "anna", "tammudu", "bro"),
        "sister" to listOf("sister", "akka", "chelli", "sis"),
        "akka" to listOf("sister", "akka", "chelli", "sis"),
        "sis" to listOf("sister", "akka", "chelli", "sis")
    )

    /**
     * Resolves an input string (contact name, phone number, or relationship) to a ContactResolution result.
     *
     * P1.3: when the raw resolution is ambiguous, a remembered contact
     * preference (user previously picked "Ravi Office" for "Ravi") is honored
     * ONLY if that contact is still among the current candidates. Otherwise the
     * ambiguity is preserved and the user is asked again — a remembered
     * preference never silently picks a stale/unsafe contact.
     */
    fun resolve(context: Context, input: String): ContactResolution {
        val result = resolveCore(context, input)
        val preference = runCatching { ContactPreferenceStore(context).find(input) }.getOrNull()
        return preferRememberedIfCandidate(result, preference)
    }

    /**
     * Pure P1.3 preference logic: when the raw resolution is ambiguous, the
     * remembered contact is honored ONLY if it is still among the current
     * candidates. Otherwise the ambiguity is preserved and the user is asked
     * again — a remembered preference never silently picks a stale contact.
     */
    fun preferRememberedIfCandidate(result: ContactResolution, preference: ContactPreference?): ContactResolution {
        if (result !is ContactResolution.Ambiguous) return result
        if (result.matches.size <= 1) return result
        if (preference == null || preference.selectedName.isBlank()) return result

        val preferred = result.matches.firstOrNull { candidate ->
            candidate.name.equals(preference.selectedName, ignoreCase = true) ||
                normalizePhoneNumber(candidate.phoneNumber) == preference.selectedPhone.replace("+", "")
        }
        if (preferred != null) {
            Log.d(TAG, "Preferred remembered contact '${preferred.name}' (from preference).")
            return ContactResolution.Found(preferred, normalizeToE164(preferred.phoneNumber))
        }
        Log.d(TAG, "Remembered contact not among current candidates — asking again.")
        return result
    }

    /**
     * Core resolution (exact name → direct number → relationship alias →
     * token match → prefix match → substring match). No preference handling.
     */
    private fun resolveCore(context: Context, input: String): ContactResolution {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ContactResolution.NotFound(trimmed)
        }

        // 1. Direct phone number check
        if (isDirectPhoneNumber(trimmed)) {
            val normalized = normalizeToE164(trimmed)
            return ContactResolution.Found(
                contact = Contact(name = trimmed, phoneNumber = normalized, type = "Direct"),
                normalizedNumber = normalized
            )
        }

        // 2. Permission check
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted. Cannot query ContactsContract.")
            return ContactResolution.NotFound(trimmed)
        }

        val allContacts = loadAllContacts(context)
        if (allContacts.isEmpty()) {
            return ContactResolution.NotFound(trimmed)
        }

        val normalizedQuery = trimmed.lowercase(Locale.ROOT)

        // 3-6. Pure name matching (exact → alias → token → prefix → substring)
        return matchByName(normalizedQuery, allContacts, trimmed)
    }

    /**
     * Pure contact-name matching used by the resolver (and unit tests).
     * Stages, in order: exact (case-insensitive) → relationship alias → token
     * match (all query words) → word/prefix match → substring fallback.
     * Each stage returns a unique Found, an Ambiguous set, or falls through.
     */
    fun matchByName(normalizedQuery: String, contacts: List<Contact>, originalQuery: String = normalizedQuery): ContactResolution {
        val query = normalizedQuery.lowercase(Locale.ROOT)
        if (contacts.isEmpty()) return ContactResolution.NotFound(originalQuery)

        fun uniqueByNumber(matches: List<Contact>): List<Contact> =
            matches.distinctBy { normalizePhoneNumber(it.phoneNumber) }

        fun resolved(matches: List<Contact>): ContactResolution {
            val unique = uniqueByNumber(matches)
            return when (unique.size) {
                0 -> ContactResolution.NotFound(originalQuery)
                1 -> ContactResolution.Found(unique.first(), normalizeToE164(unique.first().phoneNumber))
                else -> ContactResolution.Ambiguous(originalQuery, unique)
            }
        }

        // 3. Exact contact name match (case-insensitive)
        val exactMatches = contacts.filter { it.name.trim().lowercase(Locale.ROOT) == query }
        if (exactMatches.isNotEmpty()) return resolved(exactMatches)

        // 4. Relationship alias match
        val aliasKeywords = RELATIONSHIP_ALIASES[query]
        if (aliasKeywords != null) {
            val relationshipMatches = contacts.filter { contact ->
                val nameLower = contact.name.lowercase(Locale.ROOT)
                aliasKeywords.any { alias ->
                    nameLower == alias || nameLower.split("\\s+".toRegex()).contains(alias)
                }
            }
            if (relationshipMatches.isNotEmpty()) return resolved(relationshipMatches)
        }

        // 4b. Token match (all query words match contact words as prefixes/words)
        val tokens = query.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.size > 1) {
            val tokenMatches = contacts.filter { contact ->
                val words = contact.name.lowercase(Locale.ROOT).split("\\s+".toRegex())
                tokens.all { token -> words.any { it == token || it.startsWith(token) } }
            }
            if (tokenMatches.isNotEmpty()) return resolved(tokenMatches)
        }

        // 5. Word boundary / Prefix match
        val prefixMatches = contacts.filter { contact ->
            val nameLower = contact.name.lowercase(Locale.ROOT)
            nameLower.startsWith(query) ||
                nameLower.split("\\s+".toRegex()).any { it.startsWith(query) }
        }
        if (prefixMatches.isNotEmpty()) return resolved(prefixMatches)

        // 6. Substring match fallback
        val substringMatches = contacts.filter { contact ->
            contact.name.lowercase(Locale.ROOT).contains(query)
        }
        if (substringMatches.isNotEmpty()) return resolved(substringMatches)

        return ContactResolution.NotFound(originalQuery)
    }

    /**
     * Checks if a string looks like a direct phone number (7-18 digits, optional leading plus/spaces/hyphens).
     */
    fun isDirectPhoneNumber(input: String): Boolean {
        val cleaned = input.trim()
        val digitsOnly = cleaned.replace(Regex("[^0-9]"), "")
        val hasValidChars = cleaned.matches(Regex("^[+]?[0-9\\s\\-().]{7,20}$"))
        return hasValidChars && digitsOnly.length in 7..18
    }

    /**
     * Normalizes a phone number to standard E.164 string format (+countryCode...).
     * Defaults to Indian +91 if 10 digits are provided without international prefix.
     */
    fun normalizeToE164(raw: String): String {
        val clean = raw.replace(Regex("[^0-9+]"), "")
        if (clean.startsWith("+")) {
            return clean
        }
        val digits = clean.replace("+", "")
        return if (digits.length == 10) {
            "+91$digits"
        } else if (digits.startsWith("0") && digits.length == 11) {
            "+91${digits.substring(1)}"
        } else if (digits.startsWith("91") && digits.length == 12) {
            "+$digits"
        } else {
            "+$digits"
        }
    }

    /**
     * Extracts pure numeric digits suitable for WhatsApp URL parameter (e.g. 919876543210).
     */
    fun toWhatsAppUrlPhone(e164Number: String): String {
        return e164Number.replace("+", "").replace(Regex("[^0-9]"), "")
    }

    private fun normalizePhoneNumber(raw: String): String {
        return raw.replace(Regex("[^0-9+]"), "")
    }

    private fun loadAllContacts(context: Context): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.let {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (it.moveToNext()) {
                    val name = if (nameIndex >= 0) it.getString(nameIndex) ?: "" else ""
                    val number = if (numberIndex >= 0) it.getString(numberIndex) ?: "" else ""
                    val typeInt = if (typeIndex >= 0) it.getInt(typeIndex) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE

                    val typeStr = when (typeInt) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        else -> "Other"
                    }

                    if (name.isNotBlank() && number.isNotBlank()) {
                        contacts.add(Contact(name = name, phoneNumber = number, type = typeStr))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts from device", e)
        } finally {
            cursor?.close()
        }

        return contacts
    }

    /**
     * Checks if an incoming sender (e.g. from a WhatsApp notification) matches a selected contact entry.
     * Compares case-insensitive name matching, as well as normalized phone number digits (min 7 digits).
     * Fails closed on blank/empty parameters.
     */
    fun matchesSelectedContact(incomingSender: String, selectedContact: String): Boolean {
        val s1 = incomingSender.trim()
        val s2 = selectedContact.trim()
        if (s1.isEmpty() || s2.isEmpty()) return false

        if (s1.equals(s2, ignoreCase = true)) return true

        val digits1 = s1.filter { it.isDigit() }
        val digits2 = s2.filter { it.isDigit() }
        if (digits1.length >= 7 && digits2.length >= 7) {
            if (digits1 == digits2 || digits1.endsWith(digits2) || digits2.endsWith(digits1)) {
                return true
            }
        }
        return false
    }

    /**
     * Fail-closed check verifying if an incoming sender matches any contact in a selected whitelist set.
     */
    fun isSenderInSelectedContacts(sender: String, selectedContacts: Set<String>): Boolean {
        if (sender.isBlank() || selectedContacts.isEmpty()) return false
        return selectedContacts.any { matchesSelectedContact(sender, it) }
    }

    /**
     * Public accessor to load sorted contacts from the device phonebook.
     */
    fun getDeviceContacts(context: Context): List<Contact> {
        return loadAllContacts(context)
    }
}

