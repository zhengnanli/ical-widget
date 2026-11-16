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

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        Widgets.refreshAsync(this, ctx, force = false)

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) CalendarRepository.setMonthOffset(ctx, id, 0)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val offset = CalendarRepository.monthOffset(ctx, id)
        when (intent.action) {
            Widgets.ACTION_REFRESH -> Widgets.refreshAsync(this, ctx, force = true)
            Widgets.ACTION_PREV_MONTH -> { CalendarRepository.setMonthOffset(ctx, id, offset - 1); Widgets.refreshAsync(this, ctx, false) }
            Widgets.ACTION_NEXT_MONTH -> { CalendarRepository.setMonthOffset(ctx, id, offset + 1); Widgets.refreshAsync(this, ctx, false) }
            Widgets.ACTION_THIS_MONTH -> { CalendarRepository.setMonthOffset(ctx, id, 0); Widgets.refreshAsync(this, ctx, false) }
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> Widgets.refreshAsync(this, ctx, false)
            else -> super.onReceive(ctx, intent)
        }
    }
}

/** Plain month grid: current month, today highlighted, no events. */
class SimpleMonthWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.renderAll(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> Widgets.renderAll(ctx)
            else -> super.onReceive(ctx, intent)
        }
    }
}
