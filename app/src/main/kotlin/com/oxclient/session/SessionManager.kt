package com.oxclient.session

import android.util.Log
import com.oxclient.auth.AccountManager
import com.oxclient.config.ServerConfig
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.ConnectionManager
import com.oxclient.core.relay.OxRelay
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEventBus
import com.oxclient.module.ModuleManager
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.BlockTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {

    private const val TAG = "SessionManager"

    private val _isActive        = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _connectedHost   = MutableStateFlow("")
    val connectedHostFlow: StateFlow<String> = _connectedHost.asStateFlow()

    private val _connectedPort   = MutableStateFlow(0)
    val connectedPortFlow: StateFlow<Int> = _connectedPort.asStateFlow()

    private val _statusMessage   = MutableStateFlow("Kapalı")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _sessionCount    = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount.asStateFlow()

    val connectedHost: String get() = _connectedHost.value
    val connectedPort: Int    get() = _connectedPort.value

    private var relay: OxRelay? = null

    fun start() {
        if (_isActive.value) { Log.w(TAG, "Relay zaten çalışıyor"); return }

        val account = AccountManager.getRelayReadyAccount()
        if (account == null) {
            OverlayLogger.w(TAG, "Relay başlatılamadı: geçerli hesap yok")
            _statusMessage.value = "Hesap bulunamadı"
            return
        }

        val host      = ServerConfig.getHostBlocking()
        val port      = ServerConfig.getPortBlocking()
        val localPort = ServerConfig.LOCAL_PROXY_PORT

        OverlayLogger.i(TAG, "Relay başlatılıyor: ${account.gamertag} → $host:$port")
        _statusMessage.value = "Bağlanıyor..."

        try {
            EntityTracker.init()
            BlockTracker.clear()

            val r = OxRelay(localPort = localPort)
            r.capture(remoteHost = host, remotePort = port) { session ->
                onSessionCreated(session)
            }

            relay                = r
            _isActive.value      = true
            _connectedHost.value = host
            _connectedPort.value = port
            _statusMessage.value = "Aktif — $host:$port"
            _sessionCount.value  = 0

            OverlayLogger.i(TAG, "Relay aktif: ${account.gamertag} → $host:$port")

        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Relay başlatılamadı: ${e.message}", e)
            _isActive.value      = false
            _statusMessage.value = "Hata: ${e.message}"
            relay                = null
        }
    }

    fun stop() {
        if (!_isActive.value) return
        OverlayLogger.i(TAG, "Relay durduruluyor")
        try {
            ModuleManager.disableAll()
            relay?.stop()
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Relay durdurma hatası: ${e.message}", e)
        } finally {
            relay                = null
            _isActive.value      = false
            _connectedHost.value = ""
            _connectedPort.value = 0
            _statusMessage.value = "Kapalı"
            _sessionCount.value  = 0
            PacketEventBus.setSession(null)
            EntityTracker.reset()
            BlockTracker.clear()
            ConnectionManager.onDisconnected("Relay durduruldu")
        }
    }

    private fun onSessionCreated(session: OxRelaySession) {
        _sessionCount.value++
        OverlayLogger.i(TAG, "Yeni session #${_sessionCount.value}: ${session.clientAddress}")
        _statusMessage.value = "Session #${_sessionCount.value} — ${session.clientAddress}"
        PacketEventBus.setSession(session)
        ConnectionManager.setupSession(session, relay)  // FIX: relay geçiriliyordu null — AutoCodecListener pong güncelleyemiyordu
        installSessionCloseListener(session)
    }

    private fun installSessionCloseListener(session: OxRelaySession) {
        try {
            session.clientSession.peer.channel
                .closeFuture()
                .addListener {
                    onSessionEnded(session, "channel closed")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Session close listener eklenemedi: ${e.message}")
        }
    }

    private fun onSessionEnded(session: OxRelaySession, reason: String) {
        OverlayLogger.i(TAG, "Session kapandı: ${session.clientAddress} — $reason")
        PacketEventBus.setSession(null)
        EntityTracker.reset()
        BlockTracker.clear()
        if (_isActive.value) {
            _statusMessage.value = "Session kapandı — yeniden bağlanmayı bekliyor"
            ConnectionManager.onDisconnected(reason)
        }
    }

    fun onSessionStart(host: String, port: Int) = start()
    fun onSessionStop() = stop()
}
