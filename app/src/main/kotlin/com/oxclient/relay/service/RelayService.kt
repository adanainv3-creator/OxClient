package com.oxclient.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oxclient.R
import com.oxclient.relay.OxRelay
import com.oxclient.session.RelaySessionManager
import com.oxclient.ui.dashboard.DashboardActivity

/**
 * RelayService — Bedrock Relay için Android Foreground Service.
 *
 * - START_STICKY: sistem tarafından öldürülürse yeniden başlar
 * - PARTIAL_WAKE_LOCK: ekran kapalıyken de paket işleme devam eder
 * - API 34+ için FOREGROUND_SERVICE_TYPE_SPECIAL_USE
 */
class RelayService : Service() {

    companion object {
        private const val TAG        = "RelayService"
        private const val CHANNEL_ID = "ox_relay"
        private const val NOTIF_ID   = 1003

        const val ACTION_START = "com.oxclient.relay.START"
        const val ACTION_STOP  = "com.oxclient.relay.STOP"
        const val EXTRA_HOST   = "target_host"
        const val EXTRA_PORT   = "target_port"

        fun start(ctx: Context, host: String, port: Int) {
            val intent = Intent(ctx, RelayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RelayService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private var relay    : OxRelay? = null
    private var wakeLock : PowerManager.WakeLock? = null
    private var relayThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        Log.d(TAG, "RelayService oluşturuldu")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: "2b2tpe.org"
                val port = intent.getIntExtra(EXTRA_PORT, 19132)
                startRelay(host, port)
            }
            ACTION_STOP -> {
                stopRelay()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRelay()
        releaseWakeLock()
        super.onDestroy()
        Log.d(TAG, "RelayService yok edildi")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Relay yönetimi ────────────────────────────────────────────────────

    private fun startRelay(host: String, port: Int) {
        if (relay?.isRunning == true) return

        startForeground(NOTIF_ID, buildNotif("Relay aktif → $host:$port"))
        Log.i(TAG, "Relay başlatılıyor: $host:$port")

        relay = OxRelay(targetHost = host, targetPort = port)

        // MAX_PRIORITY thread ile başlat — paket işleme gecikmesin
        relayThread = Thread.currentThread().apply {
            Thread({
                relay?.start()
            }, "OxRelayThread").also {
                it.priority = Thread.MAX_PRIORITY
                it.isDaemon = true
                it.start()
            }
        }.let {
            Thread({
                relay?.start()
            }, "OxRelayThread").also {
                it.priority = Thread.MAX_PRIORITY
                it.isDaemon = true
                it.start()
            }
        }

        RelaySessionManager.onRelayStarted(host, port)
    }

    private fun stopRelay() {
        Log.i(TAG, "Relay durduruluyor")
        relay?.stop()
        relay = null
        relayThread?.interrupt()
        relayThread = null
        RelaySessionManager.onRelayStoped()
    }

    // ── WakeLock ──────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OxClient:RelayWakeLock"
        ).also {
            it.acquire(6 * 60 * 60 * 1000L) // max 6 saat
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    // ── Bildirim ──────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OxClient Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Minecraft Bedrock relay servisi"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ox_logo)
            .setContentTitle("OxClient")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
