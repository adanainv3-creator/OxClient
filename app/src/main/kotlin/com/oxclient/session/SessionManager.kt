package com.oxclient.session

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {

    private const val TAG = "SessionManager"

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // ✅ FIX: host ve port artık StateFlow — Compose overlay doğru güncellenir
    private val _connectedHost = MutableStateFlow("")
    val connectedHostFlow: StateFlow<String> = _connectedHost.asStateFlow()

    private val _connectedPort = MutableStateFlow(0)
    val connectedPortFlow: StateFlow<Int> = _connectedPort.asStateFlow()

    // Geriye dönük uyumluluk için @Volatile getter'lar korundu
    val connectedHost: String get() = _connectedHost.value
    val connectedPort: Int    get() = _connectedPort.value

    fun onSessionStart(host: String, port: Int) {
        _isActive.value      = true
        _connectedHost.value = host
        _connectedPort.value = port
        Log.i(TAG, "Relay başladı → $host:$port")
    }

    fun onSessionStop() {
        _isActive.value      = false
        _connectedHost.value = ""
        _connectedPort.value = 0
        Log.i(TAG, "Relay sona erdi")
    }
}
