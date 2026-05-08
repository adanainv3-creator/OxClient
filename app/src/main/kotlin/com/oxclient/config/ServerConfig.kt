package com.oxclient.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ox_server")

/**
 * ServerConfig
 *
 * Kullanıcının seçtiği sunucu adresini ve portunu DataStore'da saklar.
 * Varsayılan değerler: host = "2b2tpe.org", port = 19132.
 *
 * Kullanım:
 *   ServerConfig.init(context)        — uygulama başlangıcında
 *   ServerConfig.host / .port         — anlık değerleri okur (Flow)
 *   ServerConfig.getHostBlocking()    — VPN servisinden eşzamanlı okuma
 *   ServerConfig.save(host, port)     — yeni sunucu kaydet
 */
object ServerConfig {

    const val DEFAULT_HOST       = "2b2tpe.org"
    const val DEFAULT_PORT       = 19132
    const val LOCAL_PROXY_PORT   = 19133

    private val KEY_HOST = stringPreferencesKey("server_host")
    private val KEY_PORT = intPreferencesKey("server_port")

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    /** Sunucu adresini akış olarak gözlemle */
    val host: Flow<String>
        get() = ctx.dataStore.data.map { prefs ->
            prefs[KEY_HOST] ?: DEFAULT_HOST
        }

    /** Sunucu portunu akış olarak gözlemle */
    val port: Flow<Int>
        get() = ctx.dataStore.data.map { prefs ->
            prefs[KEY_PORT] ?: DEFAULT_PORT
        }

    /** Eşzamanlı host okuma — VPN servisinin onCreate içinden kullanılır */
    fun getHostBlocking(): String = runBlocking {
        ctx.dataStore.data.map { it[KEY_HOST] ?: DEFAULT_HOST }.first()
    }

    /** Eşzamanlı port okuma — VPN servisinin onCreate içinden kullanılır */
    fun getPortBlocking(): Int = runBlocking {
        ctx.dataStore.data.map { it[KEY_PORT] ?: DEFAULT_PORT }.first()
    }

    /** Yeni sunucu adresini kaydet */
    suspend fun save(host: String, port: Int) {
        ctx.dataStore.edit { prefs ->
            prefs[KEY_HOST] = host.trim()
            prefs[KEY_PORT] = port
        }
    }

    /** Varsayılana dön */
    suspend fun reset() {
        ctx.dataStore.edit { prefs ->
            prefs[KEY_HOST] = DEFAULT_HOST
            prefs[KEY_PORT] = DEFAULT_PORT
        }
    }
}
