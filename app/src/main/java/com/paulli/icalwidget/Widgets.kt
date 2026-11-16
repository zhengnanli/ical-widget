package com.paulli.icalwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Rendering and refresh logic shared by the agenda and month widgets. */
object Widgets {
    const val ACTION_REFRESH = "com.paulli.icalwidget.REFRESH"
    const val ACTION_MIDNIGHT = "com.paulli.icalwidget.MIDNIGHT"
    const val ACTION_PREV_MONTH = "com.paulli.icalwidget.PREV_MONTH"
    const val ACTION_NEXT_MONTH = "com.paulli.icalwidget.NEXT_MONTH"
    const val ACTION_THIS_MONTH = "com.paulli.icalwidget.THIS_MONTH"

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    /** Re-renders every widget from the cached feed. Does no network I/O. */
    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val agenda = ids(ctx, CalendarWidgetProvider::class.java)
        val month = ids(ctx, MonthWidgetProvider::class.java)
        for (id in agenda) mgr.updateAppWidget(id, agendaViews(ctx, id))
        for (id in month) mgr.updateAppWidget(id, monthViews(ctx, id))
        val simple = ids(ctx, SimpleMonthWidgetProvider::class.java)
        if (simple.isNotEmpty()) mgr.updateAppWidget(simple, simpleMonthViews(ctx))
        val withLists = agenda + month
        if (withLists.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(withLists, R.id.event_list)
        if (withLists.isNotEmpty() || simple.isNotEmpty()) scheduleMidnight(ctx)
    }

    /** Renders from cache right away, then downloads in the background (if [force] or stale) and renders again. */
    fun refreshAsync(receiver: BroadcastReceiver, ctx: Context, force: Boolean) {
        val pending = receiver.goAsync()
        Thread {
            try {
                renderAll(ctx)
                val fetched = if (force) CalendarRepository.fetch(ctx) else CalendarRepository.ensureFresh(ctx)
                if (fetched || force) renderAll(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Wakes the widgets just after midnight so "today" moves. Inexact, which is fine here. */
    private fun scheduleMidnight(ctx: Context) {
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(0, 0, 30)).atZone(zone).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            ctx, 100, Intent(ctx, CalendarWidgetProvider::class.java).setAction(ACTION_MIDNIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, pi)
    }

    fun statusText(ctx: Context): String {
        val last = CalendarRepository.lastFetch(ctx)
        val error = CalendarRepository.lastError(ctx)
        if (last == 0L) return if (error != null) "Couldn't load: $error" else "Loading…"
        val time = DateFormat.getTimeFormat(ctx).format(last)
        val day = if (DateUtils.isToday(last)) time else DateFormat.getMediumDateFormat(ctx).format(last) + " " + time
        return if (error != null) "Offline · cached $day" else "Updated $day"
    }

    // ------------------------------------------------------------------ agenda widget

    private fun agendaViews(ctx: Context, id: Int): RemoteViews {
        val portrait = RemoteViews(ctx.packageName, R.layout.widget_calendar)
        val landscape = RemoteViews(ctx.packageName, R.layout.widget_agenda_landscape)
        for (v in listOf(portrait, landscape)) {
            v.setTextViewText(R.id.status_text, statusText(ctx))
            bindList(ctx, v, id)
            v.setOnClickPendingIntent(R.id.refresh_button, broadcast(ctx, CalendarWidgetProvider::class.java, ACTION_REFRESH, id))
            v.setOnClickPendingIntent(R.id.title_area, openApp(ctx))
        }
        return RemoteViews(landscape, portrait)
    }

    // ------------------------------------------------------------------ month widget

    private fun monthViews(ctx: Context, id: Int): RemoteViews {
        val zone = ZoneId.systemDefault()
        val month = YearMonth.from(LocalDate.now(zone)).plusMonths(CalendarRepository.monthOffset(ctx, id).toLong())
        val (gridStart, weeks) = gridOf(month)

        // Color of the first event on each day that has one.
        val busy = HashMap<LocalDate, Int>()
        val gridEnd = gridStart.plusDays(weeks * 7L)
        for (o in CalendarRepository.occurrences(ctx, gridStart.atStartOfDay(zone), gridEnd.atStartOfDay(zone))) {
            var d = o.start.toLocalDate()
            val last = if (o.end > o.start && o.end.toLocalTime() == LocalTime.MIDNIGHT) o.end.toLocalDate().minusDays(1) else o.end.toLocalDate()
            while (d <= last && d < gridEnd) { busy.putIfAbsent(d, EVENT_COLOR_RES[AgendaRows.colorIndex(o.title)]); d = d.plusDays(1) }
        }

        val prev = broadcast(ctx, MonthWidgetProvider::class.java, ACTION_PREV_MONTH, id)
        val next = broadcast(ctx, MonthWidgetProvider::class.java, ACTION_NEXT_MONTH, id)
        val reset = broadcast(ctx, MonthWidgetProvider::class.java, ACTION_THIS_MONTH, id)
        val refresh = broadcast(ctx, MonthWidgetProvider::class.java, ACTION_REFRESH, id)

        fun panel(layout: Int) = RemoteViews(ctx.packageName, layout).apply {
            fillGrid(ctx, this, month, busy)
            setOnClickPendingIntent(R.id.month_title, reset)
            setOnClickPendingIntent(R.id.prev_button, prev)
            setOnClickPendingIntent(R.id.next_button, next)
            setOnClickPendingIntent(R.id.refresh_button, refresh)
        }

        val portrait = panel(R.layout.widget_month)
        val landscape = panel(R.layout.widget_month_landscape).apply {
            setTextViewText(R.id.status_text, statusText(ctx))
            bindList(ctx, this, id)
        }
        return RemoteViews(landscape, portrait)
    }

    /** The plain month: always the current month, no events. */
    private fun simpleMonthViews(ctx: Context): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.widget_month_simple).apply {
            fillGrid(ctx, this, YearMonth.now(), busy = null)
            setOnClickPendingIntent(R.id.month_title, openApp(ctx))
        }

    /** First cell of the grid (start of the week containing the 1st) and the number of week rows. */
    private fun gridOf(month: YearMonth): Pair<LocalDate, Int> {
        val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val gridStart = month.atDay(1).with(java.time.temporal.TemporalAdjusters.previousOrSame(firstDow))
        val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(gridStart, month.atEndOfMonth()) / 7) + 1).toInt()
        return gridStart to weeks
    }

