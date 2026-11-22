package com.example.automation.actions

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.automation.engine.ContactMatch
import com.example.automation.engine.PhoneNumber
import org.json.JSONObject

object ContactAliasResolver {
    // Canonical symmetric alias groups (lowercased canonical terms)
    // "appa", "maa", and "friend" are removed per instructions.
    // Private aliases from ContactResolver.kt (bhaarya, bharta, bro, sis) are merged. "honey", "wifey", "hubby" pruned per instructions.
    val ALIAS_GROUPS: List<List<String>> = listOf(
        // Mother
        listOf("mom", "mummy", "mother", "amma", "mommy", "అమ్మ", "అమ్మా", "మమ్మీ", "Mom", "Mummy", "Mother", "Amma"),
        // Father
        listOf("dad", "daddy", "father", "papa", "nanna", "నాన్న", "డాడీ", "Dad", "Daddy", "Father", "Papa", "Nanna"),
        // Brother
        listOf("brother", "anna", "annayya", "thammudu", "tammudu", "bro", "అన్న", "అన్నయ్య", "తమ్ముడు", "Brother", "Anna", "Annayya", "Thammudu"),
        // Sister
        listOf("sister", "akka", "chelli", "chellelu", "sis", "అక్క", "చెల్లి", "చెల్లెలు", "Sister", "Akka", "Chelli", "Chellelu"),
        // Spouse
        listOf("wife", "bhaarya", "bharya", "భార్య", "Wife"),
        listOf("husband", "bharta", "భర్త", "Husband")
    )

    // Symmetric lookup map: every lowercase variant maps to the unique list of terms in its group
    val SYMMETRIC_ALIAS_MAP: Map<String, List<String>> = buildMap {
        for (group in ALIAS_GROUPS) {
            val distinctTerms = group.distinct()
            for (term in group) {
                put(term.lowercase(), distinctTerms)
            }
        }
    }

    // Retain backward-compatible map
    val RELATIONSHIP_ALIASES: Map<String, List<String>> get() = SYMMETRIC_ALIAS_MAP

