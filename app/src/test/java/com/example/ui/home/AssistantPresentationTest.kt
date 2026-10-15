package com.example.ui.home

import com.example.automation.activity.AgentActivityState
import com.example.automation.activity.AgentStage
import com.example.network.ConnectionState
import com.example.voice.assistant.SessionState
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import org.junit.Assert.*
import org.junit.Test

class AssistantPresentationTest {
    @Test fun playbackOwnsSpeakingNotTextChunks() {
        assertEquals(AssistantPhase.STREAMING, assistantPresentation(AssistantSnapshot(
            connected = ConnectionState.CONNECTED, streaming = true)).phase)
        assertEquals(AssistantPhase.SPEAKING, assistantPresentation(AssistantSnapshot(
            streaming = true, playing = true)).phase)
    }

    @Test fun terminalOverridesStaleModelFlagsAndNewTurnCanReset() {
        val stale = AssistantSnapshot(thinking = true, session = SessionState.WAITING_FOR_MODEL,
            terminal = AssistantPhase.UNVERIFIED, detail = "No verification evidence")
        assertEquals(AssistantPhase.UNVERIFIED, assistantPresentation(stale).phase)
        assertEquals(AssistantPhase.THINKING, assistantPresentation(stale.copy(terminal = null,
            detail = null)).phase)
    }

    @Test fun normalTurnCompleteSnapshotIsIdle() {
        assertEquals(AssistantPhase.IDLE, assistantPresentation(AssistantSnapshot(
            session = SessionState.IDLE, terminal = null)).phase)
    }

    @Test fun cancellationBeatsStreamingAndApproval() {
        assertEquals(AssistantPhase.CANCELLING, assistantPresentation(AssistantSnapshot(
            streaming = true, approvalQuestion = "Allow?", cancelling = true)).phase)
    }

    @Test fun terminalBeatsStaleQuestion() {
        assertEquals(AssistantPhase.FAILED, assistantPresentation(AssistantSnapshot(
            waitingQuestion = "Which?", terminal = AssistantPhase.FAILED)).phase)
    }

    @Test fun splitReasoningTagsAreFilteredAcrossChunks() {
        var text = ""
        text = appendResponseChunk(text, "hello <tho")
        text = appendResponseChunk(text, "ught>private")
        text = appendResponseChunk(text, "</thought> world")
        assertEquals("hello  world", visibleResponse(text))
    }

    @Test fun visibleResponsePreservesWhitespaceAndHidesEveryPrivateSection() {
        assertEquals("  hello\n  world  ", visibleResponse("  hello\n <thought>x</thought> world  "))
        assertEquals("beforeafter", visibleResponse("before<thought>x</thought><MAX_RETRIEVED_MEMORY>y</MAX_RETRIEVED_MEMORY>after"))
        assertEquals("public  tail ", visibleResponse("public <thought>one</thought> tail <MAX_RETRIEVED_MEMORY>two"))
    }

    @Test fun visibleResponseHidesEveryPartialPrivateOpeningPrefix() {
        val prefixes = listOf("thought", "MAX_RETRIEVED_MEMORY").flatMap { tag ->
            (1..tag.length + 1).map { "<${tag.take(it)}" }
        }
        for (prefix in prefixes) {
            assertEquals("safe ", visibleResponse("safe $prefix"))
        }
    }

    @Test fun settledResponseSuppressesStaleThinkingAndRecordingDoesNotMaskStreaming() {
        assertEquals(AssistantPhase.LISTENING, assistantPresentation(AssistantSnapshot(
            thinking = true, recording = true, responseSettled = true)).phase)
        assertEquals(AssistantPhase.COMPLETED, assistantPresentation(AssistantSnapshot(
            thinking = true, responseSettled = true)).phase)
        assertEquals(AssistantPhase.STREAMING, assistantPresentation(AssistantSnapshot(
            thinking = true, recording = true, streaming = true)).phase)
        assertEquals(AssistantPhase.SPEAKING, assistantPresentation(AssistantSnapshot(
            thinking = true, recording = true, streaming = true, playing = true)).phase)
    }

    @Test fun disabledStateWinsOverRuntimeFlags() {
        assertEquals(AssistantPhase.IDLE, assistantPresentation(AssistantSnapshot(
            enabled = false, terminal = AssistantPhase.FAILED, playing = true)).phase)
    }

    @Test fun codeAndWordBoundariesSurviveStreamingDeltas() {
        val chunks = listOf("Hello ", "world\n\n```kotlin\n", "    val n = 1\n", "\n    println(n)\n```")
        var raw = ""
        for (chunk in chunks) raw = appendResponseChunk(raw, chunk)
        assertEquals(chunks.joinToString(""), raw)
        assertEquals(raw, visibleResponse(raw))
    }