    /** Fills a month_panel / month_panel_simple grid. Today's box is filled with the accent color. */
    private fun fillGrid(ctx: Context, v: RemoteViews, month: YearMonth, busy: Map<LocalDate, Int>?) {
        val today = LocalDate.now()
        val (gridStart, weeks) = gridOf(month)
        val firstDow = gridStart.dayOfWeek

        v.setTextViewText(R.id.month_title, month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())))
        for (i in 0 until 7) {
            val dow = firstDow.plus(i.toLong())
            v.setTextViewText(MonthIds.weekdays[i], dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()))
            setTextColorRes(ctx, v, MonthIds.weekdays[i], if (isWeekend(dow)) R.color.widget_weekend else R.color.widget_text_secondary)
        }
        for (w in 0 until 6) v.setViewVisibility(MonthIds.weeks[w], if (w < weeks) View.VISIBLE else View.GONE)
        for (i in 0 until weeks * 7) {
            val day = gridStart.plusDays(i.toLong())
            val isToday = day == today
            val color = when {
                isToday -> R.color.widget_on_accent
                YearMonth.from(day) != month -> R.color.widget_text_faint
                isWeekend(day.dayOfWeek) -> R.color.widget_weekend
                else -> R.color.widget_text
            }
            v.setTextViewText(MonthIds.days[i], day.dayOfMonth.toString())
            setTextColorRes(ctx, v, MonthIds.days[i], color)
            v.setInt(MonthIds.boxes[i], "setBackgroundResource", if (isToday) R.drawable.today_box else 0)
            if (busy != null) {
                val dotColor = busy[day]
                v.setViewVisibility(MonthIds.dots[i], if (dotColor != null) View.VISIBLE else View.INVISIBLE)
                // On today's filled box a colored dot could vanish into the fill, so it uses the on-accent color.
                if (dotColor != null) setTintRes(ctx, v, MonthIds.dots[i], if (isToday) R.color.widget_on_accent else dotColor)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    val EVENT_COLOR_RES = intArrayOf(
        R.color.event_0, R.color.event_1, R.color.event_2, R.color.event_3, R.color.event_4, R.color.event_5,
    )

    /** Tints an ImageView; on Android 12+ the launcher resolves the color, so it follows theme changes live. */
    fun setTintRes(ctx: Context, views: RemoteViews, viewId: Int, colorRes: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 31) views.setColorStateList(viewId, "setImageTintList", colorRes)
        else views.setInt(viewId, "setColorFilter", ctx.getColor(colorRes))
    }

    private fun isWeekend(d: java.time.DayOfWeek) = d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY

    /** On Android 12+ the color resource is resolved by the launcher, so it follows light/dark mode live. */
    fun setTextColorRes(ctx: Context, views: RemoteViews, viewId: Int, colorRes: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 31) views.setColorStateList(viewId, "setTextColor", colorRes)
        else views.setTextColor(viewId, ctx.getColor(colorRes))
    }

    private fun bindList(ctx: Context, views: RemoteViews, id: Int) {
        val svc = Intent(ctx, CalendarWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) // unique per widget
        }
        @Suppress("DEPRECATION")
        views.setRemoteAdapter(R.id.event_list, svc)
        views.setEmptyView(R.id.event_list, R.id.empty_view)
        // Item taps fill in this template; it must be mutable for the fill-in to apply.
        views.setPendingIntentTemplate(
            R.id.event_list,
            PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        )
    }

    private fun broadcast(ctx: Context, cls: Class<*>, action: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id * 8 + action.hashCode() % 8,
            Intent(ctx, cls).setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("icalwidget://$action/$id")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
