package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.features.whatsapp.WhatsAppContactMatch
import com.example.features.whatsapp.WhatsAppContactResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsAppContactResolverUnitTest {

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.widget.TextView",
        isClickable: Boolean = false,
        isVisible: Boolean = true,
        pkgName: String = "com.whatsapp",
        bounds: Rect = Rect(10, 10, 100, 100)
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.text = text
            this.contentDescription = desc
            this.viewIdResourceName = resId
            this.className = className
            this.isClickable = isClickable
            this.isVisibleToUser = isVisible
            this.packageName = pkgName
            this.setBoundsInScreen(bounds)
        }
    }

    @Test
    fun testNormalization() {
        assertEquals("yashwanth", WhatsAppContactResolver.normalize("  Yashwanth! "))
        assertEquals("yashwanth reddy", WhatsAppContactResolver.normalize("Yashwanth   Reddy."))
        assertEquals("919876543210", WhatsAppContactResolver.normalizeDigits("+91 98765-43210"))
        assertEquals("9876543210", WhatsAppContactResolver.normalizeDigits("+91 98765-43210").takeLast(10))
    }

    @Test
    fun testVariantGeneration() {
        val variants = WhatsAppContactResolver.generateTargetVariants("Yashwanth", "+91 9876543210")
        assertTrue(variants.contains("yashwanth"))
        assertTrue(variants.contains("Yashwanth"))
        assertTrue(variants.contains("9876543210"))
        assertTrue(variants.contains("+919876543210"))
    }

    @Test
    fun test1_ExactContactResult() {
        val root = createNode(className = "android.widget.FrameLayout")

        val rowContainer = createNode(
            resId = "com.whatsapp:id/contact_row_container",
            className = "android.widget.RelativeLayout",
            isClickable = true
        )
        val nameNode = createNode(
            text = "Yashwanth",
            resId = "com.whatsapp:id/conversations_row_contact_name",
            className = "android.widget.TextView"
        )
        shadowOf(rowContainer).addChild(nameNode)
        shadowOf(root).addChild(rowContainer)

        val result = WhatsAppContactResolver.resolveSearchRow(
            root = root,
            targetContact = "Yashwanth"
        )

        assertTrue("Expected Found but was $result", result is WhatsAppContactMatch.Found)
        val found = result as WhatsAppContactMatch.Found
        assertEquals("Yashwanth", found.matchedName)
        assertEquals(1.0f, found.confidence, 0.01f)
        assertEquals("EXACT_NAME", found.matchType)
    }

    @Test
    fun test2_UnrelatedSnippetContainingSameName() {
        val root = createNode(className = "android.widget.FrameLayout")

        // 1. Group row with message snippet mentioning "Yashwanth"
        val groupRow = createNode(
            resId = "com.whatsapp:id/contact_row_container",
            isClickable = true
        )
        val groupName = createNode(
            text = "College Friends Group",
            resId = "com.whatsapp:id/conversations_row_contact_name"
        )
        val messageSnippet = createNode(
            text = "Yashwanth is coming tomorrow",
            resId = "com.whatsapp:id/conversations_row_message"
        )
        shadowOf(groupRow).addChild(groupName)
        shadowOf(groupRow).addChild(messageSnippet)
        shadowOf(root).addChild(groupRow)

        // 2. Direct Contact row for "Yashwanth"
        val contactRow = createNode(
            resId = "com.whatsapp:id/contact_row_container",
            isClickable = true
        )
        val directName = createNode(
            text = "Yashwanth",
            resId = "com.whatsapp:id/conversations_row_contact_name"
        )
        shadowOf(contactRow).addChild(directName)
        shadowOf(root).addChild(contactRow)

        val result = WhatsAppContactResolver.resolveSearchRow(
            root = root,
            targetContact = "Yashwanth"
        )

        assertTrue("Expected Found but was $result", result is WhatsAppContactMatch.Found)
        val found = result as WhatsAppContactMatch.Found
        assertEquals("Yashwanth", found.matchedName)
        assertEquals(contactRow, found.nodeToClick)
    }

    @Test
    fun test3_MultipleSameNameResults() {
        val root = createNode(className = "android.widget.FrameLayout")

        val row1 = createNode(resId = "com.whatsapp:id/contact_row_container", isClickable = true)
        val name1 = createNode(text = "Yashwanth", resId = "com.whatsapp:id/conversations_row_contact_name")
        shadowOf(row1).addChild(name1)
        shadowOf(root).addChild(row1)

        val row2 = createNode(resId = "com.whatsapp:id/contact_row_container", isClickable = true)
        val name2 = createNode(text = "Yashwanth", resId = "com.whatsapp:id/conversations_row_contact_name")
        shadowOf(row2).addChild(name2)
        shadowOf(root).addChild(row2)

        val result = WhatsAppContactResolver.resolveSearchRow(
            root = root,
            targetContact = "Yashwanth"
        )

        assertTrue("Expected Found for duplicate exact name matches", result is WhatsAppContactMatch.Found)
    }

    @Test
    fun test4_GroupNameCollision() {
        val root = createNode(className = "android.widget.FrameLayout")

        val groupRow = createNode(resId = "com.whatsapp:id/contact_row_container", isClickable = true)
        val groupName = createNode(text = "Yashwanth Family Group", resId = "com.whatsapp:id/conversations_row_contact_name")
        shadowOf(groupRow).addChild(groupName)
        shadowOf(root).addChild(groupRow)

        val personRow = createNode(resId = "com.whatsapp:id/contact_row_container", isClickable = true)
        val personName = createNode(text = "Yashwanth", resId = "com.whatsapp:id/conversations_row_contact_name")
        shadowOf(personRow).addChild(personName)
        shadowOf(root).addChild(personRow)

        val result = WhatsAppContactResolver.resolveSearchRow(
            root = root,
            targetContact = "Yashwanth"
        )

        assertTrue(result is WhatsAppContactMatch.Found)
        val found = result as WhatsAppContactMatch.Found
        assertEquals("Yashwanth", found.matchedName)
        assertEquals(personRow, found.nodeToClick)
    }

    @Test
    fun test5_WrongChatAfterClick() {
        val chatRoot = createNode(className = "android.widget.FrameLayout")

        val entry = createNode(text = "Type a message", resId = "com.whatsapp:id/entry", isClickable = true)
        shadowOf(chatRoot).addChild(entry)

        val headerTitle = createNode(text = "Ravi", resId = "com.whatsapp:id/conversation_contact_name", bounds = Rect(50, 60, 300, 120))
        shadowOf(chatRoot).addChild(headerTitle)

        // Contact requested was "Yashwanth", but opened chat is "Ravi"
        val isCorrect = WhatsAppContactResolver.isCorrectChatOpen(root = chatRoot, targetContact = "Yashwanth")
        assertFalse("Must fail when wrong chat is open", isCorrect)
    }

    @Test
    fun test6_CorrectChatVerification() {
        val chatRoot = createNode(className = "android.widget.FrameLayout")

        val entry = createNode(text = "Type a message", resId = "com.whatsapp:id/entry", isClickable = true)
        shadowOf(chatRoot).addChild(entry)

        val headerTitle = createNode(text = "Yashwanth", resId = "com.whatsapp:id/conversation_contact_name", bounds = Rect(50, 60, 300, 120))
        shadowOf(chatRoot).addChild(headerTitle)

        val isCorrect = WhatsAppContactResolver.isCorrectChatOpen(root = chatRoot, targetContact = "Yashwanth")
        assertTrue("Must pass when correct chat is open", isCorrect)
    }

    @Test
    fun test7_AmbiguousContact() {
        val root = createNode(className = "android.widget.FrameLayout")

        val row1 = createNode(resId = "com.whatsapp:id/contact_row_container", isClickable = true)
        val name1 = createNode(text = "Yashwanth Reddy", resId = "com.whatsapp:id/conversations_row_contact_name")
        shadowOf(row1).addChild(name1)
        shadowOf(root).addChild(row1)

        val row2 = createNode(resId = "com.whatsapp:id/contact_row_container", isClickable = true)
        val name2 = createNode(text = "Yashwanth Rao", resId = "com.whatsapp:id/conversations_row_contact_name")
        shadowOf(row2).addChild(name2)
        shadowOf(root).addChild(row2)

        val result = WhatsAppContactResolver.resolveSearchRow(
            root = root,
            targetContact = "Yashwanth"
        )

        assertTrue("Expected Ambiguous but was $result", result is WhatsAppContactMatch.Ambiguous)
        val ambiguous = result as WhatsAppContactMatch.Ambiguous
        assertTrue(ambiguous.candidateNames.contains("Yashwanth Reddy"))
        assertTrue(ambiguous.candidateNames.contains("Yashwanth Rao"))
    }

    @Test
    fun test8_SearchResultDelayedAppearance() {
        val emptyRoot = createNode(className = "android.widget.FrameLayout")
        val notFoundResult = WhatsAppContactResolver.resolveSearchRow(root = emptyRoot, targetContact = "Yashwanth")
        assertTrue("Expected NotFound on empty search results", notFoundResult is WhatsAppContactMatch.NotFound)
    }

    @Test
    fun test9_DuplicateSendPrevention() {
        val noEntryChatRoot = createNode(className = "android.widget.FrameLayout")
        val headerTitle = createNode(text = "Yashwanth", resId = "com.whatsapp:id/conversation_contact_name", bounds = Rect(50, 60, 300, 120))
        shadowOf(noEntryChatRoot).addChild(headerTitle)

        // If entry field is missing (e.g. read-only announcement or not a valid chat), chat verification fails
        val isCorrect = WhatsAppContactResolver.isCorrectChatOpen(root = noEntryChatRoot, targetContact = "Yashwanth")
        assertFalse("Must fail if entry field is missing to prevent blind sends", isCorrect)
    }

    @Test
    fun test10_DirectClickOptimization() {
        val chatListRoot = createNode(className = "android.widget.FrameLayout")

        val directRow = createNode(resId = "com.whatsapp:id/contact_row_container", isClickable = true)
        val directName = createNode(text = "Yashwanth", resId = "com.whatsapp:id/conversations_row_contact_name")
        shadowOf(directRow).addChild(directName)
        shadowOf(chatListRoot).addChild(directRow)

        val match = WhatsAppContactResolver.resolveVisibleChatRow(root = chatListRoot, targetContact = "Yashwanth")
        assertTrue("Direct click optimization must find visible contact row", match is WhatsAppContactMatch.Found)
        val found = match as WhatsAppContactMatch.Found
        assertEquals("Yashwanth", found.matchedName)
        assertEquals(directRow, found.nodeToClick)
    }

    @Test
    fun test11_PhoneticMatching_Yaswanth_vs_Yashwanth() {
        val root = createNode(className = "android.widget.FrameLayout")

        val contactRow = createNode(
            resId = "com.whatsapp:id/contact_row_container",
            isClickable = true
        )
        val directName = createNode(
            text = "Yashwanth",
            resId = "com.whatsapp:id/conversations_row_contact_name"
        )
        shadowOf(contactRow).addChild(directName)
        shadowOf(root).addChild(contactRow)

        // Target specified as "yaswanth" without 'h'
        val result = WhatsAppContactResolver.resolveSearchRow(
            root = root,
            targetContact = "yaswanth"
        )

        assertTrue("Phonetic match should find contact: $result", result is WhatsAppContactMatch.Found)
        val found = result as WhatsAppContactMatch.Found
        assertEquals("Yashwanth", found.matchedName)
        assertEquals(contactRow, found.nodeToClick)
    }

    @Test
    fun test12_PhoneticChatHeaderVerification() {
        val chatRoot = createNode(className = "android.widget.FrameLayout")

        val entry = createNode(text = "Type a message", resId = "com.whatsapp:id/entry", isClickable = true)
        shadowOf(chatRoot).addChild(entry)

        val headerTitle = createNode(
            text = "Yashwanth",
            resId = "com.whatsapp:id/conversation_contact_name",
            bounds = Rect(50, 60, 300, 120)
        )
        shadowOf(chatRoot).addChild(headerTitle)

        // Target is "yaswanth", chat opened has "Yashwanth"
        val isCorrect = WhatsAppContactResolver.isCorrectChatOpen(root = chatRoot, targetContact = "yaswanth")
        assertTrue("Must verify chat header when phonetic difference exists (yaswanth vs Yashwanth)", isCorrect)
    }

    @Test
    fun test13_PhoneticPartialHeaderVerification() {
        val chatRoot = createNode(className = "android.widget.FrameLayout")

        val entry = createNode(text = "Type a message", resId = "com.whatsapp:id/entry", isClickable = true)
        shadowOf(chatRoot).addChild(entry)

        val headerTitle = createNode(
            text = "Yashwanth Reddy",
            resId = "com.whatsapp:id/conversation_contact_name",
            bounds = Rect(50, 60, 300, 120)
        )
        shadowOf(chatRoot).addChild(headerTitle)

        // Target is "yaswanth", chat opened has "Yashwanth Reddy"
        val isCorrect = WhatsAppContactResolver.isCorrectChatOpen(root = chatRoot, targetContact = "yaswanth")
        assertTrue("Must verify chat header when target is substring/token of header with phonetic difference", isCorrect)
    }
}
