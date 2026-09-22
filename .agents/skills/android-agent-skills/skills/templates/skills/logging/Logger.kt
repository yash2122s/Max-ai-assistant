package com.agentcore.skills.logging

/**
 * Abstraction for logging.
 * 
 * This interface allows skills to log without depending on
 * platform-specific logging frameworks.
 */
interface Logger {
    /**
     * Logs a verbose message.
     */
    fun v(tag: String, message: String, throwable: Throwable? = null)
    
    /**
     * Logs a debug message.
     */
    fun d(tag: String, message: String, throwable: Throwable? = null)
    
    /**
     * Logs an info message.
     */
    fun i(tag: String, message: String, throwable: Throwable? = null)
    
    /**
     * Logs a warning message.
     */
    fun w(tag: String, message: String, throwable: Throwable? = null)
    
    /**
     * Logs an error message.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null)
    
    /**
     * Logs a "what a terrible failure" message.
     */
    fun wtf(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Log levels for filtering.
 */
enum class LogLevel(val priority: Int) {
    VERBOSE(1),
    DEBUG(2),
    INFO(3),
    WARN(4),
    ERROR(5),
    WTF(6),
    NONE(Int.MAX_VALUE),
}

/**
 * No-op logger implementation.
 */
object NoOpLogger : Logger {
    override fun v(tag: String, message: String, throwable: Throwable?) {}
    override fun d(tag: String, message: String, throwable: Throwable?) {}
    override fun i(tag: String, message: String, throwable: Throwable?) {}
    override fun w(tag: String, message: String, throwable: Throwable?) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
    override fun wtf(tag: String, message: String, throwable: Throwable?) {}
}

/**
 * Console logger implementation for debugging.
 */
class ConsoleLogger(
    private val minLevel: LogLevel = LogLevel.DEBUG,
) : Logger {
    
    override fun v(tag: String, message: String, throwable: Throwable?) {
        if (LogLevel.VERBOSE.priority >= minLevel.priority) {
            println("[V/$tag] $message")
            throwable?.printStackTrace()
        }
    }
    
    override fun d(tag: String, message: String, throwable: Throwable?) {
        if (LogLevel.DEBUG.priority >= minLevel.priority) {
            println("[D/$tag] $message")
            throwable?.printStackTrace()
        }
    }
    
    override fun i(tag: String, message: String, throwable: Throwable?) {
        if (LogLevel.INFO.priority >= minLevel.priority) {
            println("[I/$tag] $message")
            throwable?.printStackTrace()
        }
    }
    
    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (LogLevel.WARN.priority >= minLevel.priority) {
            println("[W/$tag] $message")
            throwable?.printStackTrace()
        }
    }
    
    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (LogLevel.ERROR.priority >= minLevel.priority) {
            System.err.println("[E/$tag] $message")
            throwable?.printStackTrace()
        }
    }
    
    override fun wtf(tag: String, message: String, throwable: Throwable?) {
        if (LogLevel.WTF.priority >= minLevel.priority) {
            System.err.println("[WTF/$tag] $message")
            throwable?.printStackTrace()
        }
    }
}

/**
 * In-memory logger for testing.
 * Captures all log entries for verification.
 */
class InMemoryLogger : Logger {
    
    private val _entries = mutableListOf<LogEntry>()
    val entries: List<LogEntry> get() = _entries.toList()
    
    data class LogEntry(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    override fun v(tag: String, message: String, throwable: Throwable?) {
        _entries.add(LogEntry(LogLevel.VERBOSE, tag, message, throwable))
    }
    
    override fun d(tag: String, message: String, throwable: Throwable?) {
        _entries.add(LogEntry(LogLevel.DEBUG, tag, message, throwable))
    }
    
    override fun i(tag: String, message: String, throwable: Throwable?) {
        _entries.add(LogEntry(LogLevel.INFO, tag, message, throwable))
    }
    
    override fun w(tag: String, message: String, throwable: Throwable?) {
        _entries.add(LogEntry(LogLevel.WARN, tag, message, throwable))
    }
    
    override fun e(tag: String, message: String, throwable: Throwable?) {
        _entries.add(LogEntry(LogLevel.ERROR, tag, message, throwable))
    }
    
    override fun wtf(tag: String, message: String, throwable: Throwable?) {
        _entries.add(LogEntry(LogLevel.WTF, tag, message, throwable))
    }
    
    fun clear() {
        _entries.clear()
    }
    
    fun hasEntry(level: LogLevel, tagContains: String? = null, messageContains: String? = null): Boolean {
        return _entries.any { entry ->
            entry.level == level &&
                (tagContains == null || entry.tag.contains(tagContains)) &&
                (messageContains == null || entry.message.contains(messageContains))
        }
    }
    
    fun getEntriesWithLevel(level: LogLevel): List<LogEntry> {
        return _entries.filter { it.level == level }
    }
}

/**
 * Composite logger that delegates to multiple loggers.
 */
class CompositeLogger(
    private val loggers: List<Logger>,
) : Logger {
    
    constructor(vararg loggers: Logger) : this(loggers.toList())
    
    override fun v(tag: String, message: String, throwable: Throwable?) {
        loggers.forEach { it.v(tag, message, throwable) }
    }
    
    override fun d(tag: String, message: String, throwable: Throwable?) {
        loggers.forEach { it.d(tag, message, throwable) }
    }
    
    override fun i(tag: String, message: String, throwable: Throwable?) {
        loggers.forEach { it.i(tag, message, throwable) }
    }
    
    override fun w(tag: String, message: String, throwable: Throwable?) {
        loggers.forEach { it.w(tag, message, throwable) }
    }
    
    override fun e(tag: String, message: String, throwable: Throwable?) {
        loggers.forEach { it.e(tag, message, throwable) }
    }
    
    override fun wtf(tag: String, message: String, throwable: Throwable?) {
        loggers.forEach { it.wtf(tag, message, throwable) }
    }
}
