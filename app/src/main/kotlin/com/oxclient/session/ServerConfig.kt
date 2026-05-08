package com.oxclient.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "servers")

/**
 * ServerConfig — Tek bir sunucu bağlantı yapılandırması
 */
data class ServerConfig(
    val id   : String = java.util.UUID.randomUUID().toString(),
    val name : String = "Sunucu",
    val host : String = "geo.hivebedrock.network",
    val port : Int    = 19132,
    val icon : String = "🎮"          // emoji ikon
) {
    companion object {
        val PRESETS = listOf(
            ServerConfig("hive",      "Hive",       "geo.hivebedrock.network", 19132, "🐝"),
            ServerConfig("cubecraft", "CubeCraft",  "mco.cubecraft.net",       19132, "🎲"),
            ServerConfig("lifeboat",  "Lifeboat",   "mco.lbsg.net",            19132, "⛵"),
            ServerConfig("mineplex",  "Mineplex",   "mco.mineplex.com",        19132, "🏛️"),
            ServerConfig("nether",    "NetherGames","play.nethergames.org",    19132, "🔥"),
            ServerConfig("galaxite",  "Galaxite",   "play.galaxite.net",       19132, "🌌"),
        )
    }
}

/**
 * SessionManager — DataStore üzerinden sunucu listesi kalıcı depolama
 */
class SessionManager(private val context: Context) {

    private val gson = Gson()
    private val KEY_SERVERS = stringPreferencesKey("server_list")

    val serversFlow: Flow<List<ServerConfig>> = context.serverDataStore.data.map { prefs ->
        val json = prefs[KEY_SERVERS] ?: return@map ServerConfig.PRESETS
        try {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            gson.fromJson<List<ServerConfig>>(json, type) ?: ServerConfig.PRESETS
        } catch (_: Exception) { ServerConfig.PRESETS }
    }

    suspend fun saveServer(server: ServerConfig) {
        context.serverDataStore.edit { prefs ->
            val current = getCurrent(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == server.id }
            if (idx >= 0) current[idx] = server else current.add(server)
            prefs[KEY_SERVERS] = gson.toJson(current)
        }
    }

    suspend fun deleteServer(id: String) {
        context.serverDataStore.edit { prefs ->
            val current = getCurrent(prefs).filter { it.id != id }
            prefs[KEY_SERVERS] = gson.toJson(current)
        }
    }

    private fun getCurrent(prefs: Preferences): List<ServerConfig> {
        val json = prefs[KEY_SERVERS] ?: return ServerConfig.PRESETS
        return try {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            gson.fromJson<List<ServerConfig>>(json, type) ?: ServerConfig.PRESETS
        } catch (_: Exception) { ServerConfig.PRESETS }
    }
}
