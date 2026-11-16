package com.paulli.icalwidget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.ZonedDateTime

class CalendarWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsFactory {
        private var rows: List<AgendaRow> = emptyList()

        override fun onCreate() {}

        // Reads the cached feed; the providers handle downloading.
        override fun onDataSetChanged() {
            rows = AgendaRows.build(
                CalendarRepository.upcoming(ctx),
                ZonedDateTime.now(),
                CalendarRepository.daysAhead(ctx),
                DateFormat.is24HourFormat(ctx),
            )
        }

        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            return when (val row = rows.getOrNull(position)) {
                is AgendaRow.Day -> RemoteViews(ctx.packageName, R.layout.widget_item_day).apply {
                    setTextViewText(R.id.day_text, row.label)
                    Widgets.setTextColorRes(ctx, this, R.id.day_text, if (row.isToday) R.color.widget_today_label else R.color.widget_accent)
                }
                is AgendaRow.Event -> RemoteViews(ctx.packageName, R.layout.widget_item_event).apply {
                    setTextViewText(R.id.event_title, row.title)
                    Widgets.setTintRes(ctx, this, R.id.event_bar, Widgets.EVENT_COLOR_RES[row.colorIndex])
                    setTextViewText(R.id.event_detail, row.detail)
                    setViewVisibility(R.id.event_detail, if (row.detail.isBlank()) View.GONE else View.VISIBLE)
                    setOnClickFillInIntent(R.id.event_root, Intent())
                }
                null -> RemoteViews(ctx.packageName, R.layout.widget_item_day)
            }
        }
    }
}
