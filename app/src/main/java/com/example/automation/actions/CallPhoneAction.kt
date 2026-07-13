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
        val contact = payload.optString("contact").ifEmpty { payload.optString("phone_number") }
        
        if (contact.isBlank()) {
            logError("No contact/phone number provided for CALL_PHONE")
            return
        }

        try {
            val phoneNumber = if (looksLikePhoneNumber(contact)) {
                normalizePhoneNumber(contact)
            } else {
                resolveContactNumber(context, contact)
            }

            if (phoneNumber.isNullOrBlank()) {
                logError("Could not resolve contact '$contact' to a phone number")
                return
            }

            startCallOrDial(context, phoneNumber)
        } catch (e: SecurityException) {
            val phoneNumber = normalizePhoneNumber(contact)
            if (phoneNumber.isNotBlank()) {
                openDialer(context, phoneNumber)
            }
        } catch (e: Exception) {
            logError("Failed to make call to $contact", e)
        }
    }

    private fun looksLikePhoneNumber(value: String): Boolean {
        val cleaned = normalizePhoneNumber(value)
        return cleaned.count { it.isDigit() } >= 3 && cleaned.all { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    private fun normalizePhoneNumber(value: String): String {
        return value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    private fun resolveContactNumber(context: Context, name: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            logError("READ_CONTACTS permission is required to call contacts by name")
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?"
        val args = arrayOf("%$name%")

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (numberIndex >= 0 && cursor.moveToFirst()) {
                return normalizePhoneNumber(cursor.getString(numberIndex).orEmpty())
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
