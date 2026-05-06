package com.oxclient.relay.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oxclient.auth.AccountManager
import com.oxclient.config.ServerConfig
import com.oxclient.session.RelaySessionManager
import com.oxclient.ui.overlay.OverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RelayOrchestrator
 *
 * Relay başlatma/durdurma orkestratörü.
 * DashboardActivity tarafından kullanılır.
 *
 * Sorumluluklar:
 * - [RelayService] lifecycle yönetimi
 * - [OverlayService] lifecycle yönetimi
 * - AccountManager ile auth kontrolü
 * - UI thread güncelleme
 * - Detected protocol/version state
 */
object RelayOrchestrator {

    private const val TAG = "RelayOrchestrator"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isRelayRunning = MutableStateFlow(false)
    val isRelayRunning: StateFlow<Boolean> = _isRelayRunning.asStateFlow()

    private val _detectedProtocolVersion = MutableStateFlow(0)
    val detectedProtocolVersion: StateFlow<Int> = _detectedProtocolVersion.asStateFlow()

    private val _detectedMinecraftVersion = MutableStateFlow("")
    val detectedMinecraftVersion: StateFlow<String> = _detectedMinecraftVersion.asStateFlow()

    private val _statusMessage = MutableStateFlow("Bağlı değil")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────

    fun startRelay(context: Context, targetPackage: String = "com.mojang.minecraftpe") {
        if (_isRelayRunning.value) {
            Log.w(TAG, "Relay zaten çalışıyor")
            return
        }

        val account = AccountManager.selectedAccount
        if (account == null) {
            Log.w(TAG, "Hesap yok — relay başlatılamaz")
            updateStatus("Önce Microsoft hesabı ekleyin")
            return
        }

        val host = ServerConfig.getHostBlocking()
        val port = ServerConfig.getPortBlocking()

        Log.i(TAG, "Relay başlatılıyor: $host:$port — Hesap: ${account.gamertag}")
        updateStatus("Relay başlatılıyor → $host:$port")

        // Overlay'i başlat
        OverlayService.start(context)

        // Relay servisini başlat
        Thread({
            try {
                RelayService.start(context, host, port)
                mainHandler.post {
                    _isRelayRunning.value = true
                    updateStatus("Relay aktif → $host:$port")
                    Log.i(TAG, "Relay başarıyla başlatıldı")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay başlatma hatası", e)
                mainHandler.post {
                    updateStatus("Relay hatası: ${e.message}")
                }
            }
        }, "OxRelayStartThread").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stopRelay(context: Context) {
        if (!_isRelayRunning.value) return

        Log.i(TAG, "Relay durduruluyor")
        RelayService.stop(context)
        OverlayService.stop(context)

        mainHandler.post {
            _isRelayRunning.value = false
            _detectedProtocolVersion.value = 0
            _detectedMinecraftVersion.value = ""
            updateStatus("Bağlı değil")
        }
    }

    fun toggleRelay(context: Context) {
        if (_isRelayRunning.value) stopRelay(context)
        else startRelay(context)
    }

    fun onProtocolDetected(version: Int, mcVersion: String) {
        mainHandler.post {
            _detectedProtocolVersion.value = version
            _detectedMinecraftVersion.value = mcVersion
            Log.d(TAG, "Protocol algılandı: $version ($mcVersion)")
        }
    }

    private fun updateStatus(msg: String) {
        _statusMessage.value = msg
    }
}
