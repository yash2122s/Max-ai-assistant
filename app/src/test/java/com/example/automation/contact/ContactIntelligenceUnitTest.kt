package com.example.automation.contact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactIntelligenceUnitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sampleContacts = listOf(
        Contact("Ravi Kumar", "+919812345601", "Mobile"),
        Contact("Ravi Office", "+919812345602", "Work"),
        Contact("Ravi Home", "+919812345603", "Home"),
        Contact("Amma", "+919812345610", "Mobile"),
        Contact("Sita Ram", "+919812345611", "Mobile"),
        Contact("Sita Lakshmi", "+919812345612", "Mobile")
    )

    @Before
    fun setUp() {
        context.getSharedPreferences("max_contact_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // --- Pure matching stages ---

    @Test
    fun `exact name match found`() {
        val result = ContactResolver.matchByName("Ravi Kumar", sampleContacts)
        assertTrue(result is ContactResolution.Found)
        assertEquals("Ravi Kumar", (result as ContactResolution.Found).contact.name)
    }

    @Test
    fun `case-insensitive name match found`() {
        val result = ContactResolver.matchByName("ravi kumar", sampleContacts)
        assertTrue(result is ContactResolution.Found)
    }

    @Test
    fun `prefix match found`() {
        val result = ContactResolver.matchByName("Sita Ram", sampleContacts)
        assertTrue(result is ContactResolution.Found)
    }

    @Test
    fun `relationship alias matches`() {
        val result = ContactResolver.matchByName("mom", sampleContacts)
        assertTrue(result is ContactResolution.Found)
        assertEquals("Amma", (result as ContactResolution.Found).contact.name)
    }

    @Test
    fun `multiple matches produce ambiguity`() {
        val result = ContactResolver.matchByName("Ravi", sampleContacts)
        assertTrue(result is ContactResolution.Ambiguous)
        assertEquals(3, (result as ContactResolution.Ambiguous).matches.size)
    }

    @Test
    fun `no match not found`() {
        val result = ContactResolver.matchByName("Nobody Here", sampleContacts)
        assertTrue(result is ContactResolution.NotFound)
    }

    @Test
    fun `phone normalization handles local numbers`() {
        assertEquals("+919812345601", ContactResolver.normalizeToE164("9812345601"))
        assertEquals("+919812345601", ContactResolver.normalizeToE164("09812345601"))
        assertEquals("+919812345601", ContactResolver.normalizeToE164("919812345601"))
        assertTrue(ContactResolver.isDirectPhoneNumber("+91 98123 45601"))
    }

    // --- Preference learning + reuse ---

    @Test
    fun `remembered preference is stored and found case-insensitively`() {
        val store = ContactPreferenceStore(context)
        store.remember("Ravi", "Ravi Office")
        val found = store.find("  ravi ")
        assertNotNull(found)
        assertEquals("Ravi Office", found?.selectedName)
    }

    @Test
    fun `preference remembered selection resolves ambiguity when candidate present`() {
        val ambiguous = ContactResolution.Ambiguous(
            "Ravi",
            sampleContacts.filter { it.name.startsWith("Ravi") }
        )
        val preference = ContactPreference("Ravi", "Ravi Office")
        val result = ContactResolver.preferRememberedIfCandidate(ambiguous, preference)
        assertTrue(result is ContactResolution.Found)
        assertEquals("Ravi Office", (result as ContactResolution.Found).contact.name)
    }

    @Test
    fun `stale preference still asks the user`() {
        val ambiguous = ContactResolution.Ambiguous(
            "Ravi",
            sampleContacts.filter { it.name.startsWith("Ravi") }
        )
        // Remembered contact no longer among current candidates -> must ask again.
        val preference = ContactPreference("Ravi", "Ravi Deleted")
        val result = ContactResolver.preferRememberedIfCandidate(ambiguous, preference)
        assertTrue(result is ContactResolution.Ambiguous)
    }

    @Test
    fun `forget removes stored preference`() {
        val store = ContactPreferenceStore(context)
        store.remember("Ravi", "Ravi Office")
        store.forget("ravi")
        assertNull(store.find("Ravi"))
    }

    @Test
    fun `rememberSelection ignores non-refinements`() {
        assertFalse(ContactIntelligence.rememberSelection(context, "Ravi", "Ravi"))
        assertFalse(ContactIntelligence.rememberSelection(context, "+91 98123 45601", "Ravi Office"))
        assertFalse(ContactIntelligence.rememberSelection(context, "", "Ravi Office"))
        assertTrue(ContactIntelligence.rememberSelection(context, "Ravi", "Ravi Office"))
    }

    @Test
    fun `rememberIfContactSelection only fires for contact actions`() {
        assertTrue(
            ContactIntelligence.rememberIfContactSelection(context, "SEND_WHATSAPP_MESSAGE", "Ravi", "Ravi Office")
        )
        assertFalse(
            ContactIntelligence.rememberIfContactSelection(context, "OPEN_APP", "Ravi", "Ravi Office")
        )
    }

    @Test
    fun `later resolution prefers remembered contact only among candidates`() {
        val store = ContactPreferenceStore(context)
        store.remember("Ravi", "Ravi Office")
        val ambiguous = ContactResolution.Ambiguous(
            "Ravi",
            sampleContacts.filter { it.name.startsWith("Ravi") }
        )
        val resolved = ContactResolver.preferRememberedIfCandidate(ambiguous, store.find("Ravi"))
        assertTrue(resolved is ContactResolution.Found)
        assertEquals("Ravi Office", (resolved as ContactResolution.Found).contact.name)
    }
}
