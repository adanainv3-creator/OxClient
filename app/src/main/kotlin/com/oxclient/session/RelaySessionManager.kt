package com.oxclient.session

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RelaySessionManager
 *
 * Relay oturumunun durumunu izler.
 * RelayService ve GamingPacketHandler tarafından güncellenir.
 */
object RelaySessionManager {

    private const val TAG = "RelaySessionManager"

    private val _isRelayActive = MutableStateFlow(false)
    val isRelayActive: StateFlow<Boolean> = _isRelayActive.asStateFlow()

    private val _isGameStarted = MutableStateFlow(false)
    val isGameStarted: StateFlow<Boolean> = _isGameStarted.asStateFlow()

    @Volatile var targetHost: String = ""
        private set

    @Volatile var targetPort: Int = 0
        private set

    @Volatile var playerDimension: Int = 0
        private set

    @Volatile var playerGameMode: Int = 0
        private set

    // ── Relay lifecycle ───────────────────────────────────────────────────

    fun onRelayStarted(host: String, port: Int) {
        targetHost = host
        targetPort = port
        _isRelayActive.value = true
        Log.i(TAG, "Relay başladı → $host:$port")
    }

    fun onRelayStoped() {
        _isRelayActive.value  = false
        _isGameStarted.value  = false
        targetHost = ""
        targetPort = 0
        Log.i(TAG, "Relay durdu")
    }

    // ── Oyun lifecycle ────────────────────────────────────────────────────

    fun onGameStarted(playerName: String, dimension: Int, gameMode: Int) {
        playerDimension  = dimension
        playerGameMode   = gameMode
        _isGameStarted.value = true
        Log.i(TAG, "Oyun başladı — boyut=$dimension mod=$gameMode")
    }

    fun onSessionStop() {
        _isGameStarted.value = false
        Log.i(TAG, "Oyun oturumu sona erdi")
    }
}
