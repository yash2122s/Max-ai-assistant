package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.actions.CallPhoneAction
import com.example.automation.actions.ContactAliasResolver
import com.example.automation.actions.ContactNotFoundClarificationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactResolutionUnitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testContactAliasResolver_NannaResolvesToFamilyAliases() {
        val candidates = ContactAliasResolver.getCandidateQueries("నాన్నకు")
        assertTrue("Must contain cleaned root query 'నాన్న'", candidates.contains("నాన్న"))
        assertTrue("Must contain English alias 'Dad'", candidates.contains("Dad"))
        assertTrue("Must contain English alias 'Daddy'", candidates.contains("Daddy"))
        assertTrue("Must contain English alias 'Father'", candidates.contains("Father"))
        assertTrue("Must contain English alias 'Papa'", candidates.contains("Papa"))
    }

    @Test
    fun testContactAliasResolver_DaddyResolvesToTeluguAndEnglishAliases() {
        val candidates = ContactAliasResolver.getCandidateQueries("డాడీకి")
        assertTrue("Must contain cleaned query 'డాడీ'", candidates.contains("డాడీ"))
        assertTrue("Must contain 'Daddy'", candidates.contains("Daddy"))
        assertTrue("Must contain 'Dad'", candidates.contains("Dad"))
        assertTrue("Must contain 'Papa'", candidates.contains("Papa"))
    }

    @Test
    fun testContactAliasResolver_AmmaResolvesToMomAliases() {
        val candidates = ContactAliasResolver.getCandidateQueries("అమ్మకి")
        assertTrue("Must contain 'అమ్మ'", candidates.contains("అమ్మ"))
        assertTrue("Must contain 'Mom'", candidates.contains("Mom"))
        assertTrue("Must contain 'Mummy'", candidates.contains("Mummy"))
        assertTrue("Must contain 'Mother'", candidates.contains("Mother"))
    }

    @Test
    fun testContactAliasResolver_AnnayyaResolvesToBrotherAliases() {
        val candidates = ContactAliasResolver.getCandidateQueries("అన్నయ్యకు")
        assertTrue("Must contain 'అన్నయ్య'", candidates.contains("అన్నయ్య"))
        assertTrue("Must contain 'Brother'", candidates.contains("Brother"))
        assertTrue("Must contain 'Anna'", candidates.contains("Anna"))
    }

    @Test
    fun testCallPhoneAction_UnresolvedContactThrowsClarificationException() {
        val action = CallPhoneAction()
        val payload = JSONObject().apply {
            put("contact", "నాన్న")
        }

        try {
            action.execute(context, payload)
            fail("Must throw ContactNotFoundClarificationException when contact is not found")
        } catch (e: ContactNotFoundClarificationException) {
            assertTrue("Clarification message must not be blank", e.clarificationMessage.isNotBlank())
            assertTrue("Message must mention the contact name", e.clarificationMessage.contains("నాన్న"))
            assertTrue("Message must prompt user who to call", e.clarificationMessage.contains("ఎవరికి కాల్ చేయాలో"))
        } catch (e: Exception) {
            fail("Expected ContactNotFoundClarificationException, but got: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
