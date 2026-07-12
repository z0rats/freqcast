package com.urlradiodroid.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Registered in the manifest for `android.appwidget.action.APPWIDGET_UPDATE`; hosts [RadioWidget]. */
class RadioWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RadioWidget()
}
