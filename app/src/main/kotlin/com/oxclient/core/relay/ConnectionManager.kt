package com.oxclient.core.relay

import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.core.relay.listener.*
import com.oxclient.events.PacketEventBus
import com.oxclient.module.ModuleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ConnectionManager — Session lifecycle ve state yönetimi.
 *
 * setupSession() çağrısı MUTLAKA session.init()'den ÖNCE yapılmalıdır.
 * Listener'lar eklenmiş olmalı ki OxRelaySession.connectToServer()
 * sonrasında onSessionStart() doğru listener'lara ulaşsın.
 *
 * Doğru sıra (OxRelay.capture() içinde):
 *   1. OxRelaySession oluştur
 *   2. sessions.add(session)
 *   3. ConnectionManager.setupSession(session, relay)  ← listener'lar eklenir
 *   4. session.init()                                  ← client handler kurulur
 *   (server bağlantısı LoginPacket gelince başlar)
 */
object ConnectionManager {

    private const val TAG = "ConnectionManager"

    enum class State {
        IDLE,
        CONNECTING,
        HANDSHAKING,
        PLAYING,
        DISCONNECTED
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _ping = MutableStateFlow(-1L)
    val ping: StateFlow<Long> = _ping.asStateFlow()

    /**
     * Session'a listener'ları ekler ve EventBus'ı bağlar.
     *
     * Listener ekleme sırası priority'ye göre otomatik sıralanır:
     *   AutoCodecListener   priority = -10  (ilk çalışır)
     *   LoginPacketListener priority =   0
     *   GamingPacketListener priority = 100 (son çalışır)
     *
     * @param relay  AutoCodecListener pong güncellemesi için gerekli.
     *               null ise pong güncellenmez (LAN listesi etkilenmez ama bağlantı çalışır).
     */
    fun setupSession(session: OxRelaySession, relay: OxRelay? = null) {
        OverlayLogger.d(TAG, "Session kuruluyor: ${session.clientAddress}")

        // EventBus bağla
        PacketEventBus.setSession(session)

        // Listener'ları priority sırasına göre ekle
        val listeners = listOf<OxPacketListener>(
            AutoCodecListener(relay),   // priority = -10
            LoginPacketListener(),      // priority =   0
            GamingPacketListener(),     // priority = 100
        ).sortedBy { it.priority }

        listeners.forEach { listener ->
            session.listeners.add(listener)
            OverlayLogger.v(TAG, "  + ${listener::class.simpleName} (priority=${listener.priority})")
        }

        // Modülleri session'a bağla
        try {
            ModuleManager.registerToSession(session)
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "ModuleManager.registerToSession hatası: ${e.message}")
        }

        _state.value = State.CONNECTING
        OverlayLogger.i(TAG, "Session hazır — ${listeners.size} listener aktif")
    }

    fun onHandshaking() {
        _state.value = State.HANDSHAKING
        OverlayLogger.d(TAG, "State → HANDSHAKING")
    }

    fun onGameStarted() {
        _state.value = State.PLAYING
        OverlayLogger.i(TAG, "State → PLAYING ✓")
    }

    fun onDisconnected(reason: String = "") {
        OverlayLogger.i(TAG, "State → DISCONNECTED ${if (reason.isNotBlank()) "($reason)" else ""}")
        PacketEventBus.setSession(null)
        _ping.value  = -1L
        _state.value = State.IDLE
    }

    fun updatePing(ms: Long) {
        _ping.value = ms
    }

    val isPlaying   : Boolean get() = _state.value == State.PLAYING
    val isConnected : Boolean get() = _state.value == State.PLAYING || _state.value == State.HANDSHAKING
    val isIdle      : Boolean get() = _state.value == State.IDLE
}
