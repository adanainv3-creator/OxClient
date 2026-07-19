package com.oxclient.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.oxclient.module.BaseModule
import com.oxclient.module.BoolSetting
import com.oxclient.module.EnumSetting
import com.oxclient.module.FloatSetting
import com.oxclient.module.IntSetting
import com.oxclient.module.ModuleManager
import com.oxclient.module.StringSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "ox_configs")

data class ConfigProfile(val name: String, val savedAt: Long)

object Config {

    private val KEY_CONFIGS = stringPreferencesKey("config_profiles")

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun safeCtx(): Context =
        appContext ?: throw IllegalStateException("Config.init() çağrılmamış!")

    // ---- Profil listesi / aktif profil ----

    val profiles: Flow<List<ConfigProfile>>
        get() = try {
            safeCtx().configDataStore.data.map { prefs -> parseProfileList(prefs[KEY_CONFIGS]) }
        } catch (_: Exception) { flowOf(emptyList()) }

    val activeProfile: Flow<String?>
        get() = try {
            safeCtx().configDataStore.data.map { prefs -> readRoot(prefs[KEY_CONFIGS]).optString("active").ifBlank { null } }
        } catch (_: Exception) { flowOf(null) }

    fun getProfilesBlocking(): List<ConfigProfile> = try {
        val raw = runBlocking { safeCtx().configDataStore.data.map { it[KEY_CONFIGS] }.first() }
        parseProfileList(raw)
    } catch (_: Exception) { emptyList() }

    private fun parseProfileList(raw: String?): List<ConfigProfile> {
        val profilesJson = readRoot(raw).optJSONObject("profiles") ?: JSONObject()
        return profilesJson.keys().asSequence().map { name ->
            val entry = profilesJson.optJSONObject(name)
            ConfigProfile(name, entry?.optLong("savedAt", 0L) ?: 0L)
        }.sortedByDescending { it.savedAt }.toList()
    }

    private fun readRoot(raw: String?): JSONObject =
        try { JSONObject(raw ?: "{}") } catch (_: Exception) { JSONObject() }

    // ---- Kaydet / Yükle / Sil ----

