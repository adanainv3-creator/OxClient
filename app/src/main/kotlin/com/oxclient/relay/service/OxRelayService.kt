package com.oxclient.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.core.app.NotificationCompat
import com.oxclient.R
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.modules.combat.OxRelayBridge
import com.oxclient.relay.OxAddress
import com.oxclient.relay.OxRelay
import com.oxclient.session.ServerConfig
import com.oxclient.session.SessionManager
import com.oxclient.ui.dashboard.DashboardActivity
import timber.log.Timber

/**
 * OxRelayService — MITM relay'i Android Foreground Service olarak çalıştırır.
 *
 * Başlatma:
 *   OxRelayService.start(context)    // DashboardActivity → launchGame() içinde
 *
 * Durdurma:
 *   OxRelayService.stop(context)     // stopSession() içinde
 *
 * Intent extras:
 *   EXTRA_HOST : String — hedef sunucu adresi
 *   EXTRA_PORT : Int    — hedef port
 */
class OxRelayService : Service() {

    companion object {
        private const val CHANNEL_ID  = "ox_relay_service"
        private const val NOTIF_ID    = 2001
        const val EXTRA_HOST          = "host"
        const val EXTRA_PORT          = "port"

        fun start(ctx: Context, host: String = ServerConfig.host.value, port: Int = ServerConfig.port.value) {
            val intent = Intent(ctx, OxRelayService::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            ctx.startForegroundService(intent)
            Timber.i("[RelayService] start() → $host:$port")
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OxRelayService::class.java))
            Timber.i("[RelayService] stop()")
        }
    }

    private var relay   : OxRelay?  = null
    private var wakeLock: WakeLock? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("[RelayService] onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_HOST) ?: ServerConfig.host.value
        val port = intent?.getIntExtra(EXTRA_PORT, ServerConfig.port.value) ?: ServerConfig.port.value

        startForeground(NOTIF_ID, buildNotification(host, port))
        acquireWakeLock()
        startRelay(host, port)

        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("[RelayService] onDestroy")
        stopRelay()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Relay ─────────────────────────────────────────────────────────────────

    private fun startRelay(host: String, port: Int) {
        if (relay?.isRunning == true) return

        val mcToken  = MicrosoftAuthManager.currentMcToken() ?: run {
            Timber.e("[RelayService] MC token yok — relay başlatılamıyor")
            stopSelf()
            return
        }
        val gamertag = MicrosoftAuthManager.currentGamertag() ?: "OxPlayer"

        Timber.i("[RelayService] Relay başlatılıyor → $host:$port  gamertag=$gamertag")

        val r = OxRelay()
        relay = r

        r.start(
            remoteAddress  = OxAddress(host, port),
            mcToken        = mcToken,
            gamertag       = gamertag,
            onSessionReady = { session ->
                OxRelayBridge.attach(session)
                SessionManager.onSessionStart()
                Timber.i("[RelayService] ✓ Session hazır")
            }
        )

        // Relay başarıyla başladıysa bildirimi güncelle
        if (r.isRunning) {
            updateNotification("✓ Relay aktif — $host:$port")
        } else {
            Timber.e("[RelayService] Relay başlatılamadı")
            stopSelf()
        }
    }

    private fun stopRelay() {
        OxRelayBridge.detach()
        relay?.stop()
        relay = null
        SessionManager.onSessionStop()
    }

    // ── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OxClient::RelayWakeLock")
        wakeLock?.acquire(60 * 60 * 1000L)  // max 1 saat
        Timber.d("[RelayService] WakeLock alındı")
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "OxClient Relay",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "MITM relay servisi" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(host: String = "", port: Int = 0, msg: String? = null): Notification {
        val dashIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ox_logo)
            .setContentTitle("OxClient — Relay Aktif")
            .setContentText(msg ?: "Hedef: $host:$port")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(dashIntent)
            .build()
    }

    private fun updateNotification(msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(msg = msg))
    }
}
