package com.example.automation.agent

import android.app.Application
import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.CancellationToken
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.service.PrunedNode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiExecutionSafetyTest {
    private lateinit var context: Context
    private val targetPackage = "com.example.target"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        MaxRuntimeManager.enableMax(context)
    }

    private fun node(
        text: String = "Search",
        editable: Boolean = false,
        bounds: Rect = Rect(0, 0, 100, 100)
    ) = PrunedNode(
        nodeId = "field", className = if (editable) "EditText" else "Button",
        resourceId = "$targetPackage:id/search", text = text,
        isEditable = editable, isClickable = true, boundsInScreen = bounds
    )

    private fun observation(
        nodes: List<PrunedNode> = listOf(node()),
        visibleText: String = "Search",
        pkg: String = targetPackage,
        image: ByteArray? = null
    ) = UiObservation(image, nodes, pkg, visibleText, listOf("Search"))

    private fun loop(
        action: UiAction,
        observe: suspend () -> UiObservation,
        dispatch: suspend (Context, UiAction) -> Boolean,
        decide: () -> Unit = {}
    ) = UiVerificationLoop(
        context = context,
        observationProvider = observe,
        actionExecutor = dispatch,
        decisionProvider = { _, _, history ->
            decide()
            Triple(
                if (history.isEmpty()) UiAgentReasoningResult.ActionDecision(action, "test")
                else UiAgentReasoningResult.FinishTask(true, "done", "test"),
                "test", "test"
            )
        },
        adaptiveWait = {}
    )

    @Test fun foregroundChangeDuringReasoningPreventsDispatch() = runBlocking {
        var reads = 0
        var dispatches = 0
        val loop = loop(
            UiAction(UiAction.Type.TAP, x = 10, y = 10),
            observe = { if (reads++ == 0) observation() else observation(pkg = "com.example.other") },
            dispatch = { _, _ -> dispatches++; true }
        )
        val result = loop.executeTask(UiTask(goal = "tap search", targetPackage = targetPackage, maxSteps = 1))
        assertEquals(0, dispatches)
        assertEquals("WRONG_FOREGROUND_PACKAGE", result.error)
        assertFalse(result.completionVerified)
    }

    @Test fun movedBoundsWithSameLabelsPreventStaleCoordinateDispatch() = runBlocking {
        var reads = 0
        var dispatches = 0
        val loop = loop(
            UiAction(UiAction.Type.TAP, x = 10, y = 10),
            observe = {
                if (reads++ == 0) observation()
                else observation(nodes = listOf(node(bounds = Rect(150, 150, 250, 250))))
            },
            dispatch = { _, _ -> dispatches++; true }
        )
        val result = loop.executeTask(UiTask(goal = "tap search", maxSteps = 1))
        assertEquals(0, dispatches)
        assertEquals("STALE_OBSERVATION", result.error)
    }

    @Test fun changedVisionOnlyFramePreventsStaleTap() = runBlocking {
        var reads = 0
        var dispatches = 0
        val loop = loop(
            UiAction(UiAction.Type.TAP, x = 10, y = 10),
            observe = { observation(nodes = emptyList(), image = byteArrayOf(if (reads++ == 0) 1 else 2)) },
            dispatch = { _, _ -> dispatches++; true }
        )
        val result = loop.executeTask(UiTask(goal = "tap canvas", maxSteps = 1))
        assertEquals(0, dispatches)
        assertEquals("STALE_OBSERVATION", result.error)
    }

    @Test fun maxDisabledDuringReasoningPreventsDispatch() = runBlocking {
        var dispatches = 0
        val loop = loop(
            UiAction(UiAction.Type.TAP, x = 10, y = 10),
            observe = { observation() },
            dispatch = { _, _ -> dispatches++; true },
            decide = { MaxRuntimeManager.disableMax(context) }
        )
        val result = loop.executeTask(UiTask(goal = "tap search", maxSteps = 1))
        assertEquals(0, dispatches)
        assertEquals("MAX_DISABLED", result.error)
    }

    @Test fun textElsewhereOnScreenDoesNotVerifyInput() = runBlocking {
        val loop = loop(
            UiAction(UiAction.Type.TYPE, text = "hello"),
            observe = { observation(nodes = listOf(node(text = "hello")), visibleText = "hello") },
            dispatch = { _, _ -> true }
        )
        val result = loop.executeTask(UiTask(goal = "type hello", targetPackage = targetPackage))
        assertFalse(result.success)
        assertEquals("ACTION_UNVERIFIED", result.error)
    }

    @Test fun editableFieldVerifiesInputEvenIfVisibleTextSummaryIsUnchanged() = runBlocking {
        var dispatched = false
        val loop = loop(
            UiAction(UiAction.Type.TYPE, text = "hello"),
            observe = {
                observation(nodes = listOf(node(text = if (dispatched) "hello" else "", editable = true)))
            },
            dispatch = { _, _ -> dispatched = true; true }
        )
        val result = loop.executeTask(UiTask(goal = "type hello", targetPackage = targetPackage))
        assertTrue(result.success)
        assertTrue(result.completionVerified)
        assertEquals(1, result.actionsTaken.size)
    }

    @Test fun textInDifferentEditableFieldDoesNotVerifyFocusedInput() = runBlocking {
        val focused = node(text = "", editable = true).copy(isFocused = true)
        val other = node(text = "hello", editable = true).copy(nodeId = "other", resourceId = "$targetPackage:id/other")
        val loop = loop(
            UiAction(UiAction.Type.TYPE, text = "hello"),
            observe = { observation(nodes = listOf(focused, other), visibleText = "hello") },
            dispatch = { _, _ -> true }
        )
        val result = loop.executeTask(UiTask(goal = "type hello", targetPackage = targetPackage))
        assertFalse(result.success)
        assertEquals("ACTION_UNVERIFIED", result.error)
    }

    @Test fun boundsChangeAfterTapIsObservableWithoutNewVisibleText() = runBlocking {
        var dispatched = false
        val loop = loop(
            UiAction(UiAction.Type.TAP, x = 10, y = 10),
            observe = {
                observation(nodes = listOf(node(bounds = if (dispatched) Rect(0, 0, 120, 100) else Rect(0, 0, 100, 100))))
            },
            dispatch = { _, _ -> dispatched = true; true }
        )
        val result = loop.executeTask(UiTask(goal = "open search", targetPackage = targetPackage))
        assertTrue(result.success)
        assertTrue(result.completionVerified)
    }

    @Test fun cancellationDuringFinalObservationRejectsCompletion() = runBlocking {
        val token = CancellationToken()
        var reads = 0
        val loop = loop(
            UiAction(UiAction.Type.SCREENSHOT),
            observe = {
                // First observation and post-action; second step observation and final evidence.
                if (++reads == 4) token.cancel()
                observation()
            },
            dispatch = { _, _ -> true }
        )
        val result = loop.executeTask(UiTask(goal = "inspect target", targetPackage = targetPackage), token)
        assertFalse(result.success)
        assertFalse(result.completionVerified)
        assertEquals("CANCELLED", result.error)
    }

    @Test fun cancellationDuringFreshPreDispatchObservationPreventsAction() = runBlocking {
        val token = CancellationToken()
        var reads = 0
        var dispatches = 0
        val loop = loop(
            UiAction(UiAction.Type.TAP, x = 10, y = 10),
            observe = { if (++reads == 2) token.cancel(); observation() },
            dispatch = { _, _ -> dispatches++; true }
        )
        val result = loop.executeTask(UiTask(goal = "tap search", maxSteps = 1), token)
        assertEquals(0, dispatches)
        assertEquals("CANCELLED", result.error)
    }

    @Test fun gestureRejectionAndCompletionHaveDefiniteOutcomes() = runBlocking {
        assertEquals(false, UiActionExecutor.awaitGestureCompletion { false })
        assertEquals(true, UiActionExecutor.awaitGestureCompletion { callback ->
            callback.onCompleted(null)
            true
        })
    }

    @Test fun acceptedGestureWithMissingCallbackIsUncertainNotRetryable() = runBlocking {
        assertNull(UiActionExecutor.awaitGestureCompletion(timeoutMs = 10L) { true })
    }

    @Test fun cancelledGestureIsUncertainNotRetryable() = runBlocking {
        assertNull(UiActionExecutor.awaitGestureCompletion { callback ->
            callback.onCancelled(null)
            true
        })
    }

    @Test fun outerTimeoutCannotBeSwallowedByGestureTimeoutHandler() = runBlocking {
        var continuedToFallback = false
        try {
            withTimeout(10L) {
                UiActionExecutor.awaitGestureCompletion(timeoutMs = 1_000L) { true }
                continuedToFallback = true
            }
            throw AssertionError("Outer timeout must propagate")
        } catch (_: TimeoutCancellationException) {
            // Expected. The caller cannot continue to another dispatch tier.
        }
        assertFalse(continuedToFallback)
    }
}
