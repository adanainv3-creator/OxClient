package com.oxclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.proxy.BedrockRelayService
import com.oxclient.ui.overlay.OverlayService

class OxClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        MicrosoftAuthManager.init(this)
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

        // Relay kanalı
        nm.createNotificationChannel(
            NotificationChannel(
                BedrockRelayService.CHANNEL_ID,
                "OxClient MITM Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bedrock MITM proxy servisi"
                setShowBadge(false)
            }
        )
    }
}