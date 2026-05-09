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
import java.net.InetAddress

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
        Log.i(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP  -> handleStop()
            else -> { Log.w(TAG, "Bilinmeyen action: ${intent?.action}"); stopSelf() }
        }
        return START_STICKY
    }

    override fun onRevoke() { Log.w(TAG, "onRevoke"); handleStop(); super.onRevoke() }
    override fun onDestroy() { Log.d(TAG, "onDestroy"); handleStop(); scope.cancel(); super.onDestroy() }

    private fun handleStart() {
        Log.i(TAG, "========== VPN BAŞLATILIYOR ==========")

        // ÖNCE bildirimi göster (foreground)
        try {
            val notif = buildNotification()
            startForeground(NOTIF_ID, notif)
            Log.i(TAG, "✓ Foreground bildirim gösterildi")
        } catch (e: Exception) {
            Log.e(TAG, "!!! Foreground başarısız !!!", e)
        }

        scope.launch {
            try {
                val targetHost = ServerConfig.getHostBlocking()
                val targetPort = ServerConfig.getPortBlocking()
                Log.i(TAG, "Hedef: $targetHost:$targetPort")

                val account = AccountManager.selectedAccount
                Log.i(TAG, "Hesap: ${account?.gamertag ?: "YOK (anonim)"}")

                // Proxy başlat
                Log.i(TAG, "Proxy başlatılıyor…")
                val proxy = MITMProxy(targetHost, targetPort, PROXY_PORT)
                proxy.start()
                mitmProxy = proxy
                delay(300)
                Log.i(TAG, "✓ Proxy başlatıldı, çalışıyor=${proxy.isRunning}")

                // VPN tüneli
                Log.i(TAG, "VPN arayüzü oluşturuluyor…")
                val tun = buildVpnInterface(targetHost)
                if (tun == null) {
                    Log.e(TAG, "!!! VPN arayüzü NULL !!!")
                    proxy.stop()
                    stopSelf()
                    return@launch
                }
                vpnInterface = tun
                Log.i(TAG, "✓ VPN arayüzü oluşturuldu")

                // Socket'leri koru
                proxy.getListenSocket()?.let { s ->
                    runCatching { protect(s) }
                        .onSuccess { Log.i(TAG, "✓ ListenSocket korundu") }
                        .onFailure { Log.e(TAG, "ListenSocket koruma hatası", it) }
                } ?: Log.w(TAG, "ListenSocket null!")

                proxy.getServerSocket()?.let { s ->
                    runCatching { protect(s) }
                        .onSuccess { Log.i(TAG, "✓ ServerSocket korundu") }
                        .onFailure { Log.e(TAG, "ServerSocket koruma hatası", it) }
                } ?: Log.w(TAG, "ServerSocket null!")

                SessionManager.onSessionStart(targetHost, targetPort)
                Log.i(TAG, "========== VPN AKTIF ==========")
            } catch (e: CancellationException) {
                Log.w(TAG, "İptal edildi")
            } catch (e: Exception) {
                Log.e(TAG, "!!! VPN BAŞLATMA HATASI !!!", e)
                handleStop()
            }
        }
    }

    private fun buildVpnInterface(targetHost: String): ParcelFileDescriptor? {
        return try {
            // Domain'i IP'ye çözümle
            val ip = try {
                InetAddress.getByName(targetHost).hostAddress ?: targetHost
            } catch (e: Exception) {
                Log.w(TAG, "IP çözümleme başarısız: ${e.message}, ham host kullanılıyor")
                targetHost
            }
            Log.i(TAG, "VPN route: $ip/32")

            Builder().apply {
                setMtu(TUN_MTU)
                addAddress(TUN_ADDR, 32)
                addDnsServer("8.8.8.8")
                addDnsServer("1.1.1.1")
                runCatching { addRoute(ip, 32) }.onFailure {
                    Log.w(TAG, "addRoute başarısız: ${it.message}, 0.0.0.0/0 deneniyor")
                    addRoute("0.0.0.0", 0)
                }
                setSession("OxClient")
                setConfigureIntent(
                    PendingIntent.getActivity(this@OxVpnService, 0,
                        Intent(this@OxVpnService, DashboardActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
                addAllowedApplication("com.mojang.minecraftpe")
                runCatching { addAllowedApplication("com.netease.mc") }
                runCatching { addAllowedApplication("com.mojang.minecrafttrialpe") }
                addDisallowedApplication(packageName)
            }.establish().also {
                if (it == null) Log.e(TAG, "!!! Builder.establish() NULL döndü !!!")
                else Log.i(TAG, "Builder.establish() başarılı")
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildVpnInterface HATA", e)
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
            val channel = NotificationChannel(CHANNEL_ID, "OxClient VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VPN bağlantı durumu"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Kanal oluşturma hatası", e)
        }
    }
}