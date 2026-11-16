package com.paulli.icalwidget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.ZonedDateTime

/** List adapter for Android 8–11; Android 12+ gets its rows inline (see Widgets.bindList). */
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

        override fun getViewAt(position: Int): RemoteViews =
            rows.getOrNull(position)?.let { Widgets.rowViews(ctx, it) } ?: RemoteViews(ctx.packageName, R.layout.widget_item_day)
    }
}
