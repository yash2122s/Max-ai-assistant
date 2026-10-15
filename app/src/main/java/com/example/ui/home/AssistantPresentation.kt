package com.example.ui.home

import com.example.automation.activity.AgentActivityState
import com.example.automation.activity.AgentStage
import com.example.network.ConnectionState
import com.example.voice.assistant.SessionState
import com.example.utils.InternalReasoningFilter
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus

enum class AssistantPhase {
    IDLE, CONNECTING, LISTENING, THINKING, STREAMING, SPEAKING, TOOL_EXECUTION,
    WAITING_FOR_INPUT, WAITING_FOR_APPROVAL, CANCELLING, COMPLETED, CANCELLED,
    FAILED, BLOCKED, UNVERIFIED
}

data class AssistantPresentation(
    val phase: AssistantPhase,
    val label: String,
    val detail: String? = null,
    val canStop: Boolean = false
)

/** A read-only projection of runtime evidence, never an execution authority. */
data class AssistantSnapshot(
    val enabled: Boolean = true,
    val connected: ConnectionState = ConnectionState.DISCONNECTED,
    val session: SessionState = SessionState.IDLE,
    val recording: Boolean = false,
    val thinking: Boolean = false,
    val streaming: Boolean = false,
    val playing: Boolean = false,
    val activity: AgentActivityState = AgentActivityState(),
    val terminal: AssistantPhase? = null,
    val detail: String? = null,
    val waitingQuestion: String? = null,
    val approvalQuestion: String? = null,
    val cancelling: Boolean = false,
    val responseSettled: Boolean = false
)

/** Helper to determine if a character is part of a word (Latin, Telugu/Indic, digits, combining marks). */
internal fun isWordCharacter(c: Char): Boolean {
    if (c.isLetterOrDigit()) return true
    val type = Character.getType(c)
    return type == Character.NON_SPACING_MARK.toInt() ||
           type == Character.COMBINING_SPACING_MARK.toInt() ||
           type == Character.ENCLOSING_MARK.toInt()
}

/** Check if character is an Indic/Unicode combining mark that attaches to the preceding consonant. */
internal fun isCombiningMark(c: Char): Boolean {
    val type = Character.getType(c)
    return type == Character.NON_SPACING_MARK.toInt() ||
           type == Character.COMBINING_SPACING_MARK.toInt() ||
           type == Character.ENCLOSING_MARK.toInt()
}

/** Buffer deltas cleanly; ensure word boundary space between tokens so words don't get glued together. */
fun appendResponseChunk(existing: String, chunk: String): String {
    if (existing.isEmpty()) return chunk
    if (chunk.isEmpty()) return existing
    if (existing.endsWith(" ") || existing.endsWith("\t") || existing.endsWith("\n") ||
        chunk.startsWith(" ") || chunk.startsWith("\t") || chunk.startsWith("\n")) {
        return existing + chunk
    }

    // Do NOT insert space if we are inside an unclosed tag like <thought> or <MAX_RETRIEVED_MEMORY>
    val lastOpen = existing.lastIndexOf('<')
    val lastClose = existing.lastIndexOf('>')
    if (lastOpen != -1 && lastOpen > lastClose) {
        return existing + chunk
    }

    val lastChar = existing.last()
    val firstChar = chunk.first()

    // Do not insert space before combining marks (vowel signs in Telugu, etc.)
    if (isCombiningMark(firstChar)) {
        return existing + chunk
    }

    // Do not insert space before attached punctuation or closing brackets/quotes
    if (firstChar in setOf('.', ',', '!', '?', ':', ';', ')', ']', '}', '\'', '’', '%', '-', '—')) {
        return existing + chunk
    }

    // Do not insert space right after opening brackets or quotes
    if (lastChar in setOf('(', '[', '{', '"', '“', '«', '/', '@', '#')) {
        return existing + chunk
    }

    // Insert space between words (alphanumeric, Telugu/Indic) or after sentence-ending punctuation
    val isAfterWord = isWordCharacter(lastChar)
    val isAfterPunctuation = lastChar in setOf('.', ',', '!', '?', ':', ';', ')', ']', '}', '"', '”')
    val isBeforeWord = isWordCharacter(firstChar)

    val needsSpace = (isAfterWord || isAfterPunctuation) && isBeforeWord
    return if (needsSpace) "$existing $chunk" else existing + chunk
}

internal fun visibleResponse(raw: String): String {
    // Walk the complete buffer so every private section is suppressed, including
    // a second section that is still waiting for its closing tag.  Incomplete
    // opening tags are also withheld; the raw buffer itself remains untouched.
    val tags = listOf("thought", "MAX_RETRIEVED_MEMORY")
    val visible = StringBuilder()
    var index = 0
    while (index < raw.length) {
        val opening = tags.firstOrNull { raw.regionMatches(index, "<$it>", 0, it.length + 2, ignoreCase = true) }
        if (opening != null) {
            val close = "</$opening>"
            val end = raw.indexOf(close, index + opening.length + 2, ignoreCase = true)
            if (end < 0) break
            index = end + close.length
            continue
        }
        if (raw[index] == '<' && tags.any { "<$it>".startsWith(raw.substring(index), ignoreCase = true) }) break
        visible.append(raw[index++])
    }
    // The shared sanitizer trims text and drops empty lines. Apply its existing
    // leak rules per line without destroying indentation or paragraph spacing.
    return visible.toString().lines().mapNotNull { line ->
        if (line.isBlank()) line else {
            InternalReasoningFilter.sanitize(line).takeIf { it.isNotEmpty() }?.let {
                line.takeWhile(Char::isWhitespace) + it + line.takeLastWhile(Char::isWhitespace)
            }
        }
    }.joinToString("\n")
}

