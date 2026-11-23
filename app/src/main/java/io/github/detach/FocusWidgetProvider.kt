package io.github.detach

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

class FocusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> update(context, manager, id, manager.getAppWidgetOptions(id)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id, options)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val time = FocusSession.durationText(FocusSession.lastDurationSeconds(context))
        val views = if (Build.VERSION.SDK_INT >= 31) {
            RemoteViews(Layout.entries.associate { SizeF(it.width, it.height) to views(context, id, it, time) })
        } else {
            views(context, id, layoutFor(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 56),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 56),
            ), time)
        }
        manager.updateAppWidget(id, views)
    }

    internal enum class Layout(val width: Float, val height: Float, val resource: Int) {
        COMPACT(56f, 56f, R.layout.widget_focus_compact),
        STRIP(112f, 56f, R.layout.widget_focus_strip),
        PANEL(126f, 110f, R.layout.widget_focus_panel),
    }

    companion object {
        internal fun layoutFor(width: Int, height: Int): Layout =
            Layout.entries.lastOrNull { width >= it.width && height >= it.height } ?: Layout.COMPACT

        internal fun views(context: Context, id: Int, layout: Layout, time: String) = RemoteViews(context.packageName, layout.resource).apply {
            val pick = launch(context, id, FocusWidgetActivity.CHOOSE_DURATION)
            setOnClickPendingIntent(R.id.focus_widget_root, pick)
            if (layout != Layout.COMPACT) setTextViewText(R.id.focus_widget_time, time)
            if (layout == Layout.PANEL) {
                setOnClickPendingIntent(R.id.focus_widget_time, pick)
                setContentDescription(R.id.focus_widget_time, "Change Focus duration, $time")
                setOnClickPendingIntent(R.id.focus_widget_start, launch(context, id, FocusWidgetActivity.START_SAVED))
                setContentDescription(R.id.focus_widget_start, "Start Focus for $time")
            } else {
                setContentDescription(R.id.focus_widget_root, "Choose Focus duration, $time")
            }
        }

        private fun launch(context: Context, id: Int, action: String) = PendingIntent.getActivity(
            context, id, Intent(context, FocusWidgetActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun updateAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, FocusWidgetProvider::class.java))
            if (ids.isNotEmpty()) FocusWidgetProvider().onUpdate(app, manager, ids)
        }
    }
}