    @Test fun resultPhaseReflectsContractOutcomes() {
        val accepted = ToolResult(true, "tool", status = ToolResultStatus.ACCEPTED)
        val unverified = ToolResult(true, "tool", verificationRequired = true)
        val verified = ToolResult.success("tool").copy(verificationRequired = false)
        val waiting = ToolResult.needsInput("tool", "Which?")
        val blocked = ToolResult.failure("security_gate", "BLOCKED", "No")
        assertEquals(AssistantPhase.UNVERIFIED, resultPhase(accepted))
        assertEquals(AssistantPhase.UNVERIFIED, resultPhase(unverified))
        assertEquals(AssistantPhase.COMPLETED, resultPhase(verified))
        assertEquals(AssistantPhase.WAITING_FOR_INPUT, resultPhase(waiting))
        assertEquals(AssistantPhase.BLOCKED, resultPhase(blocked))
    }

    @Test fun legitimateOutputAfterTerminalCanStillPresent() {
        assertEquals(AssistantPhase.STREAMING, assistantPresentation(AssistantSnapshot(
            terminal = null, streaming = true)).phase)
    }

    @Test fun approvalAndQuestionTakePriorityOverWork() {
        val running = AssistantSnapshot(activity = AgentActivityState(active = true,
            stage = AgentStage.EXECUTING), thinking = true)
        assertEquals(AssistantPhase.TOOL_EXECUTION, assistantPresentation(running).phase)
        assertEquals(AssistantPhase.WAITING_FOR_INPUT,
            assistantPresentation(running.copy(waitingQuestion = "Which contact?")).phase)
        val approval = assistantPresentation(running.copy(approvalQuestion = "Allow?"))
        assertEquals(AssistantPhase.WAITING_FOR_APPROVAL, approval.phase)
        assertEquals("Allow?", approval.detail)
        assertTrue(approval.canStop)
    }

    @Test fun cancellationThenActualOutcomeAndReconnect() {
        val running = AssistantSnapshot(thinking = true, cancelling = true)
        assertEquals(AssistantPhase.CANCELLING, assistantPresentation(running).phase)
        assertEquals(AssistantPhase.CANCELLED, assistantPresentation(running.copy(
            cancelling = false, terminal = AssistantPhase.CANCELLED)).phase)
        assertEquals(AssistantPhase.CONNECTING, assistantPresentation(AssistantSnapshot(
            connected = ConnectionState.CONNECTING)).phase)
    }

    @Test fun failureAndBlockedAreNotSuccess() {
        for (phase in listOf(AssistantPhase.FAILED, AssistantPhase.BLOCKED,
            AssistantPhase.UNVERIFIED, AssistantPhase.CANCELLED)) {
            assertEquals(phase, assistantPresentation(AssistantSnapshot(terminal = phase,
                activity = AgentActivityState(stage = AgentStage.COMPLETED))).phase)
        }
    }

    @Test fun englishAndTenglishDeltasPreserveProperWordSpacing() {
        // Tenglish stream without leading spaces
        val tenglishChunks = listOf("Nenu", "meeku", "sahayam", "cheyagalanu.")
        var assembledTenglish = ""
        for (chunk in tenglishChunks) {
            assembledTenglish = appendResponseChunk(assembledTenglish, chunk)
        }
        assertEquals("Nenu meeku sahayam cheyagalanu.", assembledTenglish)

        // English stream with punctuation
        val englishChunks = listOf("Hello,", "how", "are", "you", "today?")
        var assembledEnglish = ""
        for (chunk in englishChunks) {
            assembledEnglish = appendResponseChunk(assembledEnglish, chunk)
        }
        assertEquals("Hello, how are you today?", assembledEnglish)
    }

    @Test fun teluguUnicodeDeltasPreserveCombiningMarksAndWordSpacing() {
        // Telugu words with vowel signs (matras) and halant
        val teluguChunks = listOf("నేను", "మీకు", "సహాయం", "చేస్తాను.")
        var assembledTelugu = ""
        for (chunk in teluguChunks) {
            assembledTelugu = appendResponseChunk(assembledTelugu, chunk)
        }
        assertEquals("నేను మీకు సహాయం చేస్తాను.", assembledTelugu)

        // Sub-character combining mark attachment (no spurious spaces before vowel signs)
        var matraAssembly = ""
        matraAssembly = appendResponseChunk(matraAssembly, "చ")
        matraAssembly = appendResponseChunk(matraAssembly, "ే")
        assertEquals("చే", matraAssembly)
    }
}
