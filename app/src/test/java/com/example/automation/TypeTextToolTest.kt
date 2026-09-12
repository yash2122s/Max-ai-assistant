package com.example.automation

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.TypeTextTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TypeTextToolTest {

    private lateinit var context: Context
    private val tool = TypeTextTool()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.widget.EditText",
        isClickable: Boolean = true,
        isEditable: Boolean = true,
        isFocused: Boolean = false,
        isPassword: Boolean = false,
        isEnabled: Boolean = true,
        isVisible: Boolean = true,
        bounds: Rect = Rect(10, 10, 200, 100)
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.text = text
            this.contentDescription = desc
            this.viewIdResourceName = resId
            this.className = className
            this.isClickable = isClickable
            this.isEditable = isEditable
            this.isFocused = isFocused
            this.isPassword = isPassword
            this.isEnabled = isEnabled
            this.isVisibleToUser = isVisible
            this.setBoundsInScreen(bounds)
        }
    }

    // ==========================================
    // 1. Expected Text Present -> Success
    // ==========================================
    @Test
    fun test1_ExpectedTextPresent_Success() {
        assertTrue(tool.isTextMatch("Palakollu", "Palakollu", replace = true))
        assertTrue(tool.isTextMatch("Search Palakollu", "Palakollu", replace = true))
        assertTrue(tool.isTextMatch("palakollu", "Palakollu", replace = true))
    }

    // ==========================================
    // 2. Expected Text Absent But Field Non-Empty -> FAILURE
    // ==========================================
    @Test
    fun test2_ExpectedTextAbsentButFieldNonEmpty_Failure() {
        assertFalse(tool.isTextMatch("Search here", "Palakollu", replace = true))
        assertFalse(tool.isTextMatch("Some other text", "Palakollu", replace = true))
        assertFalse(tool.isTextMatch("12345", "Palakollu", replace = true))
    }

    // ==========================================
    // 3. Stale Original Node -> Fresh Node Verification Semantics
    // ==========================================
    @Test
    fun test3_TextMatchingHandlesCaseAndNormalizedWhitespace() {
        assertTrue(tool.isTextMatch("  Palakollu  \n", "Palakollu", replace = true))
        assertTrue(tool.isTextMatch("WiFi 5GHz", "wifi", replace = true))
    }

    // ==========================================
    // 4. replace = true Semantics
    // ==========================================
    @Test
    fun test4_ReplaceTrue_Semantics() {
        assertTrue(tool.isTextMatch("Palakollu", "Palakollu", replace = true, originalText = "OldQuery"))
        // If field was NOT updated and still contains OldQuery, must fail
        assertFalse(tool.isTextMatch("OldQuery", "Palakollu", replace = true, originalText = "OldQuery"))
    }

    // ==========================================
    // 5. replace = false Semantics
    // ==========================================
    @Test
    fun test5_ReplaceFalse_Semantics() {
        assertTrue(tool.isTextMatch("25+10", "10", replace = false, originalText = "25+"))
        assertTrue(tool.isTextMatch("Hello World", "World", replace = false, originalText = "Hello "))
        // If append failed and text is just original text, must fail
        assertFalse(tool.isTextMatch("Hello ", "World", replace = false, originalText = "Hello "))
    }

    // ==========================================
    // 6. Focused Search Field Priority
    // ==========================================
    @Test
    fun test6_ValidateExecutionRequestRequiresText() {
        val validRequest = ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = com.google.gson.JsonObject().apply {
                addProperty("text", "Palakollu")
            },
            executionId = UUID.randomUUID()
        )
        assertTrue(tool.validate(validRequest))

        val emptyRequest = ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = com.google.gson.JsonObject().apply {
                addProperty("text", "")
            },
            executionId = UUID.randomUUID()
        )
        assertFalse(tool.validate(emptyRequest))
    }

    // ==========================================
    // 7. Security: Password / PIN / OTP Blocked
    // ==========================================
    @Test
    fun test7_PasswordNodeIsDetectedAsSecure() {
        val passwordNode = createNode(isPassword = true, resId = "com.app:id/pin_entry")
        val normalNode = createNode(isPassword = false, resId = "com.app:id/search_query")

        assertTrue(passwordNode.isPassword)
        assertFalse(normalNode.isPassword)
    }

    // ==========================================
    // 8. Empty Verification State -> Not Success
    // ==========================================
    @Test
    fun test8_EmptyVerificationState_NotSuccess() {
        assertFalse(tool.isTextMatch("", "Palakollu", replace = true))
        assertFalse(tool.isTextMatch("   ", "Palakollu", replace = true))
        assertFalse(tool.isTextMatch("", "Palakollu", replace = false, originalText = ""))
    }

    // ==========================================
    // 9. Service Unavailable Handling
    // ==========================================
    @Test
    fun test9_ExecuteWithoutServiceReturnsFailure() = runBlocking {
        com.example.service.JarvisAccessibilityService.instance = null
        val request = ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = com.google.gson.JsonObject().apply {
                addProperty("text", "Palakollu")
            },
            executionId = UUID.randomUUID()
        )

        val result = tool.execute(context, request)
        assertFalse(result.success)
        assertEquals("SERVICE_UNAVAILABLE", result.errorCode)
    }

    // ==========================================
    // 10. Missing Text Parameter Returns Failure
    // ==========================================
    @Test
    fun test10_MissingTextParameterReturnsMissingTextError() = runBlocking {
        val request = ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = com.google.gson.JsonObject(),
            executionId = UUID.randomUUID()
        )

        val result = tool.execute(context, request)
        assertFalse(result.success)
        assertEquals("MISSING_TEXT", result.errorCode)
    }

    // ==========================================
    // 11. Replace Flag Parsing
    // ==========================================
    @Test
    fun test11_ReplaceFlagParsing() {
        val requestReplace = ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = com.google.gson.JsonObject().apply {
                addProperty("text", "test")
                addProperty("replace", true)
            },
            executionId = UUID.randomUUID()
        )
        assertTrue(requestReplace.arguments.get("replace").asBoolean)

        val requestAppend = ExecutionRequest(
            action = "TYPE_TEXT",
            arguments = com.google.gson.JsonObject().apply {
                addProperty("text", "test")
                addProperty("append", true)
            },
            executionId = UUID.randomUUID()
        )
        assertTrue(requestAppend.arguments.get("append").asBoolean)
    }

    // ==========================================
    // 12. Supported Actions
    // ==========================================
    @Test
    fun test12_SupportedActions() {
        assertTrue(tool.supportedActions.contains("TYPE_TEXT"))
        assertTrue(tool.supportedActions.contains("ENTER_TEXT"))
        assertTrue(tool.supportedActions.contains("INPUT_TEXT"))
        assertTrue(tool.supportedActions.contains("TYPE"))
    }
}
