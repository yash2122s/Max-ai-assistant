package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.DirectCallAuthorization
import com.example.automation.engine.SpokenDigits
import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.ActionDispatcher
import com.example.features.calls.actions.CallPhoneAction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpokenDigitsTest {

    @Test
    fun testEnglishDigitWords() {
        assertEquals("0123456789", SpokenDigits.digitsOnly("zero one two three four five six seven eight nine"))
        assertEquals("9849012345", SpokenDigits.digitsOnly("call nine eight four nine zero one two three four five"))
    }

    @Test
    fun testTeluguScriptDigitWords() {
        assertEquals("01234556789", SpokenDigits.digitsOnly("సున్నా ఒకటి రెండు మూడు నాలుగు ఐదు అయిదు ఆరు ఏడు ఎనిమిది తొమ్మిది"))
        assertEquals("9849012345", SpokenDigits.digitsOnly("కాల్ తొమ్మిది ఎనిమిది నాలుగు తొమ్మిది సున్నా ఒకటి రెండు మూడు నాలుగు ఐదు"))
    }

    @Test
    fun testTeluguTransliterationDigitWords() {
        assertEquals("0123456789", SpokenDigits.digitsOnly("sunna okati rendu moodu nalugu aidu aaru edu enimidi tommidi"))
        assertEquals("9849012345", SpokenDigits.digitsOnly("call tommidi enimidi nalugu tommidi sunna okati rendu moodu nalugu aidu"))
    }

    @Test
    fun testMixedDigitsAndPunctuation() {
        assertEquals("919849012345", SpokenDigits.digitsOnly("+91 98490-12345"))
        assertEquals("9849012345", SpokenDigits.digitsOnly("call (984) 901-2345"))
        assertEquals("9849012345", SpokenDigits.digitsOnly("call 98490 one two three four five"))
    }

    @Test
    fun testDirectCallAuthorizationWithSpokenDigits() {
        DirectCallAuthorization.clear()

        // 1. Spoken English digits with call verb issues valid single-use token
        val token = DirectCallAuthorization.issue(
            utterance = "call nine eight four nine zero one two three four five",
            target = "9849012345"
        )
        assertNotNull("DirectCallAuthorization must issue token for matching spoken digits", token)
        assertTrue(DirectCallAuthorization.consume(token, "9849012345"))
        assertFalse("Token must be single-use", DirectCallAuthorization.consume(token, "9849012345"))

        // 2. Reject targets containing special characters or USSD
        assertNull(DirectCallAuthorization.issue("call *123#", "*123#"))
        assertNull(DirectCallAuthorization.issue("call 98490;12345", "98490;12345"))
        assertNull(DirectCallAuthorization.issue("call 98490,12345", "98490,12345"))

        // 3. Reject emergency numbers
        assertNull(DirectCallAuthorization.issue("call 112", "112"))
        assertNull(DirectCallAuthorization.issue("call 911", "911"))
        assertNull(DirectCallAuthorization.issue("call 100", "100"))
        assertNull(DirectCallAuthorization.issue("call 108", "108"))

        // 4. Require call verb
        assertNull(DirectCallAuthorization.issue("nine eight four nine zero one two three four five", "9849012345"))
    }

    @Test
    fun testActionAuthorizerBlocksCallLogAndCallbacks() {
        // "recall", "callback", "what's the call log" must be blocked
        val r1 = ActionAuthorizer.authorizeAction("recall the previous event", "CALL_PHONE", "")
        assertFalse("Recall must be blocked", r1.authorized)

        val r2 = ActionAuthorizer.authorizeAction("callback later", "CALL_PHONE", "")
        assertFalse("Callback must be blocked", r2.authorized)

        val r3 = ActionAuthorizer.authorizeAction("what's the call log", "CALL_PHONE", "")
        assertFalse("Call log query must be blocked", r3.authorized)

        val r4 = ActionAuthorizer.authorizeAction("open call history", "CALL_PHONE", "")
        assertFalse("Call history query must be blocked", r4.authorized)

        // Genuine call commands must be authorized
        val c1 = ActionAuthorizer.authorizeAction("call Mom", "CALL_PHONE", "Mom")
        assertTrue("call Mom must be authorized", c1.authorized)

        val c2 = ActionAuthorizer.authorizeAction("dial 9849012345", "CALL_PHONE", "9849012345")
        assertTrue("dial number must be authorized", c2.authorized)

        val c3 = ActionAuthorizer.authorizeAction("అమ్మకి కాల్ చేయి", "CALL_PHONE", "అమ్మ")
        assertTrue("Telugu call command failed with reason: '${c3.reason}'", c3.authorized)
    }

    @Test
    fun testActionDispatcherExplicitCallVariants() {
        // Telugu variants
        assertTrue(ActionDispatcher.isExplicitCallUtterance("అమ్మకి కాల్ చేయి"))
        assertTrue(ActionDispatcher.isExplicitCallUtterance("అమ్మకి కాల్ చెయ్యి"))
        assertTrue(ActionDispatcher.isExplicitCallUtterance("అమ్మకి కాల్ చేయ్"))
        assertTrue(ActionDispatcher.isExplicitCallUtterance("అమ్మకి కాల్ చేయండి"))
        assertTrue(ActionDispatcher.isExplicitCallUtterance("అమ్మకి ఫోన్ చేయి"))
        assertTrue(ActionDispatcher.isExplicitCallUtterance("Mom ki call cheyyi"))
        assertTrue(ActionDispatcher.isExplicitCallUtterance("Mom ki call cheyandi"))
        assertTrue(ActionDispatcher.isExplicitCallUtterance("Mom ki call cheyi"))
        assertTrue(ActionDispatcher.isExplicitCallUtterance("Mom ki call chey"))

        // Exclusions
        assertFalse(ActionDispatcher.isExplicitCallUtterance("what's the call log"))
        assertFalse(ActionDispatcher.isExplicitCallUtterance("check call history"))
        assertFalse(ActionDispatcher.isExplicitCallUtterance("recall that"))
        assertFalse(ActionDispatcher.isExplicitCallUtterance("callback tomorrow"))
    }

    @Test
    fun testCallPhoneActionRejectsSpecialCharacters() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val action = CallPhoneAction()

        try {
            val payload = JSONObject().apply {
                put("contact", "*123#")
            }
            action.execute(context, payload)
            fail("Expected IllegalArgumentException for USSD code")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Special characters") == true)
        }
    }
}
