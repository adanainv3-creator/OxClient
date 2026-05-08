package com.oxclient.session

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {

    private const val TAG = "SessionManager"

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    @Volatile
    var connectedHost: String = ""
        private set

    @Volatile
    var connectedPort: Int = 0
        private set

    fun onSessionStart(host: String, port: Int) {
        _isActive.value = true
        connectedHost = host
        connectedPort = port
        Log.i(TAG, "Session başladı → $host:$port")
    }

    fun onSessionStop() {
        _isActive.value = false
        connectedHost = ""
        connectedPort = 0
        Log.i(TAG, "Session sona erdi")
    }
}