internal fun resultPhase(result: ToolResult, approvalPending: Boolean = false): AssistantPhase = when {
    approvalPending -> AssistantPhase.WAITING_FOR_APPROVAL
    result.status in setOf(ToolResultStatus.NEEDS_INPUT, ToolResultStatus.USER_ACTION_REQUIRED,
        ToolResultStatus.PENDING_USER_ACTION) -> AssistantPhase.WAITING_FOR_INPUT
    result.errorCode?.contains("CANCEL", ignoreCase = true) == true -> AssistantPhase.CANCELLED
    result.toolName == "security_gate" || result.errorCode?.contains("BLOCK", ignoreCase = true) == true -> AssistantPhase.BLOCKED
    result.status == ToolResultStatus.ACCEPTED || (result.success && !result.isVerifiedSuccess) ||
        result.errorCode?.contains("UNVERIFIED", ignoreCase = true) == true -> AssistantPhase.UNVERIFIED
    result.isVerifiedSuccess -> AssistantPhase.COMPLETED
    else -> AssistantPhase.FAILED
}

fun assistantPresentation(snapshot: AssistantSnapshot): AssistantPresentation {
    val a = snapshot.activity
    val phase = when {
        !snapshot.enabled -> AssistantPhase.IDLE
        snapshot.cancelling -> AssistantPhase.CANCELLING
        snapshot.terminal != null -> snapshot.terminal
        snapshot.approvalQuestion != null -> AssistantPhase.WAITING_FOR_APPROVAL
        snapshot.waitingQuestion != null || a.stage == AgentStage.WAITING || a.isAmbiguous -> AssistantPhase.WAITING_FOR_INPUT
        a.stage == AgentStage.CANCELLED -> AssistantPhase.CANCELLED
        a.stage == AgentStage.FAILED -> AssistantPhase.FAILED
        a.active && a.stage in setOf(AgentStage.EXECUTING, AgentStage.OBSERVING, AgentStage.VERIFYING, AgentStage.PLANNING) -> AssistantPhase.TOOL_EXECUTION
        snapshot.playing -> AssistantPhase.SPEAKING
        !snapshot.responseSettled && (snapshot.streaming || snapshot.session == SessionState.STREAMING_TEXT) -> AssistantPhase.STREAMING
        snapshot.connected == ConnectionState.CONNECTING || snapshot.session == SessionState.CONNECTING -> AssistantPhase.CONNECTING
        !snapshot.responseSettled && (snapshot.thinking || snapshot.session in setOf(SessionState.WAITING_FOR_MODEL, SessionState.PROCESSING_AUDIO)) -> AssistantPhase.THINKING
        snapshot.recording || snapshot.session == SessionState.LISTENING -> AssistantPhase.LISTENING
        snapshot.responseSettled -> AssistantPhase.COMPLETED
        else -> AssistantPhase.IDLE
    }
    val label = when (phase) {
        AssistantPhase.IDLE -> "Ready"
        AssistantPhase.CONNECTING -> "Connecting"
        AssistantPhase.LISTENING -> "Listening"
        AssistantPhase.THINKING -> "Thinking"
        AssistantPhase.STREAMING -> "Responding"
        AssistantPhase.SPEAKING -> "Speaking"
        AssistantPhase.TOOL_EXECUTION -> "Working"
        AssistantPhase.WAITING_FOR_INPUT -> "Waiting for input"
        AssistantPhase.WAITING_FOR_APPROVAL -> "Approval needed"
        AssistantPhase.CANCELLING -> "Stopping"
        AssistantPhase.COMPLETED -> "Completed"
        AssistantPhase.CANCELLED -> "Cancelled"
        AssistantPhase.FAILED -> "Failed"
        AssistantPhase.BLOCKED -> "Blocked"
        AssistantPhase.UNVERIFIED -> "Unverified"
    }
    return AssistantPresentation(
        phase, label,
        when (phase) {
            AssistantPhase.WAITING_FOR_APPROVAL -> snapshot.approvalQuestion ?: snapshot.detail
            AssistantPhase.WAITING_FOR_INPUT -> snapshot.waitingQuestion ?: a.clarificationPrompt ?: snapshot.detail
            AssistantPhase.TOOL_EXECUTION -> listOfNotNull(a.currentAction, a.currentTarget).joinToString(" · ").ifBlank { a.currentReason }
            AssistantPhase.IDLE -> null
            else -> snapshot.detail
        },
        phase in setOf(AssistantPhase.LISTENING, AssistantPhase.THINKING, AssistantPhase.STREAMING,
            AssistantPhase.SPEAKING, AssistantPhase.TOOL_EXECUTION,
            AssistantPhase.WAITING_FOR_INPUT, AssistantPhase.WAITING_FOR_APPROVAL)
    )
}
