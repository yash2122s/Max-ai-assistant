package com.example.share

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ReminderDraftTest {
    private val zone = TimeZone.getTimeZone("Asia/Kolkata")
    private val now = 1795545000000L

    @Test fun interviewProducesEditableLocalDateAndTime() {
        val draft = ReminderDraftParser.extract("ACME technical interview\n27 November 2026 at 3:00 PM", now, zone)
        assertEquals(ReminderDraft("ACME technical interview", "2026-11-27", "15:00"), draft)
        assertEquals(1795770000000L, ReminderDraftParser.trigger(draft.date, draft.time, "30", zone))
    }

    @Test fun ambiguousFieldsStayEmpty() {
        val draft = ReminderDraftParser.extract("Interview registration 25/11/2026 at 9 AM\nMeeting 26/11/2026 at 10 AM", now, zone)
        assertEquals("", draft.date)
        assertEquals("", draft.time)
    }

    @Test fun plainLanguageLeadTimeMatchesNumericMinutes() {
        fun alert(lead: String) = ReminderDraftParser.trigger("2026-11-27", "15:00", lead, zone)
        assertEquals(alert("30"), alert("30 minutes mundu remind chey"))
        assertEquals(alert("60"), alert("remind me 1 hour before"))
        assertNull(alert("30 minutes after"))
    }

    @Test fun invalidInputsCannotSchedule() {
        for (date in listOf("2026-02-31", "2026-2-01", "")) assertNull(ReminderDraftParser.trigger(date, "15:00", "0", zone))
        for (minutes in listOf("-1", "10081", "x", "")) assertNull(ReminderDraftParser.trigger("2026-11-27", "15:00", minutes, zone))
        assertNull(ReminderDraftParser.trigger("2026-11-27", "24:61", "0", zone))
    }

    @Test fun tomorrowAtMidnightUsesLocalDay() {
        val draft = ReminderDraftParser.extract("Interview tomorrow at 12 AM", now, zone)
        assertEquals("2026-11-26", draft.date)
        assertEquals("00:00", draft.time)
    }

    @Test fun duplicateMentionsAreUnambiguous() {
        val draft = ReminderDraftParser.extract("25/11/2026 at 4 PM\nNovember 25, 2026 at 16:00", now, zone)
        assertEquals("2026-11-25", draft.date)
        assertEquals("16:00", draft.time)
    }
}
