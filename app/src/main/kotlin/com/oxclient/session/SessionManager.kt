package com.oxclient.session

import android.util.Log
import com.oxclient.config.ServerConfig
import com.oxclient.core.relay.OxRelay
import com.oxclient.events.PacketEventBus
import com.oxclient.module.ModuleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SessionManager — OxRelay yaşam döngüsünü yönetir.
 * OverlayService tarafından start/stop edilir.
 */
object SessionManager {

    private const val TAG = "SessionManager"

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _connectedHost = MutableStateFlow("")
    val connectedHostFlow: StateFlow<String> = _connectedHost.asStateFlow()

    private val _connectedPort = MutableStateFlow(0)
    val connectedPortFlow: StateFlow<Int> = _connectedPort.asStateFlow()

    val connectedHost: String get() = _connectedHost.value
    val connectedPort: Int    get() = _connectedPort.value

    private var relay: OxRelay? = null

    // ── Başlat ────────────────────────────────────────────────────────────

    fun start() {
        if (_isActive.value) {
            Log.w(TAG, "Relay zaten çalışıyor")
            return
        }

        val host = ServerConfig.getHostBlocking()
        val port = ServerConfig.getPortBlocking()

        Log.i(TAG, "Relay başlatılıyor → $host:$port")

        try {
            val r = OxRelay(localPort = ServerConfig.LOCAL_PROXY_PORT)
            r.capture(remoteHost = host, remotePort = port) {
                Log.i(TAG, "OxRelaySession oluşturuldu")
            }

            relay = r
            _isActive.value      = true
            _connectedHost.value = host
            _connectedPort.value = port

            Log.i(TAG, "Relay aktif → $host:$port")

        } catch (e: Exception) {
            Log.e(TAG, "Relay başlatılamadı: ${e.message}", e)
            _isActive.value = false
        }
    }

    // ── Durdur ────────────────────────────────────────────────────────────

    fun stop() {
        if (!_isActive.value) return

        Log.i(TAG, "Relay durduruluyor")
        try {
            ModuleManager.disableAll()
            relay?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Relay durdurma hatası: ${e.message}", e)
        } finally {
            relay                = null
            _isActive.value      = false
            _connectedHost.value = ""
            _connectedPort.value = 0
        }
    }

    // Eski API uyumluluğu
    fun onSessionStart(host: String, port: Int) = start()
    fun onSessionStop()                          = stop()
}
