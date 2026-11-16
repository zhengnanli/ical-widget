package com.paulli.icalwidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.min

/**
 * A popup listing a day's events plus the next few days, opened by tapping a date or an event in
 * the widgets.
 */
class DayActivity : Activity() {

    companion object {
        /** Days shown after the tapped one (empty ones are skipped). */
        private const val FOLLOWING_DAYS = 3
    }

    private var day: LocalDate = LocalDate.now()
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var events: LinearLayout
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day)
        val metrics = resources.displayMetrics
        window.setLayout(min((metrics.widthPixels * 0.92).toInt(), (520 * metrics.density).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)

        title = findViewById(R.id.day_title)
        subtitle = findViewById(R.id.day_subtitle)
        events = findViewById(R.id.day_events)
        findViewById<View>(R.id.day_prev).setOnClickListener { day = day.minusDays(1); show() }
        findViewById<View>(R.id.day_next).setOnClickListener { day = day.plusDays(1); show() }
        findViewById<View>(R.id.day_today).setOnClickListener { day = LocalDate.now(); show() }

        readDate(intent)
        show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDate(intent)
        show()
    }

    private fun readDate(intent: Intent) {
        val epochDay = intent.getLongExtra(Widgets.EXTRA_DATE, Long.MIN_VALUE)
        day = if (epochDay == Long.MIN_VALUE) LocalDate.now() else LocalDate.ofEpochDay(epochDay)
    }

    private fun show() {
        val today = LocalDate.now()
        val locale = Locale.getDefault()
        title.text = day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy", locale))
        subtitle.text = when (val n = ChronoUnit.DAYS.between(today, day)) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            in 2..Long.MAX_VALUE -> "In $n days"
            else -> "${-n} days ago"
        }
        findViewById<View>(R.id.day_today).visibility = if (day == today) View.GONE else View.VISIBLE

        val generation = ++loadGeneration
        val shown = day
        events.removeAllViews()
        events.addView(message("Loading…"))
        Thread {
            if (CalendarRepository.ensureFresh(this)) Widgets.renderAll(this)
            val days = 1 + FOLLOWING_DAYS
            val rows = AgendaRows.build(
                CalendarRepository.occurrencesFrom(this, shown, days), ZonedDateTime.now(),
                days, DateFormat.is24HourFormat(this), from = shown, hideEnded = false,
            )
            runOnUiThread { if (generation == loadGeneration && !isFinishing) populate(shown, rows) }
        }.start()
    }

    private fun populate(shown: LocalDate, rows: List<AgendaRow>) {
        events.removeAllViews()
        // The tapped day is the popup's title, so it gets no heading; say so if it's empty.
        if (rows.none { it is AgendaRow.Event && it.date == shown }) events.addView(message("No events"))
        val inflater = LayoutInflater.from(this)
        for (row in rows) {
            if (row is AgendaRow.Day) {
                if (row.date != shown) events.addView(heading(row.label))
                continue
            }
            row as AgendaRow.Event
            val item = inflater.inflate(R.layout.day_event_item, events, false)
            item.findViewById<View>(R.id.item_bar).backgroundTintList = getColorStateList(Widgets.EVENT_COLOR_RES[row.colorIndex])
            item.findViewById<TextView>(R.id.item_title).text = row.title
            item.findViewById<TextView>(R.id.item_time).text = row.time
            item.findViewById<TextView>(R.id.item_location).apply {
                text = row.location
                visibility = if (row.location.isBlank()) View.GONE else View.VISIBLE
            }
            events.addView(item)
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.widget_accent))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        val dp = resources.displayMetrics.density
        setPadding(0, (16 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun message(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.widget_text_secondary))
        textSize = 15f
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt())
    }
}
