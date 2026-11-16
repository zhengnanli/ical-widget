package com.paulli.icalwidget

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Downloads the ICS feed, caches it on disk, and expands it into upcoming occurrences. */
object CalendarRepository {

    /** From `ical.url` in local.properties at build time; empty if unset. */
    val DEFAULT_URL: String = BuildConfig.DEFAULT_ICS_URL
    const val DEFAULT_DAYS = 14

    /** Don't hit the network more often than this unless the user taps refresh. */
    private const val MIN_FETCH_INTERVAL_MS = 15 * 60 * 1000L

    private const val KEY_URL = "url"
    private const val KEY_DAYS = "days"
    private const val KEY_LAST_FETCH = "last_fetch"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_ERROR_AT = "last_error_at"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("ical_widget", Context.MODE_PRIVATE)

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "calendar.ics")

    fun url(ctx: Context): String = prefs(ctx).getString(KEY_URL, null) ?: DEFAULT_URL
    fun daysAhead(ctx: Context): Int = prefs(ctx).getInt(KEY_DAYS, DEFAULT_DAYS)
    fun lastFetch(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_FETCH, 0L)

    /** The last error, if it happened after the last successful fetch. */
    fun lastError(ctx: Context): String? {
        val p = prefs(ctx)
        val at = p.getLong(KEY_LAST_ERROR_AT, 0L)
        return if (at > lastFetch(ctx)) p.getString(KEY_LAST_ERROR, null) else null
    }

    fun saveSettings(ctx: Context, url: String, days: Int) {
        val urlChanged = url != url(ctx)
        prefs(ctx).edit().putString(KEY_URL, url).putInt(KEY_DAYS, days.coerceIn(1, 365)).apply()
        if (urlChanged) cacheFile(ctx).delete()
    }

    /** The date tapped in a month widget's grid, which the list beside it starts from; null = default. */
    fun selectedDay(ctx: Context, widgetId: Int): LocalDate? =
        prefs(ctx).getLong("selected_$widgetId", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)

    fun setSelectedDay(ctx: Context, widgetId: Int, day: LocalDate?) = prefs(ctx).edit().apply {
        if (day == null) remove("selected_$widgetId") else putLong("selected_$widgetId", day.toEpochDay())
    }.apply()

    /** Occurrences on [days] days starting at [from], in the device's zone. */
    fun occurrencesFrom(ctx: Context, from: LocalDate, days: Int): List<Occurrence> {
        val zone = ZoneId.systemDefault()
        return occurrences(ctx, from.atStartOfDay(zone), from.plusDays(days.toLong()).atStartOfDay(zone))
    }

    fun monthOffset(ctx: Context, widgetId: Int): Int = prefs(ctx).getInt("month_offset_$widgetId", 0)
    fun setMonthOffset(ctx: Context, widgetId: Int, offset: Int) =
        prefs(ctx).edit().putInt("month_offset_$widgetId", offset).apply()

    /** Fetches if the cache is stale or missing. Returns true if it downloaded. Call off the main thread. */
    @Synchronized
    fun ensureFresh(ctx: Context): Boolean {
        val stale = System.currentTimeMillis() - lastFetch(ctx) > MIN_FETCH_INTERVAL_MS
        return if (stale || !cacheFile(ctx).exists()) fetch(ctx) else false
    }

    /** Downloads the feed now. Returns true on success. */
    @Synchronized
    fun fetch(ctx: Context): Boolean {
        val p = prefs(ctx)
        return try {
            val body = download(url(ctx))
            if (!body.contains("BEGIN:VCALENDAR")) throw IllegalStateException("Response is not an iCalendar feed")
            val file = cacheFile(ctx)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            p.edit().putLong(KEY_LAST_FETCH, System.currentTimeMillis()).commit()
            true
        } catch (e: Exception) {
            p.edit()
                .putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName)
                .putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())
                .commit()
            false
        }
    }

    private fun download(rawUrl: String): String {
        if (rawUrl.isBlank()) throw IllegalStateException("No calendar URL set. Open the app to add one.")
        var url = rawUrl.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
        repeat(5) { // follow redirects, including http -> https
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "IcalWidget/1.0 (Android)")
                conn.setRequestProperty("Accept", "text/calendar, */*")
                val code = conn.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect without Location")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    /** Occurrences from the start of today through [daysAhead] days, from the cached feed. */
    fun upcoming(ctx: Context): List<Occurrence> {
        val zone = ZoneId.systemDefault()
        val from = LocalDate.now(zone).atStartOfDay(zone)
        return occurrences(ctx, from, from.plusDays(daysAhead(ctx).toLong()))
    }

    /** Occurrences overlapping [from, to) from the cached feed. */
    fun occurrences(ctx: Context, from: ZonedDateTime, to: ZonedDateTime): List<Occurrence> {
        val file = cacheFile(ctx)
        if (!file.exists()) return emptyList()
        return try {
            Ics.expand(file.readText(), from, to, from.zone)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
