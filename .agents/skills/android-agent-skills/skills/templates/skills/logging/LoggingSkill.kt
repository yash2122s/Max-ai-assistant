package com.agentcore.skills.logging

import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.Immutable
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata

/**
 * Input for LoggingSkill.
 */
@Immutable
data class LoggingInput(
    /**
     * The log level.
     */
    val level: LogLevel = LogLevel.INFO,
    
    /**
     * The log tag.
     */
    val tag: String,
    
    /**
     * The log message.
     */
    val message: String,
    
    /**
     * Optional throwable to log.
     */
    val throwable: Throwable? = null,
    
    /**
     * Additional structured data to include.
     */
    val data: Map<String, Any> = emptyMap(),
)

/**
 * Output from LoggingSkill.
 */
@Immutable
data class LoggingOutput(
    /**
     * Whether the log was recorded.
     */
    val logged: Boolean,
    
    /**
     * Timestamp when the log was recorded.
     */
    val timestamp: Long,
    
    /**
     * The formatted log message.
     */
    val formattedMessage: String,
)

/**
 * Skill for logging messages through the agent system.
 * 
 * This skill provides a standardized way to log from within skills
 * without depending on platform-specific logging frameworks.
 */
class LoggingSkill(
    private val logger: Logger,
    private val messageFormatter: LogMessageFormatter = DefaultLogMessageFormatter(),
) : BaseSkill<LoggingInput, LoggingOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "core.logging"
        name = "Logging"
        description = "Logs messages through the agent logging system"
        category = SkillCategory.LOGGING
        tags("logging", "debug", "core")
        inputType = LoggingInput::class
        outputType = LoggingOutput::class
        isRetryable = false
        recommendedTimeoutMs = 1_000L
    }
    
    override suspend fun doExecute(
        input: LoggingInput,
        context: SkillContext,
    ): SkillResult<LoggingOutput> {
        val formattedMessage = messageFormatter.format(input, context)
        val timestamp = context.clock.currentTimeMillis()
        
        when (input.level) {
            LogLevel.VERBOSE -> logger.v(input.tag, formattedMessage, input.throwable)
            LogLevel.DEBUG -> logger.d(input.tag, formattedMessage, input.throwable)
            LogLevel.INFO -> logger.i(input.tag, formattedMessage, input.throwable)
            LogLevel.WARN -> logger.w(input.tag, formattedMessage, input.throwable)
            LogLevel.ERROR -> logger.e(input.tag, formattedMessage, input.throwable)
            LogLevel.WTF -> logger.wtf(input.tag, formattedMessage, input.throwable)
            LogLevel.NONE -> { /* Don't log */ }
        }
        
        return SkillResult.success(
            LoggingOutput(
                logged = input.level != LogLevel.NONE,
                timestamp = timestamp,
                formattedMessage = formattedMessage,
            )
        )
    }
}

/**
 * Interface for formatting log messages.
 */
interface LogMessageFormatter {
    fun format(input: LoggingInput, context: SkillContext): String
}

/**
 * Default message formatter.
 */
class DefaultLogMessageFormatter : LogMessageFormatter {
    
    override fun format(input: LoggingInput, context: SkillContext): String {
        val builder = StringBuilder()
        builder.append("[${context.traceId.take(8)}] ")
        builder.append(input.message)
        
        if (input.data.isNotEmpty()) {
            builder.append(" | ")
            builder.append(input.data.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
        
        return builder.toString()
    }
}

/**
 * JSON-style message formatter.
 */
class JsonLogMessageFormatter : LogMessageFormatter {
    
    override fun format(input: LoggingInput, context: SkillContext): String {
        val fields = mutableMapOf<String, Any>(
            "traceId" to context.traceId,
            "message" to input.message,
            "timestamp" to context.clock.currentTimeMillis(),
        )
        
        if (input.data.isNotEmpty()) {
            fields["data"] = input.data
        }
        
        // Simple JSON serialization (for production, use a proper JSON library)
        return buildString {
            append("{")
            fields.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(", ")
                append("\"$key\": ")
                when (value) {
                    is String -> append("\"$value\"")
                    is Map<*, *> -> {
                        append("{")
                        value.entries.forEachIndexed { i, (k, v) ->
                            if (i > 0) append(", ")
                            append("\"$k\": \"$v\"")
                        }
                        append("}")
                    }
                    else -> append("$value")
                }
            }
            append("}")
        }
    }
}

/**
 * Helper functions for creating LoggingInput.
 */
object Log {
    fun verbose(tag: String, message: String, data: Map<String, Any> = emptyMap()) = LoggingInput(
        level = LogLevel.VERBOSE,
        tag = tag,
        message = message,
        data = data,
    )
    
    fun debug(tag: String, message: String, data: Map<String, Any> = emptyMap()) = LoggingInput(
        level = LogLevel.DEBUG,
        tag = tag,
        message = message,
        data = data,
    )
    
    fun info(tag: String, message: String, data: Map<String, Any> = emptyMap()) = LoggingInput(
        level = LogLevel.INFO,
        tag = tag,
        message = message,
        data = data,
    )
    
    fun warn(tag: String, message: String, throwable: Throwable? = null) = LoggingInput(
        level = LogLevel.WARN,
        tag = tag,
        message = message,
        throwable = throwable,
    )
    
    fun error(tag: String, message: String, throwable: Throwable? = null) = LoggingInput(
        level = LogLevel.ERROR,
        tag = tag,
        message = message,
        throwable = throwable,
    )
}
