package com.oxclient.proxy

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oxclient.R
import com.oxclient.module.ModuleManager
import com.oxclient.session.ServerConfig
import com.oxclient.ui.dashboard.DashboardActivity
import kotlinx.coroutines.*

/**
 * BedrockRelayService
 *
 * MITM proxy'yi ön planda çalışan bir Android Service olarak yönetir.
 * DashboardActivity ve OverlayService bu servise Binder üzerinden bağlanır.
 */
class BedrockRelayService : Service() {

    private val TAG = "BedrockRelayService"

    private val binder = RelayBinder()
    val relay          = BedrockRelay()
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var isRunning = false
        private set

    companion object {
        const val CHANNEL_ID     = "ox_relay_channel"
        const val NOTIF_ID       = 2001
        const val ACTION_STOP    = "com.oxclient.ACTION_STOP_RELAY"
        const val EXTRA_HOST     = "host"
        const val EXTRA_PORT     = "port"
        const val EXTRA_NAME     = "name"
    }

    inner class RelayBinder : Binder() {
        fun getService() = this@BedrockRelayService
    }

    // ─────────────────────────────────────────────────────────────────────
    //  YAŞAM DÖNGÜSÜ
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRelay()
            stopSelf()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra(EXTRA_HOST) ?: "geo.hivebedrock.network"
        val port = intent?.getIntExtra(EXTRA_PORT, 19132) ?: 19132
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "Hive"

        startForeground(NOTIF_ID, buildNotification("Bağlanıyor: $name"))
        startRelay(ServerConfig(host = host, port = port, name = name))

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopRelay()
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RELAY YÖNETİMİ
    // ─────────────────────────────────────────────────────────────────────

    private fun startRelay(server: ServerConfig) {
        scope.launch {
            try {
                ModuleManager.initAll()
                relay.start(server)
                isRunning = true
                updateNotification("MITM aktif → ${server.name}")
                Log.i(TAG, "Relay başlatıldı: ${server.host}:${server.port}")
            } catch (e: Exception) {
                Log.e(TAG, "Relay başlatma hatası", e)
                isRunning = false
                stopSelf()
            }
        }
    }

    fun stopRelay() {
        if (!isRunning) return
        isRunning = false
        relay.stop()
        ModuleManager.disableAll()
        Log.i(TAG, "Relay durduruldu")
    }

    fun getStats() = relay.getStats()

    // ─────────────────────────────────────────────────────────────────────
    //  BİLDİRİM
    // ─────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OxClient MITM Relay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bedrock MITM proxy servisi"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BedrockRelayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.oxclient.R.drawable.ic_ox_logo)
            .setContentTitle("OxClient")
            .setContentText(status)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }
}
