package com.paulli.icalwidget

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A line in the agenda list: either a day heading or an event under it. */
sealed class AgendaRow {
    data class Day(val label: String, val isToday: Boolean, val date: LocalDate) : AgendaRow()
    data class Event(
        val title: String,
        val time: String,
        val location: String,
        val allDay: Boolean,
        val colorIndex: Int,
        val date: LocalDate,
    ) : AgendaRow() {
        val detail: String get() = if (location.isBlank()) time else "$time · $location"
    }
}

object AgendaRows {

    /** Number of event colors (R.color.event_0 .. event_5, from the system palette on Android 12+). */
    const val EVENT_COLORS = 6

    /** Stable color slot per event title, so a recurring event always gets the same color. */
    fun colorIndex(title: String): Int = Math.floorMod(title.trim().lowercase().hashCode(), EVENT_COLORS)

    /**
     * Groups occurrences by the days they cover, for [days] days starting at [from].
     * Multi-day events are listed under each day they span. With [hideEnded], today's events that
     * have already finished are left out.
     */
    fun build(
        occurrences: List<Occurrence>,
        now: ZonedDateTime,
        days: Int,
        use24h: Boolean,
        from: LocalDate = now.toLocalDate(),
        hideEnded: Boolean = true,
    ): List<AgendaRow> {
        val timeFmt = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault())
        val today = now.toLocalDate()

        val rows = ArrayList<AgendaRow>()
        for (i in 0 until days) {
            val day = from.plusDays(i.toLong())
            val dayStart = day.atStartOfDay(now.zone)
            val dayEnd = day.plusDays(1).atStartOfDay(now.zone)
            val onDay = occurrences.filter {
                it.start < dayEnd && (it.end > dayStart || it.start >= dayStart) &&
                    (!hideEnded || day != today || it.end > now || (it.end == it.start && it.start >= now))
            }
            if (onDay.isEmpty()) continue

            rows.add(AgendaRow.Day(dayLabel(day, today), day == today, day))
            for (o in onDay) {
                rows.add(AgendaRow.Event(o.title, timeLabel(o, day, now, timeFmt), o.location, o.allDay, colorIndex(o.title), day))
            }
        }
        return rows
    }

    /** "Today · Sun, Nov 15", "Tomorrow · …", "Yesterday · …", or "Tue, Nov 17" (with the year if not this year). */
    fun dayLabel(day: LocalDate, today: LocalDate): String {
        val pattern = if (day.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy"
        val date = day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        return when (java.time.temporal.ChronoUnit.DAYS.between(today, day)) {
            0L -> "Today · $date"
            1L -> "Tomorrow · $date"
            -1L -> "Yesterday · $date"
            else -> date
        }
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
