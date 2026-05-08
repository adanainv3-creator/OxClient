package com.oxclient.core.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * OxVpnService
 *
 * İleride loopback trafiğini doğrudan relay'e yönlendirmek için
 * genişletilebilir VPN servisi iskeleti.
 *
 * Şu anki yaklaşım: Kullanıcı Minecraft'ta manuel olarak
 *   Sunucu: 127.0.0.1 / Port: 19132 girer.
 * Bu nedenle VPN'e şimdilik ihtiyaç yoktur.
 */
class OxVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("OxVpnService", "VPN servisi başlatıldı (stub)")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        vpnInterface?.close()
        super.onDestroy()
    }
}
