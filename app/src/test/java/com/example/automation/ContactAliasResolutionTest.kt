package com.example.automation

import com.example.automation.actions.ContactAliasResolver
import com.example.automation.engine.ContactMatch
import com.example.automation.engine.PhoneNumber
import org.junit.Assert.*
import org.junit.Test

class ContactAliasResolutionTest {

    @Test
    fun testStripCallPrefixes() {
        assertEquals("Mom", ContactAliasResolver.stripCallPrefixes("Call Mom"))
        assertEquals("Dad", ContactAliasResolver.stripCallPrefixes("dial Dad"))
        assertEquals("అమ్మ", ContactAliasResolver.stripCallPrefixes("కాల్ అమ్మ"))
        assertEquals("నాన్న", ContactAliasResolver.stripCallPrefixes("ఫోన్ నాన్న"))
        assertEquals("సుమలతతో", ContactAliasResolver.stripCallPrefixes("Call సుమలతతో"))
    }

    @Test
    fun testStripPossessivePrefixes() {
        // Possessive prefixes with space are stripped
        assertEquals("mom", ContactAliasResolver.stripPossessivePrefixes("my mom"))
        assertEquals("father", ContactAliasResolver.stripPossessivePrefixes("our father"))
        assertEquals("అమ్మ", ContactAliasResolver.stripPossessivePrefixes("నా అమ్మ"))
        assertEquals("అన్న", ContactAliasResolver.stripPossessivePrefixes("మా అన్న"))

        // Words that naturally start with "నా" like "నాన్న" are preserved because there is no following space
        assertEquals("నాన్న", ContactAliasResolver.stripPossessivePrefixes("నాన్న"))
    }

    @Test
    fun testStripTeluguCaseSuffix() {
        assertEquals("అమ్మ", ContactAliasResolver.stripTeluguCaseSuffix("అమ్మకి"))
        assertEquals("నాన్న", ContactAliasResolver.stripTeluguCaseSuffix("నాన్నకు"))
        assertEquals("సుమలత", ContactAliasResolver.stripTeluguCaseSuffix("సుమలతతో"))
        assertEquals("రవి", ContactAliasResolver.stripTeluguCaseSuffix("రవిగారికి"))
        assertEquals("సురేష్", ContactAliasResolver.stripTeluguCaseSuffix("సురేష్ గారు"))
        assertEquals("రాము", ContactAliasResolver.stripTeluguCaseSuffix("రామును"))
    }

    @Test
    fun testNormalizeForAliasLookup() {
        assertEquals("mom", ContactAliasResolver.normalizeForAliasLookup("Call my Mom"))
        assertEquals("అమ్మ", ContactAliasResolver.normalizeForAliasLookup("అమ్మకి"))
        assertEquals("mom", ContactAliasResolver.normalizeForAliasLookup("call Mom"))
        assertEquals("నాన్న", ContactAliasResolver.normalizeForAliasLookup("ఫోన్ నాన్నకు"))
    }

    @Test
    fun testSymmetricAliasGroupsIncludeRequiredAndExcludeRemoved() {
        // 1. "Call Mom" finds contact alias group including "అమ్మ"
        val momTerms = ContactAliasResolver.getAliasGroupTerms("Call Mom").map { it.lowercase() }
        assertTrue("Mom group must include 'mom'", momTerms.contains("mom"))
        assertTrue("Mom group must include 'mother'", momTerms.contains("mother"))
        assertTrue("Mom group must include 'amma'", momTerms.contains("amma"))
        assertTrue("Mom group must include 'అమ్మ'", momTerms.contains("అమ్మ"))
        assertTrue("Mom group must include 'మమ్మీ'", momTerms.contains("మమ్మీ"))

        // 2. "Amma ki call cheyyi" finds "Mom"
        val ammaCallTerms = ContactAliasResolver.getAliasGroupTerms("Amma ki call cheyyi").map { it.lowercase() }
        assertTrue("Amma group must include 'mom'", ammaCallTerms.contains("mom"))
        assertTrue("Amma group must include 'mother'", ammaCallTerms.contains("mother"))
        assertTrue("Amma group must include 'amma'", ammaCallTerms.contains("amma"))

        val ammaTerms = ContactAliasResolver.getAliasGroupTerms("Amma ki").map { it.lowercase() }
        assertTrue("Amma group must include 'mom'", ammaTerms.contains("mom"))
        assertTrue("Amma group must include 'mother'", ammaTerms.contains("mother"))
        assertTrue("Amma group must include 'amma'", ammaTerms.contains("amma"))

        // 3. Father group
        val dadTerms = ContactAliasResolver.getAliasGroupTerms("నాన్నకు").map { it.lowercase() }
        assertTrue("Father group must include 'dad'", dadTerms.contains("dad"))
        assertTrue("Father group must include 'daddy'", dadTerms.contains("daddy"))
        assertTrue("Father group must include 'father'", dadTerms.contains("father"))
        assertTrue("Father group must include 'papa'", dadTerms.contains("papa"))
        assertTrue("Father group must include 'nanna'", dadTerms.contains("nanna"))
        assertTrue("Father group must include 'నాన్న'", dadTerms.contains("నాన్న"))
        assertTrue("Father group must include 'డాడీ'", dadTerms.contains("డాడీ"))

        // 4. Instructions: "appa" and "maa" removed
        assertFalse("Alias groups must NOT contain 'appa'", dadTerms.contains("appa"))
        assertFalse("Alias groups must NOT contain 'maa'", momTerms.contains("maa"))

        // 5. Instructions: "friend" group removed
        val friendTerms = ContactAliasResolver.getAliasGroupTerms("friend")
        assertTrue("Friend group must be empty/removed", friendTerms.isEmpty())
    }

