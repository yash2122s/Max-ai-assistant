package com.example.automation.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject

class CallPhoneAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        var contactId = payload.optLong("contactId", -1L)
        var phoneId = payload.optLong("phoneId", -1L)
        
        // Try to resolve missing IDs from local text payload (e.g., from ChatViewModel interception)
        if (contactId == -1L || phoneId == -1L) {
            val contactName = payload.optString("contact").trim()
            if (contactName.isNotEmpty()) {
                val cleanContact = contactName.replace(Regex("[\\s\\-()]"), "")
                val isRawNumber = cleanContact.matches(Regex("""\+?\d{7,15}"""))
                if (isRawNumber) {
                    val normalized = android.telephony.PhoneNumberUtils.normalizeNumber(contactName) ?: contactName
                    val syntheticMatch = com.example.automation.engine.ContactMatch(
                        contactId = -999L,
                        lookupKey = "raw_number:$normalized",
                        displayName = "Raw Number",
                        phoneNumbers = listOf(
                            com.example.automation.engine.PhoneNumber(
                                id = -999L,
                                label = "Number",
                                normalizedNumber = normalized
                            )
                        )
                    )
                    com.example.automation.engine.PendingCallManager.setAwaitingContactSelection(listOf(syntheticMatch))
                    com.example.automation.engine.PendingCallManager.isCallPermitted(-999L, -999L)
                    contactId = -999L
                    phoneId = -999L
                } else {
                    try {
                        val matches = SearchContactAction().searchContacts(context, contactName)
                        val match = matches.firstOrNull()
                        val phone = match?.phoneNumbers?.firstOrNull()
                        if (match != null && phone != null) {
                            com.example.automation.engine.PendingCallManager.setAwaitingContactSelection(matches)
                            com.example.automation.engine.PendingCallManager.isCallPermitted(match.contactId, phone.id)
                            contactId = match.contactId
                            phoneId = phone.id
                        }
                    } catch (e: Exception) {
                        logError("Failed to search contact locally during resolution", e)
                    }
                }
            }
        }
        
        if (contactId == -1L || phoneId == -1L) {
            val msg = "Missing contactId or phoneId for CALL_PHONE. Call aborted."
            logError(msg)
            throw IllegalArgumentException(msg)
        }

        // Enforce state machine rules
        if (!com.example.automation.engine.PendingCallManager.isCallPermitted(contactId, phoneId)) {
            val msg = "Call aborted: No valid pending confirmation state exists for this contact/phone."
            logError(msg)
            throw IllegalStateException(msg)
        }

        val lookupKey = com.example.automation.engine.PendingCallManager.getLookupKeyForCall(contactId)
        if (lookupKey == null) {
            val msg = "Call aborted: Cannot find lookup key for the pending call."
            logError(msg)
            com.example.automation.engine.PendingCallManager.clear()
            throw IllegalStateException(msg)
        }

        try {
            // Re-validate the phone number using lookupKey just before dialing
            val validatedNumber = revalidatePhoneNumber(context, lookupKey, phoneId)
            if (validatedNumber.isNullOrBlank()) {
                val msg = "Call aborted: Contact or phone number no longer exists."
                logError(msg)
                com.example.automation.engine.PendingCallManager.clear()
                throw IllegalStateException(msg)
            }

            com.example.automation.engine.PendingCallManager.setCalling()
            startCallOrDial(context, validatedNumber)
        } catch (e: Exception) {
            logError("Failed to make call for contactId: $contactId", e)
            throw e
        } finally {
            com.example.automation.engine.PendingCallManager.clear()
        }
    }

    private fun normalizePhoneNumber(value: String): String {
        return android.telephony.PhoneNumberUtils.normalizeNumber(value) ?: ""
    }

    private fun revalidatePhoneNumber(context: Context, lookupKey: String, targetPhoneId: Long): String? {
        if (lookupKey.startsWith("raw_number:")) {
            return lookupKey.substringAfter("raw_number:")
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            logError("READ_CONTACTS permission is required to validate contacts")
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY} = ?"
        val args = arrayOf(lookupKey)

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val phoneId = cursor.getLong(idIndex)
                if (phoneId == targetPhoneId) {
                    val rawNumber = cursor.getString(numberIndex) ?: ""
                    return normalizePhoneNumber(rawNumber)
                }
            }
        }
        return null
    }

    private fun startCallOrDial(context: Context, phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
        } else {
            log("CALL_PHONE permission not granted, opening dialer instead")
            openDialer(context, phoneNumber)
        }
    }

    private fun openDialer(context: Context, phoneNumber: String) {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(dialIntent)
    }
}
