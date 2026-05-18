package com.oxclient.core.relay

import android.util.Log
import com.oxclient.core.relay.listener.*
import com.oxclient.events.PacketEventBus
import com.oxclient.module.ModuleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
     * Session'ı kurar ve listener'ları ekler.
     *
     * GÜNCELLEME: AutoCodecListener artık relay referansını alıyor.
     * Client'ın gerçek protokol versiyonu öğrenilince relay pong'u
     * otomatik güncellenir → MC "Sürüm uyumsuz" göstermez.
     */
    fun setupSession(session: OxRelaySession, relay: OxRelay? = null) {
        Log.d(TAG, "Session kuruluyor: ${session.clientAddress}")

        PacketEventBus.setSession(session)

        val listeners = listOf(
            AutoCodecListener(relay),      // relay → pong dinamik güncelleme
            LoginPacketListener(),
            GamingPacketListener(),
        )

        listeners.sortedBy { it.priority }.forEach { listener ->
            session.listeners.add(listener)
            Log.v(TAG, "Listener eklendi: ${listener::class.simpleName} (priority=${listener.priority})")
        }

        ModuleManager.registerToSession(session)

        _state.value = State.CONNECTING
    }

    fun onGameStarted() {
        _state.value = State.PLAYING
        Log.i(TAG, "Oyun başladı")
    }

    fun onHandshaking() {
        _state.value = State.HANDSHAKING
    }

    fun onDisconnected(reason: String = "") {
        _state.value = State.DISCONNECTED
        _ping.value  = -1L
        Log.i(TAG, "Bağlantı kesildi: $reason")
        PacketEventBus.setSession(null)
        _state.value = State.IDLE
    }

    fun updatePing(ms: Long) {
        _ping.value = ms
    }

    val isPlaying   : Boolean get() = _state.value == State.PLAYING
    val isConnected : Boolean get() = _state.value == State.PLAYING || _state.value == State.HANDSHAKING
}
