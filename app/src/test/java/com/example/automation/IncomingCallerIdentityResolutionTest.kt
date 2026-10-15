package com.example.automation

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCallerIdentityResolutionTest {

    private lateinit var context: Context

    private val testContacts = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        IncomingCallManager.terminateCallAssistantSession(context)
        testContacts.clear()

        IncomingCallManager.testContactLookupHook = { queryNum ->
            val queryDigits = IncomingCallManager.normalizePhoneDigits(queryNum)
            var matched: String? = null
            for ((storedNum, name) in testContacts) {
                val storedDigits = IncomingCallManager.normalizePhoneDigits(storedNum)
                if (IncomingCallManager.isNumberMatch(queryDigits, storedDigits)) {
                    matched = name
                    break
                }
            }
            matched
        }

        // Clear mock database tables
        try {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        } catch (_: Exception) {}
        try {
            context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI, null, null)
        } catch (_: Exception) {}
    }

    private fun insertContact(name: String, phoneNumber: String) {
        testContacts[phoneNumber] = name
    }

    private fun insertCallLog(phoneNumber: String, name: String?, type: Int, ageSecondsAgo: Long) {
        val callTime = System.currentTimeMillis() - (ageSecondsAgo * 1000L)
        val values = ContentValues().apply {
            put(CallLog.Calls.NUMBER, phoneNumber)
            put(CallLog.Calls.CACHED_NAME, name)
            put(CallLog.Calls.TYPE, type)
            put(CallLog.Calls.DATE, callTime)
        }
        context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
    }

    // A. Incoming number available + saved contact
    @Test
    fun testA_incomingNumberAvailable_savedContactMatched() = runBlocking {
        insertContact("Alice Smith", "+91 98765 43210")

        val identity = IncomingCallManager.resolveIdentity(context, "+919876543210")
        assertEquals("Alice Smith", identity)
    }

    // B. Incoming number available + unsaved number -> never guess, return "unknown number"
    @Test
    fun testB_incomingNumberAvailable_unsavedNumber_returnsUnknownNumber() = runBlocking {
        insertContact("Alice Smith", "+91 98765 43210")

        val identity = IncomingCallManager.resolveIdentity(context, "+91 99999 88888")
        assertEquals("unknown number", identity)
    }

    // C. Incoming number null + no fresh call log -> returns "unknown number"
    @Test
    fun testC_incomingNumberNull_returnsUnknownNumber() = runBlocking {
        val identity = IncomingCallManager.resolveIdentity(context, null)
        assertEquals("unknown number", identity)
    }

    // D. Previous call exists in CallLog (e.g. 5 minutes ago) -> MUST NOT announce previous caller
    @Test
    fun testD_previousCallExistsInCallLog_neverAnnouncedAsCurrentCaller() = runBlocking {
        insertContact("Old Friend Bob", "+91 91234 56789")
        // Insert a call from Bob that occurred 5 minutes ago (300 seconds ago)
        insertCallLog("+91 91234 56789", "Old Friend Bob", CallLog.Calls.INCOMING_TYPE, ageSecondsAgo = 300L)

        // Incoming call broadcast has null number (Android 9+ behavior without immediate number)
        val identity = IncomingCallManager.resolveIdentity(context, null)

        // Must NOT announce Bob!
        assertNotEquals("Old Friend Bob", identity)
        assertEquals("unknown number", identity)
    }

    // E. Similar-number contacts exist (e.g. substring or last digits collision)
    @Test
    fun testE_similarNumberContacts_strictNormalizedComparison() {
        insertContact("Charlie Short", "43210")
        insertContact("Dave Full", "+91 98765 43210")

        // Exact number lookup for Dave Full
        val contactMatch = IncomingCallManager.getContactName(context, "+919876543210")
        assertEquals("Dave Full", contactMatch)

        // Exact number lookup for an unrelated number ending in 43210 but different prefix
        val unrelatedMatch = IncomingCallManager.getContactName(context, "+91 11111 43210")
        // Charlie Short only has 5 digits which shouldn't match full 10 digit number carelessly
        assertNotEquals("Dave Full", unrelatedMatch)
    }

    // F. Private/unknown caller strings -> return "unknown number"
    @Test
    fun testF_privateUnknownCaller_returnsUnknownNumber() = runBlocking {
        val privateLabels = listOf("Private Number", "UNKNOWN", "Restricted", "null", "withheld", "")
        for (label in privateLabels) {
            val identity = IncomingCallManager.resolveIdentity(context, label)
            assertEquals("Expected 'unknown number' for '$label'", "unknown number", identity)
        }
    }

    // G. Accessibility screen contains "SIM 1", carrier name, "Mobile", etc. -> Rejected
    @Test
    fun testG_ignoredDialerLabels_areFilteredOut() {
        val ignoredSamples = listOf(
            "SIM 1",
            "sim 2",
            "Jio",
            "Airtel",
            "Mobile",
            "Incoming call",
            "Calling",
            "Answer",
            "Decline",
            "Swipe up to answer",
            "HD call",
            "Calling via Jio"
        )
        for (sample in ignoredSamples) {
            assertTrue("Expected '$sample' to be ignored dialer label", IncomingCallManager.isIgnoredDialerLabel(sample))
        }

        // Legitimate contact names must NOT be ignored
        val validNames = listOf("Yashwanth", "Rahul Sharma", "Mom", "Doctor Suresh")
        for (name in validNames) {
            assertFalse("Expected '$name' NOT to be ignored", IncomingCallManager.isIgnoredDialerLabel(name))
        }
    }

    // H. Multiple contacts with similar representations -> exact matching verification
    @Test
    fun testH_numberMatch_verifiesTrailingAndFullEquality() {
        // Exact equality
        assertTrue(IncomingCallManager.isNumberMatch("9876543210", "9876543210"))
        // Country code prefix difference (+91 vs standard)
        assertTrue(IncomingCallManager.isNumberMatch("919876543210", "9876543210"))
        // Different numbers with only 3 identical digits -> must NOT match
        assertFalse(IncomingCallManager.isNumberMatch("9876511210", "9876543210"))
        // Unrelated short digits
        assertFalse(IncomingCallManager.isNumberMatch("123", "456"))
    }
}
