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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ox_server")

object ServerConfig {

    const val DEFAULT_HOST       = "2b2tpe.org"
    const val DEFAULT_PORT       = 19132
    const val LOCAL_PROXY_PORT   = 19132  // 19133 → 19132: OxVpnService ile senkron

    private val KEY_HOST = stringPreferencesKey("server_host")
    private val KEY_PORT = intPreferencesKey("server_port")

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun safeCtx(): Context = appContext ?: throw IllegalStateException("ServerConfig.init() çağrılmamış!")

    val host: Flow<String>
        get() = try {
            safeCtx().dataStore.data.map { it[KEY_HOST] ?: DEFAULT_HOST }
        } catch (e: Exception) {
            flowOf(DEFAULT_HOST)
        }

    val port: Flow<Int>
        get() = try {
            safeCtx().dataStore.data.map { it[KEY_PORT] ?: DEFAULT_PORT }
        } catch (e: Exception) {
            flowOf(DEFAULT_PORT)
        }

    fun getHostBlocking(): String = try {
        runBlocking { safeCtx().dataStore.data.map { it[KEY_HOST] ?: DEFAULT_HOST }.first() }
    } catch (e: Exception) {
        DEFAULT_HOST
    }

    fun getPortBlocking(): Int = try {
        runBlocking { safeCtx().dataStore.data.map { it[KEY_PORT] ?: DEFAULT_PORT }.first() }
    } catch (e: Exception) {
        DEFAULT_PORT
    }

    suspend fun save(host: String, port: Int) {
        try {
            safeCtx().dataStore.edit { prefs ->
                prefs[KEY_HOST] = host.trim()
                prefs[KEY_PORT] = port
            }
        } catch (_: Exception) {}
    }

    suspend fun reset() {
        try {
            safeCtx().dataStore.edit { prefs ->
                prefs[KEY_HOST] = DEFAULT_HOST
                prefs[KEY_PORT] = DEFAULT_PORT
            }
        } catch (_: Exception) {}
    }
}
