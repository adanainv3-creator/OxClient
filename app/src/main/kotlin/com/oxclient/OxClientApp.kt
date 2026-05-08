package com.oxclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.oxclient.auth.AccountManager
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.proxy.BedrockRelayService
import com.oxclient.ui.overlay.OverlayService

class OxClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        MicrosoftAuthManager.init(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                OverlayService.CHANNEL_ID,
                "OxClient Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Oyun ici HUD overlay"
                setShowBadge(false)
            }
        )

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

    companion object {
        lateinit var instance: OxClientApp
            private set
    }
}