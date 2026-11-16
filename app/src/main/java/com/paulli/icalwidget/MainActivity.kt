package com.paulli.icalwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.time.ZonedDateTime

/** Settings + preview screen: feed URL, how many days to show, and a manual refresh. */
class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var status: TextView
    private lateinit var preview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlInput = findViewById(R.id.url_input)
        daysInput = findViewById(R.id.days_input)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)

        urlInput.setText(CalendarRepository.url(this))
        daysInput.setText(CalendarRepository.daysAhead(this).toString())

        findViewById<Button>(R.id.save_button).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (!Regex("^(https?|webcals?)://.+", RegexOption.IGNORE_CASE).matches(url)) {
                Toast.makeText(this, "Enter an http(s) or webcal URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val days = daysInput.text.toString().toIntOrNull() ?: CalendarRepository.DEFAULT_DAYS
            CalendarRepository.saveSettings(this, url, days)
            daysInput.setText(CalendarRepository.daysAhead(this).toString())
            refresh()
        }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { refresh() }

        findViewById<Button>(R.id.pin_button).setOnClickListener { pin(CalendarWidgetProvider::class.java) }
        findViewById<Button>(R.id.pin_month_button).setOnClickListener { pin(MonthWidgetProvider::class.java) }
        findViewById<Button>(R.id.pin_simple_button).setOnClickListener { pin(SimpleMonthWidgetProvider::class.java) }

        showCached()
        Thread { if (CalendarRepository.ensureFresh(this)) Widgets.renderAll(this); runOnUiThread(::showCached) }.start()
    }

    private fun pin(cls: Class<*>) {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, cls), null, null)
        } else {
            Toast.makeText(this, "Long-press the home screen → Widgets → iCal Widget", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        status.text = "Refreshing…"
        Thread {
            CalendarRepository.fetch(this)
            Widgets.renderAll(this)
            runOnUiThread(::showCached)
        }.start()
    }

    private fun showCached() {
        if (isFinishing) return
        status.text = Widgets.statusText(this)
        Thread {
            val rows = AgendaRows.build(
                CalendarRepository.upcoming(this), ZonedDateTime.now(),
                CalendarRepository.daysAhead(this), DateFormat.is24HourFormat(this),
            )
            val text = if (rows.isEmpty()) "No upcoming events." else rows.joinToString("\n") {
                when (it) {
                    is AgendaRow.Day -> "\n" + it.label
                    is AgendaRow.Event -> "  • ${it.title}\n     ${it.detail}"
                }
            }.trimStart()
            runOnUiThread { if (!isFinishing) preview.text = text }
        }.start()
    }
}
