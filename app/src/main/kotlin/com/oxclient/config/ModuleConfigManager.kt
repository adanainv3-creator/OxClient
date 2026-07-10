package com.oxclient.config

import android.content.Context
import com.oxclient.module.*
import org.json.JSONObject
import java.io.File

object ModuleConfigManager {

    private lateinit var context: Context
    private val configDir: File by lazy { File(context.filesDir, "configs") }

    fun init(ctx: Context) {
        context = ctx.applicationContext
        if (!configDir.exists()) configDir.mkdirs()
    }

    /**
     * Geçerli tüm modüllerin ayarlarını JSON olarak döndürür.
     */
    fun getCurrentConfig(): JSONObject {
        val root = JSONObject()
        for (module in ModuleManager.modules) {
            val moduleObj = JSONObject()
            for (setting in module.settings) {
                val value = setting.value
                when (setting) {
                    is BoolSetting     -> moduleObj.put(setting.name, value)
                    is FloatSetting    -> moduleObj.put(setting.name, value)
                    is IntSetting      -> moduleObj.put(setting.name, value)
                    is StringSetting   -> moduleObj.put(setting.name, value)
                    is EnumSetting<*>  -> moduleObj.put(setting.name, (value as Enum<*>).name)
                    else               -> { /* diğer tipler için gerekirse ekle */ }
                }
            }
            root.put(module.name, moduleObj)
        }
        return root
    }

    /**
     * Verilen JSON'u mevcut modüllere uygular.
     */
    fun applyConfig(json: JSONObject) {
        for (module in ModuleManager.modules) {
            val moduleObj = json.optJSONObject(module.name) ?: continue
            for (setting in module.settings) {
                val key = setting.name
                if (!moduleObj.has(key)) continue
                try {
                    when (setting) {
                        is BoolSetting -> setting.value = moduleObj.getBoolean(key)
                        is FloatSetting -> setting.value = moduleObj.getDouble(key).toFloat()
                        is IntSetting -> setting.value = moduleObj.getInt(key)
                        is StringSetting -> setting.value = moduleObj.getString(key)
                        is EnumSetting<*> -> {
                            val enumName = moduleObj.getString(key)
                            val enumClass = setting.value?.javaClass
                            if (enumClass == null) continue
                            @Suppress("UNCHECKED_CAST")
                            val enumValue = enumClass.enumConstants
                                ?.find { (it as Enum<*>).name == enumName } as Enum<*>?
                            if (enumValue != null) {
                                @Suppress("UNCHECKED_CAST")
                                (setting as EnumSetting<Enum<*>>).value = enumValue
                            }
                        }
                        else -> { /* desteklenmeyen tipler */ }
                    }
                } catch (_: Exception) {
                    // değer uygun değilse atla
                }
            }
        }
    }

    /**
     * Mevcut ayarları [name] ismiyle kaydeder.
     */
    fun saveProfile(name: String) {
        val json = getCurrentConfig()
        val file = File(configDir, "$name.json")
        file.writeText(json.toString(2))
    }

    /**
     * [name] isimli profili yükler ve uygular.
     */
    fun loadProfile(name: String) {
        val file = File(configDir, "$name.json")
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        applyConfig(json)
    }

    /**
     * [name] isimli profili siler.
     */
    fun deleteProfile(name: String) {
        File(configDir, "$name.json").delete()
    }

    /**
     * Kayıtlı tüm profil isimlerini listeler.
     */
    fun listProfiles(): List<String> {
        return configDir.listFiles { _, name -> name.endsWith(".json") }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /**
     * Bir profil isminin geçerli olup olmadığını kontrol eder (dosya adına uygun).
     */
    fun isValidProfileName(name: String): Boolean =
        name.isNotBlank() && name.matches(Regex("^[a-zA-Z0-9_\\- ]+$"))
}