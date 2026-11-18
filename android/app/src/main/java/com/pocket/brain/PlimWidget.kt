package com.pocket.brain

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen button: one tap opens Chacha's orb, already listening. */
class PlimWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, OrbActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val views = RemoteViews(ctx.packageName, R.layout.widget_plim)
        views.setOnClickPendingIntent(R.id.widget_root, open)
        for (id in ids) mgr.updateAppWidget(id, views)
    }
}
