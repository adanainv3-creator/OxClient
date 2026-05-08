package com.oxclient.session

import android.util.Log

/**
 * SessionManager
 *
 * VPN/proxy oturumunun aktif olup olmadığını izler.
 * Java core sınıflarına (EntityTracker, MitmProxy) bağımlılık kaldırıldı.
 * Oturum durumu OxVpnService tarafından güncellenir.
 */
object SessionManager {

    private const val TAG = "SessionManager"

    @Volatile
    var isActive: Boolean = false
        private set

    /** Bağlı sunucu — VPN başlatıldığında set edilir */
    @Volatile
    var connectedHost: String = ""
        private set

    @Volatile
    var connectedPort: Int = 0
        private set

    /** VPN tüneli kurulduğunda OxVpnService tarafından çağrılır */
    fun onSessionStart(host: String, port: Int) {
        isActive       = true
        connectedHost  = host
        connectedPort  = port
        Log.i(TAG, "Session başladı → $host:$port")
    }

    /** VPN durdurulduğunda OxVpnService tarafından çağrılır */
    fun onSessionStop() {
        isActive      = false
        connectedHost = ""
        connectedPort = 0
        Log.i(TAG, "Session sona erdi")
    }
}
