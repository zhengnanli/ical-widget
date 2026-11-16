package com.paulli.icalwidget

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** One concrete occurrence of an event, expressed in the display time zone. */
data class Occurrence(
    val title: String,
    val location: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    /** Part of a series (RRULE/RDATE, or a moved/edited instance of one) rather than a one-off. */
    val recurring: Boolean = false,
)

/**
 * Minimal, dependency-free iCalendar (RFC 5545) reader.
 *
 * Handles what calendar feeds such as Outlook/Office 365 publish: line folding, Windows time zone
 * names, all-day events, RRULE (DAILY/WEEKLY/MONTHLY/YEARLY with INTERVAL, COUNT, UNTIL, BYDAY,
 * BYMONTHDAY, BYMONTH, BYSETPOS, WKST), RDATE, EXDATE, RECURRENCE-ID overrides and cancellations.
 */
object Ics {

    class Prop(val name: String, val params: Map<String, String>, val value: String)

    class Component(val name: String) {
        val props = ArrayList<Prop>()
        val children = ArrayList<Component>()
        fun get(n: String): Prop? = props.firstOrNull { it.name == n }
        fun all(n: String): List<Prop> = props.filter { it.name == n }
        fun text(n: String): String = get(n)?.value?.let(::unescape)?.trim().orEmpty()
    }

    /** Expands every event in [text] that overlaps [from, to). Results are sorted by start time. */
    fun expand(text: String, from: ZonedDateTime, to: ZonedDateTime, displayZone: ZoneId): List<Occurrence> {
        val root = parse(text)
        val tz = TzResolver(root, displayZone)
        val events = ArrayList<Component>()
        collect(root, "VEVENT", events)

        // Instances replaced or cancelled by a RECURRENCE-ID component, per UID.
        val overridden = HashMap<String, MutableSet<Instant>>()
        for (ev in events) {
            val rid = ev.get("RECURRENCE-ID") ?: continue
            val dt = parseDt(rid.value, rid.params, tz) ?: continue
            overridden.getOrPut(ev.text("UID")) { HashSet() }.add(dt.instant())
        }

        val fromI = from.toInstant()
        val toI = to.toInstant()
        val out = ArrayList<Occurrence>()

        for (ev in events) {
            if (ev.text("STATUS").equals("CANCELLED", ignoreCase = true)) continue
            val dtStartProp = ev.get("DTSTART") ?: continue
            val start = parseDt(dtStartProp.value, dtStartProp.params, tz) ?: continue

            val title = ev.text("SUMMARY").ifEmpty { "(No title)" }
            val location = ev.text("LOCATION")
            val duration = eventLength(ev, start, tz)
            val recurring = ev.get("RRULE") != null || ev.get("RDATE") != null || ev.get("RECURRENCE-ID") != null

            fun emit(local: LocalDateTime) {
                val occ = if (start.allDay) {
                    val s = local.toLocalDate().atStartOfDay(displayZone)
                    val days = (duration as Length.Days).days
                    Occurrence(title, location, s, s.plusDays(days), true, recurring)
                } else {
                    val s = local.atZone(start.zone).withZoneSameInstant(displayZone)
                    Occurrence(title, location, s, s.plus((duration as Length.Exact).d), false, recurring)
                }
                val si = occ.start.toInstant()
                val ei = occ.end.toInstant()
                if (si < toI && (ei > fromI || (ei == si && si >= fromI))) out.add(occ)
            }

            val rrule = ev.get("RRULE")
            if (ev.get("RECURRENCE-ID") != null || (rrule == null && ev.get("RDATE") == null)) {
                emit(start.local)
                continue
            }

            val excluded = HashSet<Instant>()
            overridden[ev.text("UID")]?.let(excluded::addAll)
            for (p in ev.all("EXDATE")) for (v in p.value.split(',')) {
                parseDt(v.trim(), p.params, tz, start.zone)?.let { excluded.add(it.instant()) }
            }

            val seen = HashSet<LocalDateTime>()
            fun emitIfAllowed(local: LocalDateTime) {
                if (!seen.add(local)) return
                if (local.atZone(start.zone).toInstant() in excluded) return
                emit(local)
            }

            // Stop generating once we're safely past the window (one-day margin covers zone skew).
            val horizon = to.withZoneSameInstant(start.zone).toLocalDateTime().plusDays(1)
            if (rrule != null) {
                val rule = RRule.parse(rrule.value, tz, start)
                if (rule != null) rule.generate(start.local, horizon, ::emitIfAllowed) else emitIfAllowed(start.local)
            } else {
                emitIfAllowed(start.local)
            }
            for (p in ev.all("RDATE")) {
                if (p.params["VALUE"] == "PERIOD") continue
                for (v in p.value.split(',')) parseDt(v.trim(), p.params, tz, start.zone)?.let {
                    emitIfAllowed(it.zoned().withZoneSameInstant(start.zone).toLocalDateTime())
                }
            }
        }

        out.sortWith(compareBy<Occurrence> { it.start.toInstant() }.thenBy { !it.allDay }.thenBy { it.title })
        return out
    }

