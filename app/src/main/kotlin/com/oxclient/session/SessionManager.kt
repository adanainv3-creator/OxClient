package com.oxclient.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

object SessionManager {

    private val _isActive = MutableStateFlow(false)
    val isActiveFlow: StateFlow<Boolean> = _isActive.asStateFlow()
    val isActive: Boolean get() = _isActive.value

    fun onSessionStart() {
        _isActive.value = true
        Timber.i("[Session] Başladı → ${ServerConfig.host.value}:${ServerConfig.port.value}")
    }

    fun onSessionStop() {
        _isActive.value = false
        Timber.i("[Session] Sona erdi")
    }
}
