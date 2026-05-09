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
 * Mimari: WClient ile aynı — VPN tüneli AÇILMAZ.
 * Sadece MITMProxy başlatılır (UDP 19132'de dinler).
 * Minecraft LAN broadcast üzerinden proxy'yi görür ve bağlanır.
 * Proxy paketi gerçek sunucuya iletir.
 *
 * Neden VPN tüneli yok?
 * - VPN tüneli açılırsa Minecraft'ın NetherNet/Xbox auth trafiği (TCP/443)
 *   tünele girmeye çalışır → "Çok Oyunculu Bağlantı Başarısız" hatası.
 * - addRoute(targetIp, 32) sadece UDP değil tüm trafiği yakalar.
 * - protect() sonrası socket bazen hâlâ tünelden geçer (Android bug).
 * - Loopback proxy bu sorunların hiçbirini yaşamaz.
 *
 * Akış:
 *   Minecraft → LAN discovery → proxy pong (127.0.0.1:19132)
 *   Minecraft → UDP 19132 → MITMProxy
 *   MITMProxy → UDP targetPort → Gerçek sunucu
 *   Gerçek sunucu → MITMProxy → Minecraft
 */
class OxVpnService : VpnService() {

    companion object {
        private const val TAG = "OxVpnService"
        const val ACTION_START = "com.oxclient.vpn.START"
        const val ACTION_STOP  = "com.oxclient.vpn.STOP"

        private const val NOTIF_ID   = 1337
        private const val CHANNEL_ID = "oxclient_vpn"
        const val  PROXY_PORT = 19132
    }

    private var mitmProxy: MITMProxy? = null

    // VPN tüneli AÇILMAZ — sadece VpnService.protect() için miras alıyoruz
    // Bu sayede proxy soketi VPN bypass edebilir (eğer başka VPN varsa)
    private var dummyVpnFd: ParcelFileDescriptor? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP  -> handleStop()
            else         -> stopSelf()
        }
        return START_STICKY
    }

    override fun onRevoke()  { handleStop(); super.onRevoke() }
    override fun onDestroy() { handleStop(); scope.cancel(); super.onDestroy() }

    private fun handleStart() {
        Log.i(TAG, "OxRelay başlatılıyor…")

        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Foreground hata", e)
        }

        scope.launch {
            try {
                val targetHost = ServerConfig.getHostBlocking()
                val targetPort = ServerConfig.getPortBlocking()
                Log.i(TAG, "Hedef sunucu: $targetHost:$targetPort")

                // Proxy başlat
                val proxy = MITMProxy(
                    targetHost = targetHost,
                    targetPort = targetPort,
                    listenPort = PROXY_PORT
                )
                proxy.start()
                mitmProxy = proxy
                delay(200)

                if (!proxy.isRunning) {
                    Log.e(TAG, "Proxy başlatılamadı!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@OxVpnService,
                            "❌ Proxy başlatılamadı! 19132 portu kullanımda olabilir.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    stopSelf()
                    return@launch
                }

                // Proxy soketlerini koru — başka bir VPN aktifse trafiği bypass et
                proxy.getListenSocket()?.let { s ->
                    runCatching { protect(s) }
                        .onSuccess { Log.i(TAG, "ListenSocket korundu") }
                        .onFailure { Log.w(TAG, "ListenSocket koruma başarısız: ${it.message}") }
                }
                proxy.getServerSocket()?.let { s ->
                    runCatching { protect(s) }
                        .onSuccess { Log.i(TAG, "ServerSocket korundu") }
                        .onFailure { Log.w(TAG, "ServerSocket koruma başarısız: ${it.message}") }
                }

                SessionManager.onSessionStart(targetHost, targetPort)

                Log.i(TAG, "✓ OxRelay AKTİF — $targetHost:$targetPort ← proxy :$PROXY_PORT")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OxVpnService,
                        "✓ OxRelay aktif! Minecraft'ta Friends → LAN'dan bağlan.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: CancellationException) {
                Log.w(TAG, "İptal edildi")
            } catch (e: Exception) {
                Log.e(TAG, "OxRelay başlatma hatası", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OxVpnService, "❌ Hata: ${e.message}", Toast.LENGTH_LONG).show()
                }
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

    private fun buildNotification(): Notification {
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
            .setContentTitle("OxRelay Aktif")
            .setContentText("Minecraft proxy üzerinden bağlanıyor")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopPi)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
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
        } catch (e: Exception) {
            Log.e(TAG, "Kanal hatası", e)
        }
    }
}
