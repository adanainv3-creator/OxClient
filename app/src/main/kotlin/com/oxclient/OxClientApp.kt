package com.oxclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.oxclient.ui.overlay.OverlayService

class OxClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Overlay kanalı
        nm.createNotificationChannel(
            NotificationChannel(
                OverlayService.CHANNEL_ID,
                "OxClient Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Oyun içi HUD overlay"
                setShowBadge(false)
            }
        )
    }
}