    // ---------------------------------------------------------------- parsing

    fun parse(text: String): Component {
        val root = Component("ROOT")
        val stack = ArrayList<Component>().apply { add(root) }
        for (line in unfold(text)) {
            val p = parseProp(line) ?: continue
            when (p.name) {
                "BEGIN" -> Component(p.value.trim().uppercase()).also {
                    stack.last().children.add(it); stack.add(it)
                }
                "END" -> if (stack.size > 1) stack.removeAt(stack.size - 1)
                else -> stack.last().props.add(p)
            }
        }
        return root
    }

    private fun collect(c: Component, name: String, out: MutableList<Component>) {
        for (ch in c.children) {
            if (ch.name == name) out.add(ch) else collect(ch, name, out)
        }
    }

    internal fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (raw in text.removePrefix("﻿").split('\n')) {
            val line = raw.removeSuffix("\r")
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                sb.append(line, 1, line.length)
            } else {
                if (sb.isNotEmpty()) out.add(sb.toString())
                sb.setLength(0)
                sb.append(line)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    internal fun parseProp(line: String): Prop? {
        var inQuotes = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ':' && !inQuotes) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = splitOutsideQuotes(line.substring(0, colon), ';')
        val params = HashMap<String, String>()
        for (p in head.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).trim().uppercase()] = p.substring(eq + 1).trim().trim('"')
        }
        return Prop(head[0].trim().uppercase(), params, line.substring(colon + 1))
    }

    private fun splitOutsideQuotes(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var inQuotes = false
        var startIdx = 0
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == sep && !inQuotes) { out.add(s.substring(startIdx, i)); startIdx = i + 1 }
        }
        out.add(s.substring(startIdx))
        return out
    }

    internal fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- dates

    /** A DTSTART-like value: wall-clock time in a zone, or a date for all-day values. */
    class Dt(val local: LocalDateTime, val zone: ZoneId, val allDay: Boolean) {
        fun zoned(): ZonedDateTime = local.atZone(zone)
        fun instant(): Instant = zoned().toInstant()
    }

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * @param fallbackZone zone for floating values; EXDATE/RDATE use the event's zone so a floating
     * exclusion lines up with a floating start.
     */
    internal fun parseDt(value: String, params: Map<String, String>, tz: TzResolver, fallbackZone: ZoneId? = null): Dt? {
        val v = value.trim()
        return try {
            if (params["VALUE"] == "DATE" || v.length == 8) {
                Dt(LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(), tz.displayZone, true)
            } else if (v.endsWith("Z") || v.endsWith("z")) {
                Dt(LocalDateTime.parse(v.dropLast(1), DATE_TIME), ZoneOffset.UTC, false)
            } else {
                val zone = params["TZID"]?.let(tz::resolve) ?: fallbackZone ?: tz.displayZone
                Dt(LocalDateTime.parse(v.take(15), DATE_TIME), zone, false)
            }
        } catch (e: Exception) {
            null
        }
    }

    private sealed class Length {
        class Exact(val d: Duration) : Length()
        class Days(val days: Long) : Length()
    }

    private fun eventLength(ev: Component, start: Dt, tz: TzResolver): Length {
        val endProp = ev.get("DTEND")
        val end = endProp?.let { parseDt(it.value, it.params, tz, start.zone) }
        if (start.allDay) {
            val days = when {
                end != null -> ChronoUnit.DAYS.between(start.local.toLocalDate(), end.local.toLocalDate())
                else -> ev.get("DURATION")?.let { parseDuration(it.value)?.toDays() } ?: 1L
            }
            return Length.Days(days.coerceAtLeast(1))
        }
        val d = when {
            end != null -> Duration.between(start.zoned(), end.zoned())
            else -> ev.get("DURATION")?.let { parseDuration(it.value) } ?: Duration.ZERO
        }
        return Length.Exact(if (d.isNegative) Duration.ZERO else d)
    }

    internal fun parseDuration(s: String): Duration? {
        val v = s.trim().uppercase()
        val m = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matchEntire(v) ?: return null
        val g = m.groupValues
        var d = Duration.ofDays((g[2].toLongOrNull() ?: 0) * 7 + (g[3].toLongOrNull() ?: 0))
            .plusHours(g[4].toLongOrNull() ?: 0)
            .plusMinutes(g[5].toLongOrNull() ?: 0)
            .plusSeconds(g[6].toLongOrNull() ?: 0)
        if (g[1] == "-") d = d.negated()
        return d
    }

    // ---------------------------------------------------------------- time zones

    class TzResolver(root: Component, val displayZone: ZoneId) {
        private val vtimezones = HashMap<String, Component>()
        private val cache = HashMap<String, ZoneId>()

        init {
            val list = ArrayList<Component>()
            collect(root, "VTIMEZONE", list)
            for (c in list) c.get("TZID")?.value?.trim()?.let { vtimezones[it] = c }
        }

        fun resolve(tzid: String): ZoneId = cache.getOrPut(tzid) { lookup(tzid.trim().trim('"')) }

        private fun lookup(id: String): ZoneId {
            tryZone(id)?.let { return it }
            WINDOWS_ZONES[id]?.let { return ZoneId.of(it) }
            // "/mozilla.org/20050126_1/America/New_York", "/citadel.org/.../Europe/Berlin" and friends.
            val segs = id.split('/').filter { it.isNotEmpty() }
            for (n in 3 downTo 1) {
                if (segs.size >= n) tryZone(segs.takeLast(n).joinToString("/"))?.let { return it }
            }
            vtimezones[id]?.get("X-LIC-LOCATION")?.value?.let(::tryZone)?.let { return it }
            // Last resort: the VTIMEZONE's standard offset (ignores DST, but beats being hours off).
            vtimezones[id]?.children?.firstOrNull { it.name == "STANDARD" }?.get("TZOFFSETTO")?.value?.let {
                runCatching { ZoneOffset.of(it.trim().let { o -> if (o.length == 5) o.substring(0, 3) + ":" + o.substring(3) else o }) }
                    .getOrNull()?.let { off -> return off }
            }
            return displayZone
        }

        private fun tryZone(id: String): ZoneId? =
            if (id.contains('/') || id == "UTC" || id == "GMT") runCatching { ZoneId.of(id) }.getOrNull() else null
    }

    /** Windows time zone names (as emitted by Outlook/Exchange) → IANA ids, from CLDR windowsZones. */
    private val WINDOWS_ZONES = mapOf(
        "Dateline Standard Time" to "Etc/GMT+12",
        "UTC-11" to "Etc/GMT+11",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Alaskan Standard Time" to "America/Anchorage",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Pacific Standard Time (Mexico)" to "America/Tijuana",
        "US Mountain Standard Time" to "America/Phoenix",
        "Mountain Standard Time" to "America/Denver",
        "Mountain Standard Time (Mexico)" to "America/Mazatlan",
        "Central Standard Time" to "America/Chicago",
        "Central America Standard Time" to "America/Guatemala",
        "Canada Central Standard Time" to "America/Regina",
        "Central Standard Time (Mexico)" to "America/Mexico_City",
        "Mexico Standard Time" to "America/Mexico_City",
        "Eastern Standard Time" to "America/New_York",
        "US Eastern Standard Time" to "America/Indiana/Indianapolis",
        "Eastern Standard Time (Mexico)" to "America/Cancun",
        "SA Pacific Standard Time" to "America/Bogota",
        "Venezuela Standard Time" to "America/Caracas",
        "Atlantic Standard Time" to "America/Halifax",
        "SA Western Standard Time" to "America/La_Paz",
        "Pacific SA Standard Time" to "America/Santiago",
        "Newfoundland Standard Time" to "America/St_Johns",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "Argentina Standard Time" to "America/Argentina/Buenos_Aires",
        "SA Eastern Standard Time" to "America/Cayenne",
        "Greenland Standard Time" to "America/Godthab",
        "Montevideo Standard Time" to "America/Montevideo",
        "UTC-02" to "Etc/GMT+2",
        "Azores Standard Time" to "Atlantic/Azores",
        "Cape Verde Standard Time" to "Atlantic/Cape_Verde",
        "UTC" to "UTC",
        "Coordinated Universal Time" to "UTC",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "Morocco Standard Time" to "Africa/Casablanca",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Romance Standard Time" to "Europe/Paris",
        "Central European Standard Time" to "Europe/Warsaw",
        "W. Central Africa Standard Time" to "Africa/Lagos",
        "GTB Standard Time" to "Europe/Bucharest",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "FLE Standard Time" to "Europe/Kiev",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Egypt Standard Time" to "Africa/Cairo",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "Jordan Standard Time" to "Asia/Amman",
        "Middle East Standard Time" to "Asia/Beirut",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Arabic Standard Time" to "Asia/Baghdad",
        "Arab Standard Time" to "Asia/Riyadh",
        "Russian Standard Time" to "Europe/Moscow",
        "E. Africa Standard Time" to "Africa/Nairobi",
        "Iran Standard Time" to "Asia/Tehran",
        "Arabian Standard Time" to "Asia/Dubai",
        "Afghanistan Standard Time" to "Asia/Kabul",
        "Pakistan Standard Time" to "Asia/Karachi",
        "West Asia Standard Time" to "Asia/Tashkent",
        "India Standard Time" to "Asia/Kolkata",
        "Sri Lanka Standard Time" to "Asia/Colombo",
        "Nepal Standard Time" to "Asia/Kathmandu",
        "Central Asia Standard Time" to "Asia/Almaty",
        "Bangladesh Standard Time" to "Asia/Dhaka",
        "Myanmar Standard Time" to "Asia/Yangon",
        "SE Asia Standard Time" to "Asia/Bangkok",
        "China Standard Time" to "Asia/Shanghai",
        "Singapore Standard Time" to "Asia/Singapore",
        "Taipei Standard Time" to "Asia/Taipei",
        "W. Australia Standard Time" to "Australia/Perth",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "Korea Standard Time" to "Asia/Seoul",
        "Cen. Australia Standard Time" to "Australia/Adelaide",
        "AUS Central Standard Time" to "Australia/Darwin",
        "E. Australia Standard Time" to "Australia/Brisbane",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "Tasmania Standard Time" to "Australia/Hobart",
        "West Pacific Standard Time" to "Pacific/Port_Moresby",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "Fiji Standard Time" to "Pacific/Fiji",
        "Tonga Standard Time" to "Pacific/Tongatapu",
    )

    // ---------------------------------------------------------------- recurrence

    internal class RRule(
        val freq: String,
        val interval: Int,
        val count: Int?,
        val until: (LocalDateTime) -> Boolean, // true when the candidate is past UNTIL
        val byDay: List<Pair<Int, DayOfWeek>>,  // ordinal 0 = every such weekday
        val byMonthDay: List<Int>,
        val byMonth: List<Int>,
        val bySetPos: List<Int>,
        val wkst: DayOfWeek,
    ) {
        companion object {
            private val DAYS = mapOf(
                "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
                "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY,
                "SU" to DayOfWeek.SUNDAY,
            )

            fun parse(value: String, tz: TzResolver, start: Dt): RRule? {
                val parts = value.split(';').mapNotNull {
                    val eq = it.indexOf('=')
                    if (eq > 0) it.substring(0, eq).trim().uppercase() to it.substring(eq + 1).trim() else null
                }.toMap()
                val freq = parts["FREQ"]?.uppercase() ?: return null
                if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
                fun ints(k: String) = parts[k]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

                val byDay = parts["BYDAY"]?.split(',')?.mapNotNull {
                    val t = it.trim().uppercase()
                    val dow = DAYS[t.takeLast(2)] ?: return@mapNotNull null
                    val ord = t.dropLast(2).ifEmpty { "0" }.toIntOrNull() ?: return@mapNotNull null
                    ord to dow
                } ?: emptyList()

                val until: (LocalDateTime) -> Boolean = parts["UNTIL"]?.let { u ->
                    val dt = parseDt(u, emptyMap(), tz, start.zone)
                    when {
                        dt == null -> null
                        dt.allDay -> { c: LocalDateTime -> c.toLocalDate() > dt.local.toLocalDate() }
                        else -> {
                            val limit = dt.instant()
                            val f = { c: LocalDateTime -> c.atZone(start.zone).toInstant() > limit }
                            f
                        }
                    }
                } ?: { _: LocalDateTime -> false }

                return RRule(
                    freq = freq,
                    interval = (parts["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1),
                    count = parts["COUNT"]?.toIntOrNull(),
                    until = until,
                    byDay = byDay,
                    byMonthDay = ints("BYMONTHDAY"),
                    byMonth = ints("BYMONTH"),
                    bySetPos = ints("BYSETPOS"),
                    wkst = parts["WKST"]?.uppercase()?.let(DAYS::get) ?: DayOfWeek.MONDAY,
                )
            }
        }

        /** Calls [emit] for each occurrence (DTSTART included) in order until UNTIL/COUNT/[horizon]. */
        fun generate(dtStart: LocalDateTime, horizon: LocalDateTime, emit: (LocalDateTime) -> Unit) {
            val time = dtStart.toLocalTime()
            val startDate = dtStart.toLocalDate()
            var emitted = 0
            var period = 0L
            while (period < 100_000) {
                val step = period * interval
                val periodStart: LocalDate
                var days: List<LocalDate>
                when (freq) {
                    "DAILY" -> {
                        val d = startDate.plusDays(step)
                        periodStart = d
                        days = listOf(d).filter { matchesMonth(it) && matchesMonthDay(it) && matchesWeekday(it) }
                    }
                    "WEEKLY" -> {
                        val weekStart = startDate.with(TemporalAdjusters.previousOrSame(wkst)).plusWeeks(step)
                        periodStart = weekStart
                        val wanted = if (byDay.isEmpty()) setOf(startDate.dayOfWeek) else byDay.map { it.second }.toSet()
                        days = (0L..6L).map { weekStart.plusDays(it) }.filter { it.dayOfWeek in wanted && matchesMonth(it) }
                    }
                    "MONTHLY" -> {
                        val ym = YearMonth.from(startDate).plusMonths(step)
                        periodStart = ym.atDay(1)
                        days = if (byMonth.isNotEmpty() && ym.monthValue !in byMonth) emptyList()
                        else daysInMonth(ym, startDate.dayOfMonth)
                    }
                    else -> { // YEARLY
                        val year = startDate.year + step.toInt()
                        periodStart = LocalDate.of(year, 1, 1)
                        val months = byMonth.ifEmpty { listOf(startDate.monthValue) }
                        days = months.filter { it in 1..12 }.sorted().flatMap { daysInMonth(YearMonth.of(year, it), startDate.dayOfMonth) }
                    }
                }
                if (periodStart.atStartOfDay() > horizon) return
                days = days.distinct().sorted()
                if (bySetPos.isNotEmpty() && days.isNotEmpty()) {
                    days = bySetPos.mapNotNull { p -> days.getOrNull(if (p > 0) p - 1 else days.size + p) }.distinct().sorted()
                }
                for (d in days) {
                    val c = d.atTime(time)
                    if (c < dtStart) continue
                    if (until(c)) return
                    if (count != null && emitted >= count) return
                    emitted++
                    emit(c)
                }
                period++
            }
        }

        private fun matchesMonth(d: LocalDate) = byMonth.isEmpty() || d.monthValue in byMonth
        private fun matchesWeekday(d: LocalDate) = byDay.isEmpty() || byDay.any { it.second == d.dayOfWeek }
        private fun matchesMonthDay(d: LocalDate) = byMonthDay.isEmpty() || byMonthDay.any {
            val len = d.lengthOfMonth()
            (if (it > 0) it else len + it + 1) == d.dayOfMonth
        }

        private fun daysInMonth(ym: YearMonth, defaultDay: Int): List<LocalDate> {
            val len = ym.lengthOfMonth()
            val byMd = byMonthDay.mapNotNull { n ->
                val dd = if (n > 0) n else len + n + 1
                if (dd in 1..len) ym.atDay(dd) else null
            }
            if (byDay.isEmpty()) {
                return if (byMonthDay.isNotEmpty()) byMd
                else if (defaultDay <= len) listOf(ym.atDay(defaultDay)) else emptyList()
            }
            val all = (1..len).map(ym::atDay)
            val byD = byDay.flatMap { (ord, dow) ->
                val matching = all.filter { it.dayOfWeek == dow }
                if (ord == 0) matching
                else listOfNotNull(matching.getOrNull(if (ord > 0) ord - 1 else matching.size + ord))
            }
            return if (byMonthDay.isNotEmpty()) byD.filter { it in byMd } else byD
        }
    }
}
