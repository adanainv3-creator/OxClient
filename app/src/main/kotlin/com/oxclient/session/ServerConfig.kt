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

object ServerConfig {

    const val DEFAULT_HOST = "2b2tpe.org"
    const val DEFAULT_PORT = 19132

    private val KEY_HOST = stringPreferencesKey("server_host")
    private val KEY_PORT = intPreferencesKey("server_port")

    private val _host = MutableStateFlow(DEFAULT_HOST)
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized  = true
        appContext   = context.applicationContext
        runBlocking {
            val prefs    = appContext.serverDataStore.data.first()
            _host.value  = prefs[KEY_HOST] ?: DEFAULT_HOST
            _port.value  = prefs[KEY_PORT] ?: DEFAULT_PORT
        }
    }

    fun save(host: String, port: Int) {
        val cleanHost = host.trim()
        val cleanPort = port.coerceIn(1, 65535)
        _host.value   = cleanHost
        _port.value   = cleanPort
        scope.launch {
            appContext.serverDataStore.edit { prefs ->
                prefs[KEY_HOST] = cleanHost
                prefs[KEY_PORT] = cleanPort
            }
        }
    }

    fun reset() = save(DEFAULT_HOST, DEFAULT_PORT)

    val display: String get() = "${_host.value}:${_port.value}"
}
