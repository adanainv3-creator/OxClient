package com.oxclient.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import com.oxclient.config.ServerConfig
import com.oxclient.core.proxy.MITMProxy
import com.oxclient.session.SessionManager
import com.oxclient.ui.dashboard.DashboardActivity
import kotlinx.coroutines.*

/**
 * OxVpnService
 *
 * Mimarisi: VPN tüneli AÇILMAZ.
 * VpnService'ten sadece protect() için türetildi.
 * MITMProxy UDP:19132'de dinler.
 * Minecraft → LAN listesi üzerinden proxy'yi görür ve bağlanır.
 * Proxy → gerçek sunucuya paketleri iletir.
 *
 * Neden VPN tüneli yok:
 * - VPN tüneli açılırsa Minecraft'ın Xbox auth trafiği (TCP/443) tünele girer
 *   → "Çok Oyunculu Bağlantı Başarısız" / zaman aşımı hatası
 * - addRoute sadece UDP değil tüm trafiği yakalar
 * - Loopback relay bu sorunu yaşamaz
 *
 * Kullanım adımları:
 * 1. OxVpnService başlat (ACTION_START)
 * 2. Minecraft aç
 * 3. Oyun → Arkadaşlar → LAN Oyunları → "OxRelay" görünür
 * 4. Bağlan → relay gerçek sunucuya yönlendirir
 */
class OxVpnService : VpnService() {

    companion object {
        private const val TAG        = "OxVpnService"
        const val ACTION_START       = "com.oxclient.vpn.START"
        const val ACTION_STOP        = "com.oxclient.vpn.STOP"
        private const val NOTIF_ID   = 1337
        private const val CHANNEL_ID = "oxclient_relay"
        const val PROXY_PORT         = 19132
    }

    private var mitmProxy   : MITMProxy?           = null
    private var dummyVpnFd  : ParcelFileDescriptor? = null
    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP  -> handleStop()
            else         -> { Log.w(TAG, "Bilinmeyen action: ${intent?.action}"); stopSelf() }
        }
        return START_STICKY
    }

    override fun onRevoke()  { handleStop(); super.onRevoke() }
    override fun onDestroy() { handleStop(); scope.cancel(); super.onDestroy() }

    // ─────────────────────────────────────────────────────────────────────

    private fun handleStart() {
        Log.i(TAG, "OxRelay başlatılıyor…")

        try {
            startForeground(NOTIF_ID, buildNotification("Başlatılıyor…"))
        } catch (e: Exception) {
            Log.e(TAG, "Foreground başlatma hatası", e)
        }

        scope.launch {
            try {
                val targetHost = ServerConfig.getHostBlocking()
                val targetPort = ServerConfig.getPortBlocking()
                Log.i(TAG, "Hedef: $targetHost:$targetPort")

                val proxy = MITMProxy(
                    targetHost = targetHost,
                    targetPort = targetPort,
                    listenPort = PROXY_PORT
                )
                proxy.start()
                mitmProxy = proxy

                delay(300)

                if (!proxy.isRunning) {
                    Log.e(TAG, "Relay başlatılamadı!")
                    showToast("❌ Port $PROXY_PORT meşgul! Minecraft açıksa kapat ve tekrar dene.")
                    stopSelf()
                    return@launch
                }

                // Soketleri VPN bypass için koru
                proxy.getListenSocket()?.let { sock ->
                    runCatching { protect(sock) }
                        .onSuccess { Log.d(TAG, "ListenSocket korundu") }
                        .onFailure { Log.w(TAG, "ListenSocket koruma başarısız: ${it.message}") }
                }
                proxy.getServerSocket()?.let { sock ->
                    runCatching { protect(sock) }
                        .onSuccess { Log.d(TAG, "ServerSocket korundu") }
                        .onFailure { Log.w(TAG, "ServerSocket koruma başarısız: ${it.message}") }
                }

                SessionManager.onSessionStart(targetHost, targetPort)
                updateNotification("Aktif → $targetHost:$targetPort")

                Log.i(TAG, "✓ OxRelay AKTİF — $targetHost:$targetPort ← relay :$PROXY_PORT")
                showToast("✓ OxRelay aktif! Minecraft → Arkadaşlar → LAN → OxRelay'e bağlan")

            } catch (e: CancellationException) {
                Log.i(TAG, "İptal edildi")
            } catch (e: Exception) {
                Log.e(TAG, "OxRelay başlatma hatası", e)
                showToast("❌ Hata: ${e.message}")
                handleStop()
            }
        }
    }

    private fun handleStop() {
        Log.i(TAG, "OxRelay durduruluyor…")
        mitmProxy?.stop()
        mitmProxy = null
        try { dummyVpnFd?.close() } catch (_: Exception) {}
        dummyVpnFd = null
        SessionManager.onSessionStop()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BİLDİRİM
    // ─────────────────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        createChannel()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, OxVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OxRelay")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopPi)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(status))
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OxClient Relay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "OxRelay proxy durumu"
                    setShowBadge(false)
                }
            )
        } catch (_: Exception) {}
    }

    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) {
            try { Toast.makeText(this@OxVpnService, msg, Toast.LENGTH_LONG).show() }
            catch (_: Exception) {}
        }
    }
}
