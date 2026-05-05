package com.oxclient.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Aktif bağlantı oturumunu yönetir.
 *
 * VPN/proxy katmanı kaldırıldığından bu sınıf artık yalnızca
 * bağlantı durumunu (aktif/değil) ve hedef sunucu bilgisini tutar.
 * Gerçek inject katmanı entegre edildiğinde buraya hook noktaları eklenir.
 */
object SessionManager {

    private val _isActive = MutableStateFlow(false)
    val isActiveFlow: StateFlow<Boolean> = _isActive.asStateFlow()

    val isActive: Boolean get() = _isActive.value

    // Bağlantı kurulduğunda çağrılır
    fun onSessionStart() {
        _isActive.value = true
        Timber.i("Session başladı → ${ServerConfig.host.value}:${ServerConfig.port.value}")
    }

    // Bağlantı kesildiğinde çağrılır
    fun onSessionStop() {
        _isActive.value = false
        Timber.i("Session sona erdi")
    }
}
