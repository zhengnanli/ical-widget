package com.paulli.icalwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/** Agenda widget: upcoming events grouped by day. */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        Widgets.refreshAsync(this, ctx, force = false)

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Widgets.ACTION_REFRESH -> Widgets.refreshAsync(this, ctx, force = true)
            Widgets.ACTION_MIDNIGHT, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> Widgets.refreshAsync(this, ctx, force = false)
            else -> super.onReceive(ctx, intent)
        }
    }
}

/** Month widget: a month grid with today highlighted (plus the agenda list in landscape). */
class MonthWidgetProvider : AppWidgetProvider() {

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        Widgets.renderAll(ctx)

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        Widgets.refreshAsync(this, ctx, force = false)

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) {
            CalendarRepository.setMonthOffset(ctx, id, 0)
            CalendarRepository.setSelectedDay(ctx, id, null)
        }
    }

    private fun showMonth(ctx: Context, id: Int, offset: Int) {
        CalendarRepository.setMonthOffset(ctx, id, offset)
        CalendarRepository.setSelectedDay(ctx, id, null)
        Widgets.refreshAsync(this, ctx, false)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val offset = CalendarRepository.monthOffset(ctx, id)
        when (intent.action) {
            Widgets.ACTION_REFRESH -> Widgets.refreshAsync(this, ctx, force = true)
            Widgets.ACTION_PREV_MONTH -> showMonth(ctx, id, offset - 1)
            Widgets.ACTION_NEXT_MONTH -> showMonth(ctx, id, offset + 1)
            Widgets.ACTION_THIS_MONTH -> showMonth(ctx, id, 0)
            Widgets.ACTION_SELECT_DAY -> {
                val day = java.time.LocalDate.ofEpochDay(intent.getLongExtra(Widgets.EXTRA_DATE, 0))
                // Tapping the selected date again goes back to the default list.
                val current = CalendarRepository.selectedDay(ctx, id)
                CalendarRepository.setSelectedDay(ctx, id, if (day == current) null else day)
                Widgets.refreshAsync(this, ctx, false)
            }
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> Widgets.refreshAsync(this, ctx, false)
            else -> super.onReceive(ctx, intent)
        }
    }
}

/** Plain month grid: current month, today highlighted, a dot on days with events. */
class SimpleMonthWidgetProvider : AppWidgetProvider() {

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        Widgets.renderAll(ctx)

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        Widgets.refreshAsync(this, ctx, force = false)

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> Widgets.refreshAsync(this, ctx, force = false)
            else -> super.onReceive(ctx, intent)
        }
    }
}
