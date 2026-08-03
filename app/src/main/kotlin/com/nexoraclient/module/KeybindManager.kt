package com.rubidiumclient.module

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private const val TAG = "KeybindManager"

private val Context.keybindDataStore: DataStore<Preferences> by preferencesDataStore(name = "ox_keybinds")

/**
 * Fiziksel tuş (Bluetooth klavye tuş kodu / bir farenin ekstra düğmesi / ses
 * tuşu) <-> modül eşlemesini tutan merkezi kayıt.
 *
 * Gerçek tuş yakalama işini bu sınıf YAPMIYOR — sadece eşlemeyi tutuyor ve
 * persist ediyor:
 *  - Klavye / gamepad / ekstra fare düğmeleri -> KeybindAccessibilityService
 *    tarafından yakalanıp [dispatch] çağrılıyor.
 *  - Ses tuşları -> OverlayService'in MediaSession tabanlı volume
 *    interceptor'ı tarafından yakalanıp [dispatch] çağrılıyor.
 *
 * Standart sol/sağ mouse tıklaması Android'de KeyEvent değil MotionEvent
 * olarak geldiği için (ve root olmadan başka bir uygulamanın penceresinden
 * sistem genelinde yakalanamadığı için) bu sistemin KAPSAMI DIŞINDA —
 * yalnızca KeyEvent üreten girdiler (klavye tuşları, çoğu farenin
 * ileri/geri/yan tuşları, gamepad düğmeleri, ses tuşları) desteklenir.
 */
object KeybindManager {
    private val KEY_BINDINGS = stringPreferencesKey("bindings")

    /** Ses tuşları gerçek KeyEvent kodu üretmiyor (MediaSession üzerinden yakalanıyor),
     *  bu yüzden gerçek Android KeyEvent kodlarıyla çakışmayacak sanal kodlar kullanıyoruz. */
    const val VOLUME_UP = -1
    const val VOLUME_DOWN = -2

    @Volatile private var appContext: Context? = null

    // keyCode -> modül adı
    private val _bindings = MutableStateFlow<Map<Int, String>>(emptyMap())
    val bindings: StateFlow<Map<Int, String>> = _bindings.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val raw = runBlocking { safeCtx().keybindDataStore.data.map { it[KEY_BINDINGS] }.first() }
            _bindings.value = parse(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Keybind'lar yüklenemedi", e)
        }
    }

    private fun safeCtx(): Context =
        appContext ?: throw IllegalStateException("KeybindManager.init() çağrılmamış!")

    private fun parse(raw: String?): Map<Int, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence()
                .mapNotNull { key -> key.toIntOrNull()?.let { it to json.getString(key) } }
                .toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Keybind JSON parse edilemedi", e)
            emptyMap()
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        try {
            runBlocking {
                ctx.keybindDataStore.edit { prefs ->
                    val json = JSONObject()
                    _bindings.value.forEach { (code, name) -> json.put(code.toString(), name) }
                    prefs[KEY_BINDINGS] = json.toString()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Keybind'lar kaydedilemedi", e)
        }
    }

    /** Bir tuşu bir modüle atar. O modülde önceden başka bir tuş bağlıysa onu çözer. */
    fun assign(keyCode: Int, moduleName: String) {
        val updated = _bindings.value.toMutableMap()
        updated.entries.removeAll { it.value.equals(moduleName, ignoreCase = true) }
        updated[keyCode] = moduleName
        _bindings.value = updated
        persist()
    }

    fun clearForModule(moduleName: String) {
        val updated = _bindings.value.filterValues { !it.equals(moduleName, ignoreCase = true) }
        if (updated.size == _bindings.value.size) return
        _bindings.value = updated
        persist()
    }

    fun keyCodeFor(moduleName: String): Int? =
        _bindings.value.entries.firstOrNull { it.value.equals(moduleName, ignoreCase = true) }?.key

    fun labelFor(moduleName: String): String? =
        keyCodeFor(moduleName)?.let { labelForKeyCode(it) }

    fun labelForKeyCode(keyCode: Int): String = when (keyCode) {
        VOLUME_UP   -> "Vol +"
        VOLUME_DOWN -> "Vol -"
        else -> runCatching {
            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace("_", " ")
        }.getOrDefault("Key $keyCode")
    }

    /**
     * Bir tuşa basıldığında çağrılır. Eşleşen bir modül varsa onu toggle eder
     * ve true döner (çağıran taraf event'i tüketebilir); eşleşme yoksa false döner.
     */
    fun dispatch(keyCode: Int): Boolean {
        val moduleName = _bindings.value[keyCode] ?: return false
        val module = ModuleManager.byName(moduleName) ?: return false
        ModuleManager.toggle(module)
        return true
    }
}
