package com.example.share

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class ReminderDraft(val title: String, val date: String, val time: String)

internal object ReminderDraftParser {
    private val month = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
    private val dates = Regex("""\b(?:\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[/-]\d{1,2}[/-]\d{4}|\d{1,2}\s+$month\s+\d{4}|$month\s+\d{1,2},?\s+\d{4}|today|tomorrow)\b""", RegexOption.IGNORE_CASE)
    private val times = Regex("""(?<![\d:])(?:\d{1,2}(?::\d{2})?\s*[ap]\.?m\.?|\d{1,2}:\d{2})(?![\w:])""", RegexOption.IGNORE_CASE)
    private val dateFormats = listOf("yyyy-M-d", "d/M/yyyy", "d-M-yyyy", "d MMM yyyy", "MMM d yyyy")

    private fun format(pattern: String, zone: TimeZone) = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
        isLenient = false
        timeZone = zone
    }

    private fun parse(value: String, pattern: String, zone: TimeZone): Date? {
        val position = ParsePosition(0)
        val result = format(pattern, zone).parse(value, position)
        return result?.takeIf { position.index == value.length }
    }

    fun extract(text: String, now: Long, zone: TimeZone): ReminderDraft {
        val foundDates = dates.findAll(text).map { match ->
            val value = match.value.replace(",", "").replace(Regex("""\s+"""), " ")
            val day = when (value.lowercase(Locale.ENGLISH)) {
                "today", "tomorrow" -> Calendar.getInstance(zone).apply {
                    timeInMillis = now
                    if (value.equals("tomorrow", true)) add(Calendar.DATE, 1)
                }.time
                else -> dateFormats.firstNotNullOfOrNull { parse(value, it, zone) }
            }
            day?.let { format("yyyy-MM-dd", zone).format(it) }
        }.toSet()
        val foundTimes = times.findAll(text).map { match ->
            val value = match.value.replace(Regex("""[.\s]"""), "").uppercase(Locale.ENGLISH)
            listOf("h:mma", "ha", "H:mm").firstNotNullOfOrNull { parse(value, it, zone) }
                ?.let { format("HH:mm", zone).format(it) }
        }.toSet()
        val lines = text.lineSequence().map { it.trim() }.filter { it.length >= 3 }.toList()
        val title = lines.firstOrNull { Regex("""\b(interview|exam|meeting|appointment|deadline|workshop)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: lines.firstOrNull { !dates.containsMatchIn(it) && !times.containsMatchIn(it) }.orEmpty()
        return ReminderDraft(title.take(160), foundDates.singleOrNull().orEmpty(), foundTimes.singleOrNull().orEmpty())
    }

    private val leadInstruction = Regex(
        """(?:remind me\s+)?(\d{1,5})\s*(min(?:ute)?s?|h(?:ou)?rs?|hours?)\s+(?:before(?:hand)?|early|mundu|mundhu)(?:\s+remind\s+(?:chey|cheyyi))?[.!]?""",
        RegexOption.IGNORE_CASE
    )

    private fun leadMinutes(input: String): Int? {
        val value = input.trim()
        val minutes = value.toIntOrNull() ?: run {
            val match = leadInstruction.matchEntire(value) ?: return null
            val amount = match.groupValues[1].toInt()
            if (match.groupValues[2].startsWith("h", ignoreCase = true)) amount * 60 else amount
        }
        return minutes.takeIf { it in 0..10080 }
    }

    fun trigger(date: String, time: String, minutes: String, zone: TimeZone): Long? {
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date) || !Regex("""\d{2}:\d{2}""").matches(time)) return null
        val offset = leadMinutes(minutes) ?: return null
        return parse("$date $time", "yyyy-MM-dd HH:mm", zone)?.time?.minus(offset * 60_000L)
    }
}