    /** Strips call prefixes and suffixes: "call", "dial", "to", "the", "ఫోన్", "కాల్", "call cheyyi", etc. */
    fun stripCallPrefixes(raw: String): String {
        return raw.trim()
            .replace(Regex("^(?:to|the|call|dial|ఫోన్|కాల్)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:call|dial|call\\s*chey|call\\s*cheyyi|call\\s*cheyandi|కాల్\\s*చేయి|కాల్\\s*చెయ్యి|కాల్|ఫోన్\\s*చేయి|ఫోన్)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /** Strips possessive prefixes: "my", "our", "నా", "మా" (requires trailing whitespace to preserve words like "నాన్న") */
    fun stripPossessivePrefixes(raw: String): String {
        return raw.trim()
            .replace(Regex("^(?:my|our|నా|మా)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /** Strips Telugu case markers in Telugu script and common Latin transliterations (గారికి, గారు, తో, కి, కు, ను, ki, ku, tho, to, garu) */
    fun stripTeluguCaseSuffix(raw: String): String {
        return raw.trim()
            .replace(Regex("(?:గారికి|గారు|తో|కి|కు|ను)$"), "")
            .replace(Regex("\\s+(?:gariki|garu|tho|to|ki|ku|nu)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /** Normalizes a query for alias group lookup (strips call verbs, possessives, and case suffixes) */
    fun normalizeForAliasLookup(raw: String): String {
        val noCall = stripCallPrefixes(raw)
        val noPossessive = stripPossessivePrefixes(noCall)
        val noSuffix = stripTeluguCaseSuffix(noPossessive)
        return noSuffix.trim().lowercase()
    }

    /** Returns all alias terms for a query, or empty set if no matching group exists */
    fun getAliasGroupTerms(query: String): Set<String> {
        val normalized = normalizeForAliasLookup(query)
        val direct = SYMMETRIC_ALIAS_MAP[normalized]
        if (direct != null) return direct.toSet()

        // Also check raw stripped of just call prefixes
        val rawClean = stripCallPrefixes(query).lowercase()
        return SYMMETRIC_ALIAS_MAP[rawClean]?.toSet() ?: emptySet()
    }

    /** Alias lookup helper returning a List or null */
    fun getAliasesFor(query: String): List<String>? {
        val terms = getAliasGroupTerms(query)
        return if (terms.isNotEmpty()) terms.toList() else null
    }

    /** Backward-compatible candidate queries generator. */
    fun getCandidateQueries(rawQuery: String): List<String> {
        val candidates = mutableListOf<String>()
        val cleaned = cleanQuery(rawQuery)
        if (cleaned.isNotBlank()) {
            candidates.add(cleaned)
        }
        val rawTrim = rawQuery.trim()
        if (rawTrim != cleaned && rawTrim.isNotBlank()) {
            candidates.add(rawTrim)
        }

        val groupTerms = getAliasGroupTerms(rawQuery)
        candidates.addAll(groupTerms)

        return candidates.distinct()
    }

    fun cleanQuery(raw: String): String {
        val withoutCall = stripCallPrefixes(raw)
        val withoutSuffix = stripTeluguCaseSuffix(withoutCall)
        return withoutSuffix.trim()
    }

    /**
     * Pure exact-match ranking / filtering.
     * If any result's normalized display name equals the query or a group term exactly,
     * return only exact matches; otherwise return all (for disambiguation).
     */
    fun filterExactMatches(candidates: List<ContactMatch>, targetTerms: Set<String>): List<ContactMatch> {
        if (candidates.isEmpty() || targetTerms.isEmpty()) return candidates
        val normalizedTerms = targetTerms.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val exact = candidates.filter { match ->
            match.displayName.trim().lowercase() in normalizedTerms
        }
        return if (exact.isNotEmpty()) exact else candidates
    }
}

class SearchContactAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        // Unused because SearchContactTool bypasses ActionDispatcher
    }

    fun searchContacts(context: Context, query: String): List<ContactMatch> {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            logError("READ_CONTACTS permission is required to search contacts")
            throw SecurityException("READ_CONTACTS permission denied")
        }

        val rawCleaned = ContactAliasResolver.stripCallPrefixes(query)
        if (rawCleaned.isBlank()) return emptyList()

        // Stage 1: Literal spoken name search
        var literalHits = queryContactsProvider(context, rawCleaned)
        val suffixStripped = ContactAliasResolver.stripTeluguCaseSuffix(rawCleaned)
        if (literalHits.isEmpty() && suffixStripped != rawCleaned && suffixStripped.isNotBlank()) {
            literalHits = queryContactsProvider(context, suffixStripped)
        }

        if (literalHits.isNotEmpty()) {
            val termsToCheck = setOf(rawCleaned, suffixStripped)
            return ContactAliasResolver.filterExactMatches(literalHits, termsToCheck)
        }

        // Stage 2: Alias group union search
        val groupTerms = ContactAliasResolver.getAliasGroupTerms(rawCleaned)
        if (groupTerms.isNotEmpty()) {
            val unionMap = mutableMapOf<Long, ContactMatch>()
            for (term in groupTerms) {
                val hits = queryContactsProvider(context, term)
                for (hit in hits) {
                    val existing = unionMap[hit.contactId]
                    if (existing == null) {
                        unionMap[hit.contactId] = hit
                    } else {
                        val combinedPhones = (existing.phoneNumbers + hit.phoneNumbers)
                            .distinctBy { it.normalizedNumber }
                        unionMap[hit.contactId] = existing.copy(phoneNumbers = combinedPhones)
                    }
                }
            }
            val unionList = unionMap.values.toList()
            if (unionList.isNotEmpty()) {
                return ContactAliasResolver.filterExactMatches(unionList, groupTerms + setOf(rawCleaned, suffixStripped))
            }
        }

        return emptyList()
    }

    private fun queryContactsProvider(context: Context, query: String): List<ContactMatch> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )

        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            android.net.Uri.encode(query)
        )

        val matchesMap = mutableMapOf<Long, MutableList<PhoneNumber>>()
        val contactDetails = mutableMapOf<Long, Pair<String, String>>()

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val lookupIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val phoneIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            val labelIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(idIndex)
                val lookupKey = cursor.getString(lookupIndex) ?: ""
                val displayName = cursor.getString(nameIndex) ?: ""
                val phoneId = cursor.getLong(phoneIdIndex)
                val rawNumber = cursor.getString(numberIndex) ?: ""
                val type = cursor.getInt(typeIndex)
                var label = cursor.getString(labelIndex) ?: ""

                if (label.isEmpty()) {
                    label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, "").toString()
                }
                
                val normalized = android.telephony.PhoneNumberUtils.normalizeNumber(rawNumber) ?: ""
                if (normalized.isNotEmpty()) {
                    contactDetails[contactId] = Pair(lookupKey, displayName)
                    val list = matchesMap.getOrPut(contactId) { mutableListOf() }
                    if (list.none { it.normalizedNumber == normalized }) {
                        list.add(PhoneNumber(phoneId, label, normalized))
                    }
                }
            }
        }

        return contactDetails.map { (contactId, details) ->
            ContactMatch(
                contactId = contactId,
                lookupKey = details.first,
                displayName = details.second,
                phoneNumbers = matchesMap[contactId] ?: emptyList()
            )
        }.sortedBy { if (it.displayName.equals(query, ignoreCase = true)) 0 else 1 }
    }
}
