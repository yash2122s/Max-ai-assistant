package com.example.features.calls.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import com.example.automation.actions.BaseAction
import com.example.automation.actions.SearchContactAction
import com.example.automation.engine.CallTargetKind
import com.example.automation.engine.ContactMatch
import com.example.automation.engine.PendingCallManager
import com.example.automation.engine.PhoneNumber
import com.example.automation.engine.PreparedCallTarget
import com.example.automation.engine.DirectCallAuthorization

class ContactNotFoundClarificationException(val clarificationMessage: String) : IllegalArgumentException(clarificationMessage)

class CallConfirmationRequiredException(
    val contactId: Long, 
    val phoneId: Long, 
    val displayName: String, 
    val phoneNumber: String, 
    val confirmationPrompt: String
) : IllegalStateException(confirmationPrompt)

class CallPermissionRequiredException : SecurityException(
    "Phone call permission is required. Grant CALL_PHONE permission and try again."
)

class CallPhoneAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        var contactId = payload.optLong("contactId", -1L)
        var phoneId = payload.optLong("phoneId", -1L)
        
        val contactParam = payload.optString("contact").ifEmpty {
            payload.optString("phoneNumber").ifEmpty {
                payload.optString("number")
            }
        }.trim()

        if (contactParam.any { it in "*#;," }) {
            val msg = "Special characters and USSD codes are not permitted in phone calls."
            logError(msg)
            throw IllegalArgumentException(msg)
        }

        // Fix for LLM context hallucination: If the LLM re-submits an old contactId from a previous 
        // AwaitingConfirmation turn, but the user changed the target (e.g., 'sai2' instead of 'sriramulu'),
        // the contactParam will not match the pending display name. We must clear the IDs to force resolution.
        if (contactId != -1L && contactParam.isNotEmpty()) {
            val pending = PendingCallManager.state
            var previousName: String? = null
            
            if (pending is com.example.automation.engine.CallState.AwaitingConfirmation) {
                previousName = pending.displayName
            } else if (pending is com.example.automation.engine.CallState.Confirmed) {
                previousName = pending.displayName
            }
            
            if (previousName != null) {
                val isMatch = previousName.equals(contactParam, ignoreCase = true) || 
                              previousName.contains(contactParam, ignoreCase = true) || 
                              contactParam.contains(previousName, ignoreCase = true)
                
                if (!isMatch) {
                    contactId = -1L
                    phoneId = -1L
                }
            }
        }

        // Try to resolve missing IDs from local text payload or existing pending state
        val currentPendingState = PendingCallManager.state
        if (contactId == -1L || phoneId == -1L) {
            if (currentPendingState is com.example.automation.engine.CallState.Confirmed) {
                contactId = currentPendingState.contactId
                phoneId = currentPendingState.phoneId
            } else if (currentPendingState is com.example.automation.engine.CallState.AwaitingConfirmation) {
                contactId = currentPendingState.contactId
                phoneId = currentPendingState.phoneId
            }
        }

        if ((contactId == -1L || phoneId == -1L) && currentPendingState !is com.example.automation.engine.CallState.Confirmed) {
            if (contactParam.isNotEmpty()) {
                val cleanContact = contactParam.replace(Regex("[\\s\\-()]"), "")
                val isRawNumber = cleanContact.matches(Regex("""\+?\d{7,15}"""))
                if (isRawNumber) {
                    val normalized = DirectCallAuthorization.normalize(contactParam) ?: ""
                    if (normalized.isBlank()) return@execute
                    val rawId = -1000L - (normalized.hashCode().toLong().and(0x7fffffffL) % 1000000L)
                    val syntheticMatch = ContactMatch(
                        contactId = rawId,
                        lookupKey = "raw_number:$normalized",
                        displayName = "Raw Number",
                        phoneNumbers = listOf(
                            PhoneNumber(
                                id = rawId,
                                label = "Number",
                                normalizedNumber = normalized
                            )
                        )
                    )
                    val target = PreparedCallTarget(
                        targetKind = CallTargetKind.DIRECT_NUMBER,
                        normalizedNumber = normalized,
                        displayName = "Raw Number",
                        contactId = rawId,
                        lookupKey = syntheticMatch.lookupKey,
                        phoneId = rawId,
                        numberLabel = "Number",
                        originalUserIntent = "Call $contactParam"
                    )
                    PendingCallManager.setAwaitingConfirmation(
                        contactId = rawId,
                        lookupKey = syntheticMatch.lookupKey,
                        phoneId = rawId,
                        displayName = "Raw Number",
                        phoneNumber = normalized,
                        originalUserIntent = "Call $contactParam",
                        preparedTarget = target
                    )

                    // A raw number bypasses confirmation only with a short-lived,
                    // single-use capability issued from the trusted transcription.
                    // Named contacts retain the normal search and confirmation flow.
                    if (DirectCallAuthorization.consume(
                            payload.optString("direct_call_authorization").takeIf { it.isNotBlank() },
                            normalized
                        )) {
                        PendingCallManager.confirmPendingCall(rawId, rawId)
                    }

                    contactId = rawId
                    phoneId = rawId
                } else {
                    try {
                        val matches = SearchContactAction().searchContacts(context, contactParam)
                        if (matches.size == 1 && matches[0].phoneNumbers.size == 1) {
                            val match = matches[0]
                            val phone = match.phoneNumbers[0]
                            val target = PreparedCallTarget(
                                targetKind = CallTargetKind.CONTACT,
                                normalizedNumber = phone.normalizedNumber,
                                displayName = match.displayName,
                                contactId = match.contactId,
                                lookupKey = match.lookupKey,
                                phoneId = phone.id,
                                numberLabel = phone.label,
                                originalUserIntent = "Call $contactParam"
                            )
                            PendingCallManager.setAwaitingConfirmation(
                                contactId = match.contactId,
                                lookupKey = match.lookupKey,
                                phoneId = phone.id,
                                displayName = match.displayName,
                                phoneNumber = phone.normalizedNumber,
                                originalUserIntent = "Call $contactParam",
                                preparedTarget = target
                            )

                            contactId = match.contactId
                            phoneId = phone.id
                        } else if (matches.isNotEmpty()) {
                            PendingCallManager.setAwaitingContactSelection(matches, originalUserIntent = "Call $contactParam")
                            val clarificationMsg = "I found multiple contacts/numbers for '$contactParam'. Please choose which one to call."
                            throw ContactNotFoundClarificationException(clarificationMsg)
                        }
                    } catch (e: ContactNotFoundClarificationException) {
                        throw e
                    } catch (e: Exception) {
                        logError("Failed to search contact locally during resolution", e)
                    }
                }
            }
        }
        
        if (contactId == -1L || phoneId == -1L) {
            PendingCallManager.clear()
            com.example.automation.approval.ApprovalCoordinator.clearAll()
            val clarificationMsg = if (contactParam.isNotEmpty()) {
                "'$contactParam' అని ఏ కాంటాక్ట్ సేవ్ లేదు. ఎవరికి కాల్ చేయాలో పేరు లేదా నంబర్ చెప్పండి."
            } else {
                "ఎవరికి కాల్ చేయాలో కాంటాక్ట్ పేరు లేదా నంబర్ చెప్పండి."
            }
            logError(clarificationMsg)
            throw ContactNotFoundClarificationException(clarificationMsg)
        }

        // P1-3: Model-controlled confirmed=true rejected as independent consent.
        // A call is permitted ONLY if PendingCallManager is ALREADY in Confirmed state
        // via authentic user confirmation. Incoming payload "confirmed" flag cannot unilaterally
        // transition PendingCallManager without verified user consent.

        // Enforce state machine rules
        if (!PendingCallManager.isCallPermitted(contactId, phoneId)) {
            val pending = PendingCallManager.state
            val (name, number) = when (pending) {
                is com.example.automation.engine.CallState.AwaitingConfirmation -> pending.displayName to pending.phoneNumber
                is com.example.automation.engine.CallState.Confirmed -> pending.displayName to pending.phoneNumber
                else -> {
                    (if (contactParam.isNotEmpty()) contactParam else "the contact") to ""
                }
            }
            val prompt = if (number.isNotBlank()) {
                "Sir, $name ($number) ki call cheyamantara? Confirm cheyandi."
            } else {
                "Sir, $name ki call cheyamantara? Confirm cheyandi."
            }
            val msg = "Call requires explicit user confirmation for $name ($number)"
            logError(msg)
            throw CallConfirmationRequiredException(contactId, phoneId, name, number, prompt)
        }

        val lookupKey = PendingCallManager.getLookupKeyForCall(contactId)
        if (lookupKey == null) {
            val msg = "Call aborted: Cannot find lookup key for the pending call."
            logError(msg)
            PendingCallManager.clear()
            throw IllegalStateException(msg)
        }

        try {
            // Re-validate the phone number using lookupKey just before dialing
            val validatedNumber = revalidatePhoneNumber(context, lookupKey, phoneId)
            if (validatedNumber.isNullOrBlank()) {
                val msg = "Call aborted: Contact or phone number no longer exists."
                logError(msg)
                PendingCallManager.clear()
                throw IllegalStateException(msg)
            }

            // Target immutability verification: ensure contact number has not changed since user confirmation
            val confirmedState = PendingCallManager.state as? com.example.automation.engine.CallState.Confirmed
            if (confirmedState != null && confirmedState.phoneNumber.isNotBlank()) {
                val normApproved = android.telephony.PhoneNumberUtils.normalizeNumber(confirmedState.phoneNumber) ?: confirmedState.phoneNumber
                val normCurrent = android.telephony.PhoneNumberUtils.normalizeNumber(validatedNumber) ?: validatedNumber
                if (normApproved != normCurrent) {
                    val msg = "Call aborted: Contact phone number has changed since confirmation."
                    logError(msg)
                    PendingCallManager.clear()
                    throw IllegalStateException(msg)
                }
            }

            PendingCallManager.setCalling()
            val callResult = startCallOrDial(context, validatedNumber)
            payload.put("callResult", callResult)
            if (callResult == "Opened the dialer") {
                payload.put("dialerOpened", true)
            }
        } catch (e: Exception) {
            logError("Failed to make call for contactId: $contactId", e)
            throw e
        } finally {
            PendingCallManager.clear()
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

    private fun isEmergencyNumber(context: Context, phoneNumber: String): Boolean {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            try {
                val isEmerg = telephonyManager?.isEmergencyNumber(cleanNumber)
                if (isEmerg == true) return true
            } catch (_: Exception) {}
        }
        val fallbackEmergency = DirectCallAuthorization.EMERGENCY_NUMBERS
        val digits = cleanNumber.filter(Char::isDigit)
        return fallbackEmergency.contains(digits) || fallbackEmergency.contains(cleanNumber)
    }

    private fun startCallOrDial(context: Context, phoneNumber: String): String {
        if (phoneNumber.any { it in "*#;," }) {
            val msg = "Special characters and USSD codes are not permitted in phone calls."
            logError(msg)
            throw IllegalArgumentException(msg)
        }

        if (isEmergencyNumber(context, phoneNumber)) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            com.example.automation.engine.truth.ExecutionTruthCoordinator.clearTurnExecution()
            log("Emergency number requested; opened the dialer without placing direct call.")
            return "Opened the dialer"
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            logError("CALL_PHONE permission not granted; refusing to downgrade to ACTION_DIAL")
            throw CallPermissionRequiredException()
        }

        val uri = Uri.fromParts("tel", phoneNumber, null)
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        var placed = false
        if (telecomManager != null) {
            try {
                telecomManager.placeCall(uri, android.os.Bundle())
                placed = true
            } catch (se: SecurityException) {
                logError("TelecomManager.placeCall SecurityException, falling back to ACTION_CALL", se)
            } catch (e: Exception) {
                logError("TelecomManager.placeCall failed, falling back to ACTION_CALL", e)
            }
        }

        if (!placed) {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
        }
        return "Call placed"
    }
}