    @Test
    fun testFilterExactMatches_AmmaDoesNotDragInAmmaji() {
        val candidates = listOf(
            ContactMatch(
                contactId = 1L,
                lookupKey = "k1",
                displayName = "Amma",
                phoneNumbers = listOf(PhoneNumber(11L, "Mobile", "9876543210"))
            ),
            ContactMatch(
                contactId = 2L,
                lookupKey = "k2",
                displayName = "Ammaji",
                phoneNumbers = listOf(PhoneNumber(12L, "Mobile", "9876543211"))
            )
        )

        val targetTerms = setOf("Amma")
        val filtered = ContactAliasResolver.filterExactMatches(candidates, targetTerms)

        assertEquals("Only exact match 'Amma' should be returned", 1, filtered.size)
        assertEquals("Amma", filtered[0].displayName)
    }

    @Test
    fun testFilterExactMatches_DisambiguationWhenBothMomAndAmmaSaved() {
        val candidates = listOf(
            ContactMatch(
                contactId = 1L,
                lookupKey = "k1",
                displayName = "Mom",
                phoneNumbers = listOf(PhoneNumber(11L, "Mobile", "9876543210"))
            ),
            ContactMatch(
                contactId = 2L,
                lookupKey = "k2",
                displayName = "Amma",
                phoneNumbers = listOf(PhoneNumber(12L, "Mobile", "9876543211"))
            ),
            ContactMatch(
                contactId = 3L,
                lookupKey = "k3",
                displayName = "Ammaji",
                phoneNumbers = listOf(PhoneNumber(13L, "Mobile", "9876543212"))
            )
        )

        // Query was Telugu "అమ్మ", which expands to the Mother alias group (including Mom, Amma, etc.)
        val groupTerms = ContactAliasResolver.getAliasGroupTerms("అమ్మ")
        val filtered = ContactAliasResolver.filterExactMatches(candidates, groupTerms)

        // Both "Mom" and "Amma" are exact matches to group terms; "Ammaji" is discarded
        assertEquals("Both Mom and Amma are exact matches to Mother group", 2, filtered.size)
        val names = filtered.map { it.displayName }.toSet()
        assertTrue(names.contains("Mom"))
        assertTrue(names.contains("Amma"))
        assertFalse("Ammaji must not be included", names.contains("Ammaji"))
    }

    @Test
    fun testFilterExactMatches_FallsBackToAllWhenNoExactMatch() {
        val candidates = listOf(
            ContactMatch(
                contactId = 1L,
                lookupKey = "k1",
                displayName = "Ammaji",
                phoneNumbers = listOf(PhoneNumber(11L, "Mobile", "9876543210"))
            ),
            ContactMatch(
                contactId = 2L,
                lookupKey = "k2",
                displayName = "Amma Garu",
                phoneNumbers = listOf(PhoneNumber(12L, "Mobile", "9876543211"))
            )
        )

        val targetTerms = setOf("Amma")
        val filtered = ContactAliasResolver.filterExactMatches(candidates, targetTerms)

        // Neither is an exact match, so all candidates are retained for user disambiguation
        assertEquals("All candidates retained for disambiguation when none are exact", 2, filtered.size)
    }

    @Test
    fun testUntouchedSuffixesForLiteralNames() {
        // Names ending with Latin characters resembling suffixes (to, nu, du, ku) must remain untouched
        // because there is no preceding whitespace separator
        assertEquals("Santo", ContactAliasResolver.stripTeluguCaseSuffix("Santo"))
        assertEquals("Sanu", ContactAliasResolver.stripTeluguCaseSuffix("Sanu"))
        assertEquals("Chandu", ContactAliasResolver.stripTeluguCaseSuffix("Chandu"))
        assertEquals("Manku", ContactAliasResolver.stripTeluguCaseSuffix("Manku"))

        assertEquals("santo", ContactAliasResolver.normalizeForAliasLookup("Santo"))
        assertEquals("sanu", ContactAliasResolver.normalizeForAliasLookup("Sanu"))
        assertEquals("chandu", ContactAliasResolver.normalizeForAliasLookup("Chandu"))
        assertEquals("manku", ContactAliasResolver.normalizeForAliasLookup("Manku"))
    }
}
