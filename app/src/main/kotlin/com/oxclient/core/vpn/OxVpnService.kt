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
import com.oxclient.auth.AccountManager
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
            else -> { stopSelf() }
        }
        return START_STICKY
    }

    override fun onRevoke() { handleStop(); super.onRevoke() }

    override fun onDestroy() { handleStop(); scope.cancel(); super.onDestroy() }

    private fun handleStart() {
        Log.i(TAG, "VPN başlatılıyor…")
        startForeground(NOTIF_ID, buildNotification())

        scope.launch {
            try {
                val targetHost = ServerConfig.getHostBlocking()
                val targetPort = ServerConfig.getPortBlocking()
                Log.i(TAG, "Hedef: $targetHost:$targetPort")

                val account = AccountManager.selectedAccount
                if (account == null) Log.w(TAG, "Hesap yok — anonim bağlantı")

                // Proxy başlat
                val proxy = MITMProxy(targetHost, targetPort, PROXY_PORT)
                proxy.start()
                mitmProxy = proxy
                delay(200) // Socket'lerin açılmasını bekle

                // VPN tüneli
                val tun = buildVpnInterface(targetHost)
                if (tun == null) {
                    Log.e(TAG, "VPN arayüzü oluşturulamadı")
                    proxy.stop()
                    stopSelf()
                    return@launch
                }
                vpnInterface = tun

                // Proxy soketlerini VPN'den koru
                proxy.getListenSocket()?.let { runCatching { protect(it) } }
                proxy.getServerSocket()?.let { runCatching { protect(it) } }

                SessionManager.onSessionStart(targetHost, targetPort)
                Log.i(TAG, "VPN aktif ✓")
            } catch (e: Exception) {
                Log.e(TAG, "VPN başlatma hatası", e)
                handleStop()
            }
        }
    }

    private fun buildVpnInterface(targetHost: String): ParcelFileDescriptor? = try {
        Builder().apply {
            setMtu(TUN_MTU)
            addAddress(TUN_ADDR, 32)
            addDnsServer("8.8.8.8")
            addDnsServer("1.1.1.1")
            runCatching { addRoute(targetHost, 32) }
            setSession("OxClient")
            setConfigureIntent(
                PendingIntent.getActivity(this@OxVpnService, 0,
                    Intent(this@OxVpnService, DashboardActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            // Sadece Minecraft'ı yakala
            addAllowedApplication("com.mojang.minecraftpe")
            runCatching { addAllowedApplication("com.netease.mc") }
            runCatching { addAllowedApplication("com.mojang.minecrafttrialpe") }
            // Kendi trafiğini bypass et
            addDisallowedApplication(packageName)
        }.establish()
    } catch (e: Exception) { Log.e(TAG, "Builder hatası", e); null }

    private fun handleStop() {
        Log.i(TAG, "VPN durduruluyor…")
        mitmProxy?.stop()
        mitmProxy = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        SessionManager.onSessionStop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN durduruldu")
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
            .setContentTitle("OxClient Aktif")
            .setContentText("Trafik yönlendiriliyor")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OxClient VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VPN durumu"; setShowBadge(false)
            }
        )
    }
}
