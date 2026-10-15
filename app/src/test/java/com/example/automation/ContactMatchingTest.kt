package com.example.automation

import com.example.automation.contact.ContactResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatchingTest {

    @Test
    fun testExactNameMatch() {
        assertTrue(ContactResolver.matchesSelectedContact("Priya", "Priya"))
        assertTrue(ContactResolver.matchesSelectedContact("priya", "Priya"))
        assertTrue(ContactResolver.matchesSelectedContact("  Priya  ", "priya"))
        assertTrue(ContactResolver.matchesSelectedContact("Yashwanth V", "yashwanth v"))
    }

    @Test
    fun testPhoneNumberMatchNormalized() {
        assertTrue(ContactResolver.matchesSelectedContact("+91 98765 43210", "+919876543210"))
        assertTrue(ContactResolver.matchesSelectedContact("9876543210", "+91 98765 43210"))
        assertTrue(ContactResolver.matchesSelectedContact("+919876543210", "9876543210"))
    }

    @Test
    fun testNonMatchingContactReturnsFalse() {
        assertFalse(ContactResolver.matchesSelectedContact("Ravi", "Priya"))
        assertFalse(ContactResolver.matchesSelectedContact("Unknown", "+919876543210"))
        assertFalse(ContactResolver.matchesSelectedContact("9876543210", "9123456789"))
    }

    @Test
    fun testFailClosedOnBlankOrEmpty() {
        assertFalse(ContactResolver.matchesSelectedContact("", "Priya"))
        assertFalse(ContactResolver.matchesSelectedContact("Priya", ""))
        assertFalse(ContactResolver.matchesSelectedContact("   ", "   "))
        assertFalse(ContactResolver.isSenderInSelectedContacts("", setOf("Priya")))
        assertFalse(ContactResolver.isSenderInSelectedContacts("Priya", emptySet()))
    }

    @Test
    fun testMatchesAnySelected() {
        val whitelist = setOf("Priya", "Mom", "+919876543210")
        assertTrue(ContactResolver.isSenderInSelectedContacts("Priya", whitelist))
        assertTrue(ContactResolver.isSenderInSelectedContacts("mom", whitelist))
        assertTrue(ContactResolver.isSenderInSelectedContacts("+91 98765 43210", whitelist))
        assertFalse(ContactResolver.isSenderInSelectedContacts("Boss", whitelist))
    }
}
