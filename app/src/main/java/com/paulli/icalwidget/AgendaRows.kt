package com.paulli.icalwidget

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A line in the agenda list: either a day heading or an event under it. */
sealed class AgendaRow {
    data class Day(val label: String, val isToday: Boolean) : AgendaRow()
    data class Event(val title: String, val detail: String, val allDay: Boolean, val colorIndex: Int) : AgendaRow()
}

object AgendaRows {

    /** Number of event colors (R.color.event_0 .. event_5, from the system palette on Android 12+). */
    const val EVENT_COLORS = 6

    /** Stable color slot per event title, so a recurring event always gets the same color. */
    fun colorIndex(title: String): Int = Math.floorMod(title.trim().lowercase().hashCode(), EVENT_COLORS)

    /**
     * Groups occurrences by the days they cover, hiding events that have already ended.
     * Multi-day events are listed under each day they span.
     */
    fun build(occurrences: List<Occurrence>, now: ZonedDateTime, days: Int, use24h: Boolean): List<AgendaRow> {
        val timeFmt = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault())
        val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        val today = now.toLocalDate()
        val live = occurrences.filter { it.end > now || (it.end == it.start && it.start >= now) }

        val rows = ArrayList<AgendaRow>()
        for (i in 0 until days) {
            val day = today.plusDays(i.toLong())
            val dayStart = day.atStartOfDay(now.zone)
            val dayEnd = day.plusDays(1).atStartOfDay(now.zone)
            val onDay = live.filter { it.start < dayEnd && (it.end > dayStart || it.start >= dayStart) }
            if (onDay.isEmpty()) continue

            val label = when (i) {
                0 -> "Today · " + day.format(dayFmt)
                1 -> "Tomorrow · " + day.format(dayFmt)
                else -> day.format(dayFmt)
            }
            rows.add(AgendaRow.Day(label, i == 0))
            for (o in onDay) {
                val time = timeLabel(o, day, now, timeFmt)
                val detail = if (o.location.isBlank()) time else "$time · ${o.location}"
                rows.add(AgendaRow.Event(o.title, detail, o.allDay, colorIndex(o.title)))
            }
        }
        return rows
    }

    private fun timeLabel(o: Occurrence, day: LocalDate, now: ZonedDateTime, fmt: DateTimeFormatter): String {
        if (o.allDay) return "All day"
        val s = o.start.withZoneSameInstant(now.zone)
        val e = o.end.withZoneSameInstant(now.zone)
        val startsToday = s.toLocalDate() == day
        val endsToday = e.toLocalDate() == day || (e.toLocalDate() == day.plusDays(1) && e.toLocalTime() == LocalTime.MIDNIGHT)
        return when {
            startsToday && s == e -> s.format(fmt)
            startsToday && endsToday -> "${s.format(fmt)} – ${e.format(fmt)}"
            startsToday -> "${s.format(fmt)} →"
            endsToday -> "→ ${e.format(fmt)}"
            else -> "All day"
        }
    }
}
