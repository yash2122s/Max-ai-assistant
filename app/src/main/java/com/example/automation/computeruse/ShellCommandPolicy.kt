package com.example.automation.computeruse

import android.util.Log
import java.util.regex.Pattern

/**
 * Centralized security policy and allowlist for ADB/Shizuku shell commands.
 * Blocks shell injection, privilege escalation, and unauthorized/destructive commands.
 */
object ShellCommandPolicy {
    private const val TAG = "ShellCommandPolicy"

    private val ALLOWED_PATTERNS = listOf(
        Pattern.compile("""^input tap \d{1,5} \d{1,5}$"""),
        Pattern.compile("""^input swipe \d{1,5} \d{1,5} \d{1,5} \d{1,5}( \d{1,6})?$"""),
        Pattern.compile("""^input keyevent (\d{1,4}|KEYCODE_[A-Z0-9_]+)$"""),
        Pattern.compile("""^input text [a-zA-Z0-9_%\s\-\.\,\@\:\/]+$"""),
        Pattern.compile("""^screencap -p [a-zA-Z0-9_\-\.\/]+$"""),
        Pattern.compile("""^chmod 666 [a-zA-Z0-9_\-\.\/]+$"""),
        Pattern.compile("""^am force-stop [a-zA-Z0-9_\.]+$"""),
        Pattern.compile("""^am start [a-zA-Z0-9_\-\.\s\/]+$"""),
        Pattern.compile("""^wm dismiss-keyguard$"""),
        Pattern.compile("""^pm list packages( -[a-z]+)*$""")
    )

    private val BLOCKED_SUBSTRINGS = listOf(
        ";", "|", "`", "$", "rm ", "rmdir", "mkfs", "reboot", "su ", "dd ",
        "chmod 777", "chmod 755 /", "pm uninstall", "setprop", "mount ", "sh "
    )

    /**
     * Validates whether a single shell command or compound (&&) command is authorized.
     */
    fun isCommandAllowed(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false

        // Check for compound command with &&
        if (trimmed.contains("&&")) {
            val subCommands = trimmed.split("&&").map { it.trim() }
            return subCommands.all { isSingleCommandAllowed(it) }
        }

        return isSingleCommandAllowed(trimmed)
    }

    private fun isSingleCommandAllowed(cmd: String): Boolean {
        val lower = cmd.lowercase()

        // 1. Check blocked keywords and injection characters
        for (blocked in BLOCKED_SUBSTRINGS) {
            if (lower.contains(blocked)) {
                Log.w(TAG, "[POLICY_REJECT] Command contains forbidden token '$blocked': '$cmd'")
                return false
            }
        }

        // 2. Must match at least one allowed regex pattern
        val matches = ALLOWED_PATTERNS.any { it.matcher(cmd).matches() }
        if (!matches) {
            Log.w(TAG, "[POLICY_REJECT] Command not matched by any allowlist pattern: '$cmd'")
            return false
        }

        return true
    }
}
