package com.paulli.icalwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
    const val ACTION_SELECT_DAY = "com.paulli.icalwidget.SELECT_DAY"
    const val EXTRA_DATE = "com.paulli.icalwidget.DATE" // epoch day

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    /** Re-renders every widget from the cached feed. Does no network I/O. */
    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val agenda = ids(ctx, CalendarWidgetProvider::class.java)
        val month = ids(ctx, MonthWidgetProvider::class.java)
        val simple = ids(ctx, SimpleMonthWidgetProvider::class.java)
        val rows by lazy {
            AgendaRows.build(
                CalendarRepository.upcoming(ctx), java.time.ZonedDateTime.now(),
                CalendarRepository.daysAhead(ctx), DateFormat.is24HourFormat(ctx),
            )
        }
        for (id in agenda) mgr.updateAppWidget(id, agendaViews(ctx, id, rows))
        for (id in month) mgr.updateAppWidget(id, monthViews(ctx, mgr, id, rows))
        for (id in simple) mgr.updateAppWidget(id, simpleMonthViews(ctx, mgr, id))
        val withLists = agenda + month
        if (withLists.isNotEmpty() && Build.VERSION.SDK_INT < 31) mgr.notifyAppWidgetViewDataChanged(withLists, R.id.event_list)
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

    private fun agendaViews(ctx: Context, id: Int, rows: List<AgendaRow>): RemoteViews {
        val portrait = RemoteViews(ctx.packageName, R.layout.widget_calendar)
        val landscape = RemoteViews(ctx.packageName, R.layout.widget_agenda_landscape)
        for (v in listOf(portrait, landscape)) {
            v.setTextViewText(R.id.status_text, statusText(ctx))
            bindList(ctx, v, id, rows)
            v.setOnClickPendingIntent(R.id.refresh_button, broadcast(ctx, CalendarWidgetProvider::class.java, ACTION_REFRESH, id))
            v.setOnClickPendingIntent(R.id.title_area, openApp(ctx))
        }
        return RemoteViews(landscape, portrait)
    }

    // ------------------------------------------------------------------ month widget

    private fun monthViews(ctx: Context, mgr: AppWidgetManager, id: Int, todayRows: List<AgendaRow>): RemoteViews {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val month = YearMonth.from(today).plusMonths(CalendarRepository.monthOffset(ctx, id).toLong())
        val selected = CalendarRepository.selectedDay(ctx, id)

        // The list beside the grid starts at the tapped date, else today (this month) or the 1st (other months).
        val listFrom = selected ?: if (month == YearMonth.from(today)) today else month.atDay(1)
        val rows = if (listFrom == today) todayRows else {
            val days = CalendarRepository.daysAhead(ctx)
            AgendaRows.build(
                CalendarRepository.occurrencesFrom(ctx, listFrom, days), java.time.ZonedDateTime.now(zone),
                days, DateFormat.is24HourFormat(ctx), from = listFrom, hideEnded = false,
            )
        }
        val listTitle = if (listFrom == today) ctx.getString(R.string.upcoming)
        else listFrom.format(DateTimeFormatter.ofPattern(if (listFrom.year == today.year) "EEE, MMM d" else "MMM d, yyyy", Locale.getDefault()))
        val busy = busyDays(ctx, month)

        val prev = broadcast(ctx, MonthWidgetProvider::class.java, ACTION_PREV_MONTH, id)
        val next = broadcast(ctx, MonthWidgetProvider::class.java, ACTION_NEXT_MONTH, id)
        val reset = broadcast(ctx, MonthWidgetProvider::class.java, ACTION_THIS_MONTH, id)
        val refresh = broadcast(ctx, MonthWidgetProvider::class.java, ACTION_REFRESH, id)

        // The month grid takes the full width in portrait, and 1.15 / 2.15 of it beside the list in landscape.
        val (portraitW, landscapeW) = widthsDp(mgr, id)
        val buttons = 3 * 36 + 8
        fun panel(layout: Int, titleSpace: Int, sel: LocalDate?, onDay: (LocalDate) -> PendingIntent) =
            RemoteViews(ctx.packageName, layout).apply {
            fillGrid(ctx, this, month, busy, titleSpace, sel, onDay)
            setOnClickPendingIntent(R.id.month_title, reset)
            setOnClickPendingIntent(R.id.prev_button, prev)
            setOnClickPendingIntent(R.id.next_button, next)
            setOnClickPendingIntent(R.id.refresh_button, refresh)
        }

        // Portrait has no list, so a tapped date opens the day screen; in landscape it moves the list.
        val portrait = panel(R.layout.widget_month, portraitW - 24 - buttons, null) { openDay(ctx, it) }
        val landscapeTitle = ((landscapeW - 24 - 25) * 1.15 / 2.15).toInt() - buttons
        val landscape = panel(R.layout.widget_month_landscape, landscapeTitle, selected) { selectDay(ctx, id, it) }.apply {
            setTextViewText(R.id.list_title, listTitle)
            setTextViewText(R.id.status_text, statusText(ctx))
            bindList(ctx, this, id, rows)
        }
        return RemoteViews(landscape, portrait)
    }

    /** Color resource of the first event on each grid day that has one. */
    private fun busyDays(ctx: Context, month: YearMonth): Map<LocalDate, Int> {
        val zone = ZoneId.systemDefault()
        val (gridStart, weeks) = gridOf(month)
        val gridEnd = gridStart.plusDays(weeks * 7L)
        val busy = HashMap<LocalDate, Int>()
        for (o in CalendarRepository.occurrences(ctx, gridStart.atStartOfDay(zone), gridEnd.atStartOfDay(zone))) {
            var d = o.start.toLocalDate()
            val last = if (o.end > o.start && o.end.toLocalTime() == LocalTime.MIDNIGHT) o.end.toLocalDate().minusDays(1) else o.end.toLocalDate()
            while (d <= last && d < gridEnd) { busy.putIfAbsent(d, EVENT_COLOR_RES[AgendaRows.colorIndex(o.title, o.recurring)]); d = d.plusDays(1) }
        }
        return busy
    }

    /** The plain month: always the current month, with a dot on days that have events. */
    private fun simpleMonthViews(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val (portraitW, landscapeW) = widthsDp(mgr, id)
        val month = YearMonth.now()
        val busy = busyDays(ctx, month)
        fun v(width: Int) = RemoteViews(ctx.packageName, R.layout.widget_month_simple).apply {
            fillGrid(ctx, this, month, busy, titleSpace = width - 24 - 4, selected = null) { openDay(ctx, it) }
            setOnClickPendingIntent(R.id.month_title, openApp(ctx))
        }
        return RemoteViews(v(landscapeW), v(portraitW))
    }

    /** Widget width in dp in portrait and in landscape, as reported by the launcher (0 if unknown). */
    private fun widthsDp(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        return o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) to o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
    }

    /** "December 2026", or "Dec 2026", or "Dec" — the longest that fits in [spaceDp] at 18sp bold. */
    private fun monthTitle(ctx: Context, month: YearMonth, spaceDp: Int): String {
        val locale = Locale.getDefault()
        val options = listOf("LLLL yyyy", "LLL yyyy", "LLL").map { month.format(DateTimeFormatter.ofPattern(it, locale)) }
        if (spaceDp <= 0) return options[0] // size unknown
        val charDp = 18f * 0.6f * ctx.resources.configuration.fontScale
        return options.firstOrNull { it.length * charDp <= spaceDp } ?: options.last()
    }

    /** First cell of the grid (start of the week containing the 1st) and the number of week rows. */
    private fun gridOf(month: YearMonth): Pair<LocalDate, Int> {
        val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val gridStart = month.atDay(1).with(java.time.temporal.TemporalAdjusters.previousOrSame(firstDow))
        val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(gridStart, month.atEndOfMonth()) / 7) + 1).toInt()
        return gridStart to weeks
    }

    /**
     * Fills a month_panel / month_panel_simple grid. Today's box is filled with the accent color,
     * [selected] gets an outline, and tapping a date fires [onDay].
     */
    private fun fillGrid(
        ctx: Context, v: RemoteViews, month: YearMonth, busy: Map<LocalDate, Int>?, titleSpace: Int,
        selected: LocalDate?, onDay: (LocalDate) -> PendingIntent,
    ) {
        val today = LocalDate.now()
        val (gridStart, weeks) = gridOf(month)
        val firstDow = gridStart.dayOfWeek

        v.setTextViewText(R.id.month_title, monthTitle(ctx, month, titleSpace))
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
            val box = when {
                isToday -> R.drawable.today_box
                day == selected -> R.drawable.selected_box
                else -> 0
            }
            v.setInt(MonthIds.boxes[i], "setBackgroundResource", box)
            v.setOnClickPendingIntent(MonthIds.boxes[i], onDay(day))
            if (busy != null) {
                val dotColor = busy[day]
                v.setViewVisibility(MonthIds.dots[i], if (dotColor != null) View.VISIBLE else View.INVISIBLE)
                // On today's filled box a colored dot could vanish into the fill, so it uses the on-accent color.
                if (dotColor != null) setTintRes(ctx, v, MonthIds.dots[i], if (isToday) R.color.widget_on_accent else dotColor)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** One agenda list row. */
    fun rowViews(ctx: Context, row: AgendaRow): RemoteViews = when (row) {
        is AgendaRow.Day -> RemoteViews(ctx.packageName, R.layout.widget_item_day).apply {
            setTextViewText(R.id.day_text, row.label)
            setTextColorRes(ctx, this, R.id.day_text, if (row.isToday) R.color.widget_today_label else R.color.widget_accent)
            setOnClickFillInIntent(R.id.day_text, Intent().putExtra(EXTRA_DATE, row.date.toEpochDay()))
        }
        is AgendaRow.Event -> RemoteViews(ctx.packageName, R.layout.widget_item_event).apply {
            setTextViewText(R.id.event_title, row.title)
            setTintRes(ctx, this, R.id.event_bar, EVENT_COLOR_RES[row.colorIndex])
            setTextViewText(R.id.event_detail, row.detail)
            setViewVisibility(R.id.event_detail, if (row.detail.isBlank()) View.GONE else View.VISIBLE)
            setOnClickFillInIntent(R.id.event_root, Intent().putExtra(EXTRA_DATE, row.date.toEpochDay()))
        }
    }

    val EVENT_COLOR_RES = intArrayOf(
        R.color.event_0, R.color.event_1, R.color.event_2, R.color.event_3, R.color.event_4, R.color.event_5,
    )

    /** Tints an ImageView; on Android 12+ the launcher resolves the color, so it follows theme changes live. */
    fun setTintRes(ctx: Context, views: RemoteViews, viewId: Int, colorRes: Int) {
        if (Build.VERSION.SDK_INT >= 31) views.setColorStateList(viewId, "setImageTintList", colorRes)
        else views.setInt(viewId, "setColorFilter", ctx.getColor(colorRes))
    }

    private fun isWeekend(d: java.time.DayOfWeek) = d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY

    /** On Android 12+ the color resource is resolved by the launcher, so it follows light/dark mode live. */
    fun setTextColorRes(ctx: Context, views: RemoteViews, viewId: Int, colorRes: Int) {
        if (Build.VERSION.SDK_INT >= 31) views.setColorStateList(viewId, "setTextColor", colorRes)
        else views.setTextColor(viewId, ctx.getColor(colorRes))
    }

    /**
     * Android 12+: the rows go inline with the update. (Android 16 converts a service-backed list into
     * inline items itself, but holds the widget's updates back while it binds the service, which made
     * the month arrows and refresh look dead.) Older versions use CalendarWidgetService.
     */
    private fun bindList(ctx: Context, views: RemoteViews, id: Int, rows: List<AgendaRow>) {
        if (Build.VERSION.SDK_INT >= 31) {
            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2).setHasStableIds(false)
            rows.forEachIndexed { i, row -> items.addItem(i.toLong(), rowViews(ctx, row)) }
            views.setRemoteAdapter(R.id.event_list, items.build())
        } else {
            val svc = Intent(ctx, CalendarWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) // unique per widget
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.event_list, svc)
        }
        views.setEmptyView(R.id.event_list, R.id.empty_view)
        // Row taps open the day screen; each row fills in its date. Must be mutable for the fill-in.
        views.setPendingIntentTemplate(
            R.id.event_list,
            PendingIntent.getActivity(
                ctx, 1, Intent(ctx, DayActivity::class.java),
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

    /** Opens the day screen on [day]. */
    private fun openDay(ctx: Context, day: LocalDate): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, DayActivity::class.java)
            .putExtra(EXTRA_DATE, day.toEpochDay())
            .setData(Uri.parse("icalwidget://day/${day.toEpochDay()}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Moves a month widget's list to [day]. */
    private fun selectDay(ctx: Context, id: Int, day: LocalDate): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, MonthWidgetProvider::class.java).setAction(ACTION_SELECT_DAY)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(EXTRA_DATE, day.toEpochDay())
            .setData(Uri.parse("icalwidget://select/$id/${day.toEpochDay()}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
