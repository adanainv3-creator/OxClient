package com.oxclient.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.serverDataStore by preferencesDataStore("ox_server_config")

/**
 * Kullanıcının seçtiği sunucu bilgilerini DataStore'da saklayan singleton.
 *
 * Varsayılan: 2b2t.pe / 19132
 * Kullanım:
 *   ServerConfig.init(context)
 *   ServerConfig.host.value        → mevcut host
 *   ServerConfig.save("mc.example.com", 19132)
 */
object ServerConfig {

    // ── Varsayılan değerler ───────────────────────────────────────────────────

    const val DEFAULT_HOST = "2b2tpe.org"
    const val DEFAULT_PORT = 19132

    // ── DataStore anahtarları ─────────────────────────────────────────────────

    private val KEY_HOST = stringPreferencesKey("server_host")
    private val KEY_PORT = intPreferencesKey("server_port")

    // ── State ─────────────────────────────────────────────────────────────────

    private val _host = MutableStateFlow(DEFAULT_HOST)
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private var initialized = false

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext

        // Kayıtlı değerleri senkron yükle (uygulama başlangıcında blok gerekli)
        runBlocking {
            val prefs = appContext.serverDataStore.data.first()
            _host.value = prefs[KEY_HOST] ?: DEFAULT_HOST
            _port.value = prefs[KEY_PORT] ?: DEFAULT_PORT
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sunucu bilgilerini günceller ve diske yazar.
     * @param host  Sunucu adresi (boş olamaz)
     * @param port  Port numarası (1–65535)
     */
    fun save(host: String, port: Int) {
        val cleanHost = host.trim()
        val cleanPort = port.coerceIn(1, 65535)
        _host.value = cleanHost
        _port.value = cleanPort
        scope.launch {
            appContext.serverDataStore.edit { prefs ->
                prefs[KEY_HOST] = cleanHost
                prefs[KEY_PORT] = cleanPort
            }
        }
    }

    /** Varsayılanlara sıfırlar. */
    fun reset() = save(DEFAULT_HOST, DEFAULT_PORT)

    /** "host:port" biçiminde döner — UI'da göstermek için. */
    val display: String get() = "${_host.value}:${_port.value}"
}