    suspend fun save(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false

        return try {
            val state = serializeCurrentState()
            safeCtx().configDataStore.edit { prefs ->
                val root = readRoot(prefs[KEY_CONFIGS])
                val profilesJson = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }

                val entry = JSONObject()
                entry.put("savedAt", System.currentTimeMillis())
                entry.put("state", state)
                profilesJson.put(trimmed, entry)

                root.put("active", trimmed)
                prefs[KEY_CONFIGS] = root.toString()
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun load(name: String): Boolean {
        return try {
            val raw = safeCtx().configDataStore.data.map { it[KEY_CONFIGS] }.first()
            val root = readRoot(raw)
            val profilesJson = root.optJSONObject("profiles") ?: return false
            val entry = profilesJson.optJSONObject(name) ?: return false
            val state = entry.optJSONObject("state") ?: return false

            applyState(state)

            safeCtx().configDataStore.edit { prefs ->
                val r = readRoot(prefs[KEY_CONFIGS])
                r.put("active", name)
                prefs[KEY_CONFIGS] = r.toString()
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun delete(name: String) {
        try {
            safeCtx().configDataStore.edit { prefs ->
                val root = readRoot(prefs[KEY_CONFIGS])
                val profilesJson = root.optJSONObject("profiles") ?: return@edit
                profilesJson.remove(name)
                if (root.optString("active") == name) root.remove("active")
                prefs[KEY_CONFIGS] = root.toString()
            }
        } catch (_: Exception) {}
    }

    suspend fun rename(oldName: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == oldName) return false

        return try {
            var didRename = false
            safeCtx().configDataStore.edit { prefs ->
                val root = readRoot(prefs[KEY_CONFIGS])
                val profilesJson = root.optJSONObject("profiles") ?: return@edit
                val entry = profilesJson.optJSONObject(oldName) ?: return@edit
                if (profilesJson.has(trimmed)) return@edit

                profilesJson.put(trimmed, entry)
                profilesJson.remove(oldName)
                if (root.optString("active") == oldName) root.put("active", trimmed)
                prefs[KEY_CONFIGS] = root.toString()
                didRename = true
            }
            didRename
        } catch (_: Exception) { false }
    }

    // ---- Modül durumunu JSON'a çevir / JSON'dan uygula ----

    private fun serializeCurrentState(): JSONObject {
        val modulesJson = JSONObject()

        ModuleManager.getAll().forEach { module ->
            val moduleJson = JSONObject()
            moduleJson.put("enabled", module.isEnabled)

            val settingsJson = JSONObject()
            module.settings.forEach { setting ->
                val value: Any = when (setting) {
                    is BoolSetting    -> setting.value
                    is IntSetting     -> setting.value
                    is FloatSetting   -> setting.value.toDouble()
                    is StringSetting  -> setting.value
                    is EnumSetting<*> -> setting.value.name
                    else              -> return@forEach
                }
                settingsJson.put(setting.name, value)
            }
            moduleJson.put("settings", settingsJson)
            modulesJson.put(module.name, moduleJson)
        }

        val root = JSONObject()
        root.put("version", 1)
        root.put("modules", modulesJson)
        return root
    }

    private fun applyState(root: JSONObject) {
        val modulesJson = root.optJSONObject("modules") ?: return

        ModuleManager.getAll().forEach { module ->
            val moduleJson = modulesJson.optJSONObject(module.name) ?: return@forEach
            applySettingsTo(module, moduleJson.optJSONObject("settings"))

            val shouldEnable = moduleJson.optBoolean("enabled", module.isEnabled)
            if (shouldEnable != module.isEnabled) {
                if (shouldEnable) ModuleManager.enable(module) else ModuleManager.disable(module)
            }
        }
    }

    private fun applySettingsTo(module: BaseModule, settingsJson: JSONObject?) {
        settingsJson ?: return
        module.settings.forEach { setting ->
            if (!settingsJson.has(setting.name)) return@forEach
            try {
                when (setting) {
                    is BoolSetting    -> setting.value = settingsJson.getBoolean(setting.name)
                    is IntSetting     -> setting.value = settingsJson.getInt(setting.name).coerceIn(setting.min, setting.max)
                    is FloatSetting   -> setting.value = settingsJson.getDouble(setting.name).toFloat().coerceIn(setting.min, setting.max)
                    is StringSetting  -> setting.value = settingsJson.getString(setting.name)
                    is EnumSetting<*> -> setting.setByName(settingsJson.getString(setting.name))
                    else              -> Unit
                }
            } catch (_: Exception) {}
        }
    }

    // ---- Dışa aktarma (indirilebilir dosya) / İçe aktarma (daha önce indirilmiş dosya) ----

    /**
     * Kayıtlı bir profili, kullanıcının cihaza dosya olarak kaydedebileceği
     * (paylaşabileceği / yedekleyebileceği) bir JSON string'ine çevirir.
     */
    suspend fun exportJson(name: String): String? {
        return try {
            val raw = safeCtx().configDataStore.data.map { it[KEY_CONFIGS] }.first()
            val root = readRoot(raw)
            val profilesJson = root.optJSONObject("profiles") ?: return null
            val entry = profilesJson.optJSONObject(name) ?: return null
            val state = entry.optJSONObject("state") ?: return null

            val export = JSONObject()
            export.put("oxclientConfig", true)
            export.put("name", name)
            export.put("exportedAt", System.currentTimeMillis())
            export.put("state", state)
            export.toString(2)
        } catch (_: Exception) { null }
    }

    /**
     * Daha önce exportJson ile indirilmiş bir dosyanın içeriğini (veya doğrudan
     * "modules" içeren ham bir state JSON'unu) yeni bir profil olarak kaydeder.
     * @return kaydedilen profilin adı, başarısızsa null
     */
    suspend fun importJson(json: String, nameOverride: String? = null): String? {
        return try {
            val parsed = JSONObject(json)
            // Hem bizim export formatımızı ("state" içinde) hem de doğrudan
            // ham state JSON'unu (modules en üst seviyede) kabul et.
            val state = if (parsed.has("state")) parsed.getJSONObject("state") else parsed
            if (!state.has("modules")) return null // geçerli bir OxClient config'i değil

            var name = (nameOverride?.trim()?.takeIf { it.isNotEmpty() })
                ?: parsed.optString("name").trim()
            if (name.isBlank()) name = "Imported ${System.currentTimeMillis()}"

            safeCtx().configDataStore.edit { prefs ->
                val root = readRoot(prefs[KEY_CONFIGS])
                val profilesJson = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }

                val entry = JSONObject()
                entry.put("savedAt", System.currentTimeMillis())
                entry.put("state", state)
                profilesJson.put(name, entry)

                root.put("active", name)
                prefs[KEY_CONFIGS] = root.toString()
            }
            name
        } catch (_: Exception) { null }
    }
}
