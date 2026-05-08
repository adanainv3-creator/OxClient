package com.oxclient.ui.overlay

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object OverlayNotifier {
    fun notify(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, OverlayService.CHANNEL_ID)
            .setSmallIcon(com.oxclient.R.drawable.ic_ox_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        nm.notify(9001, notif)
    }
}
