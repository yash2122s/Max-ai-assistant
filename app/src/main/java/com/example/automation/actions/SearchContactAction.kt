package com.example.automation.actions

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.automation.engine.ContactMatch
import com.example.automation.engine.PhoneNumber
import org.json.JSONObject

object ContactAliasResolver {
    val RELATIONSHIP_ALIASES: Map<String, List<String>> = mapOf(
        "నాన్న" to listOf("Dad", "Daddy", "Father", "Papa", "Nanna"),
        "నాన్నకు" to listOf("Dad", "Daddy", "Father", "Papa", "Nanna"),
        "డాడీ" to listOf("Daddy", "Dad", "Father", "Papa"),
        "డాడీకి" to listOf("Daddy", "Dad", "Father", "Papa"),
        "అమ్మ" to listOf("Mom", "Mummy", "Mother", "Amma"),
        "అమ్మకు" to listOf("Mom", "Mummy", "Mother", "Amma"),
        "అమ్మకి" to listOf("Mom", "Mummy", "Mother", "Amma"),
        "మమ్మీ" to listOf("Mummy", "Mom", "Mother", "Amma"),
        "మమ్మీకి" to listOf("Mummy", "Mom", "Mother", "Amma"),
        "అన్న" to listOf("Brother", "Anna"),
        "అన్నయ్య" to listOf("Brother", "Anna", "Annayya"),
        "అన్నయ్యకు" to listOf("Brother", "Anna", "Annayya"),
        "తమ్ముడు" to listOf("Brother", "Thammudu"),
        "తమ్ముడికి" to listOf("Brother", "Thammudu"),
        "అక్క" to listOf("Sister", "Akka"),
        "అక్కకు" to listOf("Sister", "Akka"),
        "అక్కకి" to listOf("Sister", "Akka"),
        "చెల్లి" to listOf("Sister", "Chelli"),
        "చెల్లెలు" to listOf("Sister", "Chelli", "Chellelu"),
        "భార్య" to listOf("Wife", "Honey"),
        "భర్త" to listOf("Husband"),
        "ఫ్రెండ్" to listOf("Friend"),
        "dad" to listOf("Daddy", "Father", "Papa", "Nanna"),
        "daddy" to listOf("Dad", "Father", "Papa", "Nanna"),
        "mom" to listOf("Mummy", "Mother", "Amma"),
        "mummy" to listOf("Mom", "Mother", "Amma"),
        "mother" to listOf("Mom", "Mummy", "Amma"),
        "father" to listOf("Dad", "Daddy", "Papa", "Nanna"),
        "brother" to listOf("Anna", "Thammudu"),
        "sister" to listOf("Akka", "Chelli")
    )

    fun cleanQuery(raw: String): String {
        return raw.trim()
            .replace(Regex("^(?:to|the|call|dial|ఫోన్|కాల్)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?:కి|కు|గారికి|గారు)$"), "")
            .trim()
    }

    fun getCandidateQueries(rawQuery: String): List<String> {
        val candidates = mutableListOf<String>()
        val cleaned = cleanQuery(rawQuery)
        if (cleaned.isNotBlank()) {
            candidates.add(cleaned)
        }
        if (rawQuery.trim() != cleaned && rawQuery.isNotBlank()) {
            candidates.add(rawQuery.trim())
        }

        val aliases = RELATIONSHIP_ALIASES[cleaned.lowercase()] ?: RELATIONSHIP_ALIASES[rawQuery.trim().lowercase()]
        if (aliases != null) {
            candidates.addAll(aliases)
        }

        return candidates.distinct()
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

        val candidates = ContactAliasResolver.getCandidateQueries(query)
        for (candidate in candidates) {
            val results = queryContactsProvider(context, candidate)
            if (results.isNotEmpty()) {
                return results
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
            "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC"
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
