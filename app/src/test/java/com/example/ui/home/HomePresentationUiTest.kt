package com.example.ui.home

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.example.automation.activity.AgentActivityState
import com.example.automation.activity.AgentStage
import com.example.ui.components.AgentActivityCard
import com.example.ui.theme.MaxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Isolated synthetic components: never construct HomeViewModel/MyApplication. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePresentationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun idleRendersWithVoiceControl() {
        compose.setContent { HomePreviewContent(AssistantPhase.IDLE) }
        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start voice input").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/max-home-idle.png")
    }

    @Test fun streamingAllowsDraftAndStopWithoutSending() {
        val phase = mutableStateOf(AssistantPhase.STREAMING)
        var stops = 0
        compose.setContent { HomePreviewContent(phase.value) { stops++; phase.value = AssistantPhase.CANCELLING } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/max-home-streaming.png")
        compose.onNodeWithContentDescription("Message MAX").performTextInput("A draft\nfor later")
        compose.onNodeWithContentDescription("Send message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop task").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Cancelling").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Message MAX").assertTextContains("A draft\nfor later")
        assertEquals(1, stops)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/max-home-stopping.png")
    }

    @Test fun approvalAcceptsMultilineReply() {
        compose.setContent { HomePreviewContent(AssistantPhase.WAITING_FOR_APPROVAL) }
        compose.onNodeWithText("Approval needed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message MAX").performTextInput("Allow\nthis reminder")
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed().assertIsEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/max-home-approval.png")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithContentDescription("Message MAX").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun failureAndCancellationHaveNoRunningControls() {
        val phase = mutableStateOf(AssistantPhase.FAILED)
        compose.setContent { HomePreviewContent(phase.value) }
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop task").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/max-home-error.png")
        compose.runOnIdle { phase.value = AssistantPhase.CANCELLED }
        compose.onNodeWithText("Cancelled").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop task").assertDoesNotExist()
    }

    @Test fun terminalActivityCardDoesNotReviveStaleInputPrompt() {
        compose.setContent {
            MaxTheme {
                AgentActivityCard(
                    presentation = assistantPresentation(AssistantSnapshot(terminal = AssistantPhase.FAILED)),
                    activityState = AgentActivityState(stage = AgentStage.FAILED,
                        isAmbiguous = true, goal = "Create a reminder"),
                    onStopTask = {})
            }
        }
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("Input needed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop Task").assertDoesNotExist()
    }
}
