package com.businesscard.scanner.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.businesscard.scanner.R
import com.businesscard.scanner.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BusinessCardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val count = AppDatabase.getDatabase(context).businessCardDao().getCount()
                appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id, count) }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            contactCount: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_business_card)

            views.setTextViewText(
                R.id.widget_count,
                context.resources.getQuantityString(R.plurals.widget_contact_count, contactCount, contactCount)
            )

            val openIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val scanIntent = PendingIntent.getActivity(
                context, 1,
                Intent(context, ScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_btn_open, openIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_scan, scanIntent)
            views.setOnClickPendingIntent(R.id.widget_count, openIntent)

            manager.updateAppWidget(widgetId, views)
        }
    }
}
