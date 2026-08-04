package com.example.automation.actions

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.automation.engine.ContactMatch
import com.example.automation.engine.PhoneNumber
import org.json.JSONObject
import org.json.JSONArray

class SearchContactAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        // This is unused because SearchContactTool will bypass the ActionDispatcher
        // and just call a search method directly.
    }

    fun searchContacts(context: Context, query: String): List<ContactMatch> {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            logError("READ_CONTACTS permission is required to search contacts")
            throw SecurityException("READ_CONTACTS permission denied")
        }

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
        val contactDetails = mutableMapOf<Long, Pair<String, String>>() // contactId -> (lookupKey, displayName)

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

        // Convert map to list and sort (exact matches first)
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
