package com.paulli.icalwidget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class IcsTest {
    private val chicago = ZoneId.of("America/Chicago")

    private fun ics(vararg events: String) = (listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0",
        "BEGIN:VTIMEZONE", "TZID:Central Standard Time", "END:VTIMEZONE",
    ) + events.flatMap { it.trimIndent().lines() } + "END:VCALENDAR").joinToString("\r\n")

    private fun expand(text: String, from: LocalDate, days: Long, zone: ZoneId = chicago) =
        Ics.expand(text, from.atStartOfDay(zone), from.plusDays(days).atStartOfDay(zone), zone)

    @Test fun weeklyWithExdateAndOverride() {
        val text = ics(
            """
            BEGIN:VEVENT
            UID:a
            SUMMARY:Lecture
            DTSTART;TZID=Central Standard Time:20261102T100000
            DTEND;TZID=Central Standard Time:20261102T105000
            RRULE:FREQ=WEEKLY;UNTIL=20261120T160000Z;INTERVAL=1;BYDAY=MO,WE,FR;WKST=SU
            EXDATE;TZID=Central Standard Time:20261104T100000,20261111T100000
            LOCATION:Room 101
            END:VEVENT
            BEGIN:VEVENT
            UID:a
            RECURRENCE-ID;TZID=Central Standard Time:20261106T100000
            SUMMARY:Lecture (moved)
            DTSTART;TZID=Central Standard Time:20261106T140000
            DTEND;TZID=Central Standard Time:20261106T145000
            END:VEVENT
            """
        )
        val occ = expand(text, LocalDate.of(2026, 11, 1), 30)
        val starts = occ.map { it.start.toLocalDateTime().toString() + " " + it.title }
        assertEquals(
            listOf(
                "2026-11-02T10:00 Lecture",
                "2026-11-06T14:00 Lecture (moved)",
                "2026-11-09T10:00 Lecture",
                "2026-11-13T10:00 Lecture",
                "2026-11-16T10:00 Lecture",
                "2026-11-18T10:00 Lecture",
                "2026-11-20T10:00 Lecture",
            ),
            starts,
        )
        assertEquals(50, java.time.Duration.between(occ[0].start, occ[0].end).toMinutes())
        assertEquals("Room 101", occ[0].location)
        // The series and its moved instance are both recurring, so they share the recurring color family.
        assertEquals(true, occ.all { it.recurring })
        assertEquals(true, occ.all { AgendaRows.colorIndex(it.title, it.recurring) < 3 })
    }

    @Test fun biweeklyHonoursWkst() {
        val text = ics(
            """
            BEGIN:VEVENT
            UID:b
            SUMMARY:Biweekly
            DTSTART;TZID=Central Standard Time:20261020T090000
            DTEND;TZID=Central Standard Time:20261020T100000
            RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;WKST=SU;COUNT=4
            END:VEVENT
            """
        )
        val dates = expand(text, LocalDate.of(2026, 10, 1), 120).map { it.start.toLocalDate().toString() }
        assertEquals(listOf("2026-10-20", "2026-11-03", "2026-11-17", "2026-12-01"), dates)
    }

    @Test fun monthlyFirstWednesdayAndDstShift() {
        val text = ics(
            """
            BEGIN:VEVENT
            UID:c
            SUMMARY:Faculty meeting
            DTSTART;TZID=Eastern Standard Time:20261007T150000
            DTEND;TZID=Eastern Standard Time:20261007T160000
            RRULE:FREQ=MONTHLY;UNTIL=20270505T200000Z;INTERVAL=1;BYDAY=1WE
            END:VEVENT
            """
        )
        val occ = expand(text, LocalDate.of(2026, 10, 1), 70)
        // 3 PM Eastern == 2 PM Central, before and after the DST change.
        assertEquals(
            listOf("2026-10-07T14:00", "2026-11-04T14:00", "2026-12-02T14:00"),
            occ.map { it.start.toLocalDateTime().toString() },
        )
    }

    @Test fun allDayAndUtcEvents() {
        val text = ics(
            """
            BEGIN:VEVENT
            UID:d
            SUMMARY:Conference
            DTSTART;VALUE=DATE:20261029
            DTEND;VALUE=DATE:20261031
            END:VEVENT
            BEGIN:VEVENT
            UID:e
            SUMMARY:Call
            DTSTART:20261030T150000Z
            DURATION:PT30M
            END:VEVENT
            BEGIN:VEVENT
            UID:f
            SUMMARY:Cancelled thing
            STATUS:CANCELLED
            DTSTART:20261030T170000Z
            END:VEVENT
            """
        )
        val occ = expand(text, LocalDate.of(2026, 10, 30), 1)
        assertEquals(listOf("Conference", "Call"), occ.map { it.title })
        assertEquals(true, occ[0].allDay)
        assertEquals(false, occ.any { it.recurring })
        assertEquals(true, occ.all { AgendaRows.colorIndex(it.title, it.recurring) >= 3 })
        assertEquals(LocalDateTime.of(2026, 10, 30, 10, 0), occ[1].start.toLocalDateTime())

        val rows = AgendaRows.build(occ, ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, chicago), 1, false)
        assertEquals(3, rows.size)
        assertEquals("All day", (rows[1] as AgendaRow.Event).detail)
        assertEquals("10:00 AM – 10:30 AM", (rows[2] as AgendaRow.Event).detail)
    }

    @Test fun foldedLinesAndEscapes() {
        val text = ics(
            "BEGIN:VEVENT\r\nUID:g\r\nSUMMARY:Long title that\r\n  continues\\, with comma\r\nDTSTART:20261030T150000Z\r\nEND:VEVENT"
        )
        assertEquals("Long title that continues, with comma", expand(text, LocalDate.of(2026, 10, 30), 1)[0].title)
    }

    /** Prints the expansion of a real feed: ICS_FILE=/path/cal.ics ./gradlew testDebugUnitTest */
    @Test fun realFeed() {
        val path = System.getenv("ICS_FILE") ?: return
        val now = ZonedDateTime.now(chicago)
        val occ = expand(File(path).readText(), now.toLocalDate(), 14)
        for (r in AgendaRows.build(occ, now, 14, false)) when (r) {
            is AgendaRow.Day -> println("\n== ${r.label}")
            is AgendaRow.Event -> println("  ${r.title}  |  ${r.detail}")
        }
    }
}
