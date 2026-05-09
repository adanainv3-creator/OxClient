package com.oxclient.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import com.oxclient.config.ServerConfig
import com.oxclient.core.proxy.MITMProxy
import com.oxclient.session.SessionManager
import com.oxclient.ui.dashboard.DashboardActivity
import kotlinx.coroutines.*

class OxVpnService : VpnService() {

    companion object {
        private const val TAG = "OxVpnService"
        const val ACTION_START = "com.oxclient.vpn.START"
        const val ACTION_STOP  = "com.oxclient.vpn.STOP"

        private const val NOTIF_ID   = 1337
        private const val CHANNEL_ID = "oxclient_vpn"
        private const val PROXY_PORT = 19133
        private const val TUN_ADDR   = "10.233.0.1"
        private const val TUN_MTU    = 1500
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var mitmProxy: MITMProxy? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP  -> handleStop()
            else -> stopSelf()
        }
        return START_STICKY
    }

    override fun onRevoke() { handleStop(); super.onRevoke() }
    override fun onDestroy() { handleStop(); scope.cancel(); super.onDestroy() }

    private fun handleStart() {
        Log.i(TAG, "VPN başlatılıyor…")

        try {
            startForeground(NOTIF_ID, buildNotification())
            Log.i(TAG, "Foreground OK")
        } catch (e: Exception) {
            Log.e(TAG, "Foreground hata", e)
        }

        scope.launch {
            try {
                val targetHost = ServerConfig.getHostBlocking()
                val targetPort = ServerConfig.getPortBlocking()
                Log.i(TAG, "Hedef: $targetHost:$targetPort")

                // ÖNCE proxy başlat
                val proxy = MITMProxy(targetHost, targetPort, PROXY_PORT)
                proxy.start()
                mitmProxy = proxy
                delay(300)
                Log.i(TAG, "Proxy: ${proxy.isRunning}")

                // SONRA VPN tüneli
                val tun = buildVpnInterface()
                if (tun == null) {
                    Log.e(TAG, "VPN NULL — 2. yöntem deneniyor…")
                    val tun2 = buildVpnInterfaceFallback()
                    if (tun2 == null) {
                        Log.e(TAG, "VPN tamamen başarısız")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@OxVpnService, "❌ VPN kurulamadı! Lütfen diğer VPN'leri kapatıp tekrar deneyin.", Toast.LENGTH_LONG).show()
                        }
                        proxy.stop()
                        stopSelf()
                        return@launch
                    }
                    vpnInterface = tun2
                    Log.i(TAG, "VPN fallback OK")
                } else {
                    vpnInterface = tun
                    Log.i(TAG, "VPN OK")
                }

                // Socket'leri koru
                proxy.getListenSocket()?.let { s -> runCatching { protect(s) }.onSuccess { Log.i(TAG, "ListenSocket korundu") } }
                proxy.getServerSocket()?.let { s -> runCatching { protect(s) }.onSuccess { Log.i(TAG, "ServerSocket korundu") } }

                SessionManager.onSessionStart(targetHost, targetPort)
                Log.i(TAG, "✓ VPN AKTIF")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OxVpnService, "✓ VPN aktif!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "İptal")
            } catch (e: Exception) {
                Log.e(TAG, "VPN HATA", e)
                handleStop()
            }
        }
    }

    /** Birincil yöntem: addAllowedApplication ile sadece Minecraft */
    private fun buildVpnInterface(): ParcelFileDescriptor? {
        return try {
            Builder().apply {
                setMtu(TUN_MTU)
                addAddress(TUN_ADDR, 32)
                addDnsServer("8.8.8.8")
                addDnsServer("1.1.1.1")
                // SADECE addAllowed — route YOK
                addAllowedApplication("com.mojang.minecraftpe")
                runCatching { addAllowedApplication("com.netease.mc") }
                runCatching { addAllowedApplication("com.mojang.minecrafttrialpe") }
                setSession("OxClient")
                setConfigureIntent(PendingIntent.getActivity(
                    this@OxVpnService, 0,
                    Intent(this@OxVpnService, DashboardActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
            }.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Birincil VPN hatası: ${e.message}")
            null
        }
    }

    /** Yedek yöntem: Tüm trafiği yakala, filtre yok */
    private fun buildVpnInterfaceFallback(): ParcelFileDescriptor? {
        return try {
            Builder().apply {
                setMtu(TUN_MTU)
                addAddress(TUN_ADDR, 32)
                addRoute("0.0.0.0", 0)
                // addAllowedApplication YOK — tüm trafik VPN'den geçer
                // addDisallowedApplication YOK
                setSession("OxClient")
                setConfigureIntent(PendingIntent.getActivity(
                    this@OxVpnService, 0,
                    Intent(this@OxVpnService, DashboardActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
            }.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Fallback VPN hatası: ${e.message}")
            null
        }
    }

    private fun handleStop() {
        Log.i(TAG, "VPN durduruluyor…")
        mitmProxy?.stop()
        mitmProxy = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        SessionManager.onSessionStop()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun buildNotification(): Notification {
        createChannel()
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, OxVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OxClient VPN Aktif")
            .setContentText("Minecraft trafiği yönlendiriliyor")
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
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "OxClient VPN", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN bağlantı durumu"
                setShowBadge(false)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Kanal hatası", e)
        }
    }
}