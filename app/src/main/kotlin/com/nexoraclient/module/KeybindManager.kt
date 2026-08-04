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
 * Central registry mapping a physical key (Bluetooth keyboard key code / a
 * mouse's extra button / a volume key) to a module.
 *
 * This class does NOT do the actual key capturing — it only holds and
 * persists the mapping:
 *  - Keyboard / gamepad / extra mouse buttons -> captured by
 *    KeybindAccessibilityService, which calls [dispatch].
 *  - Volume keys -> captured by OverlayService's MediaSession-based volume
 *    interceptor, which calls [dispatch].
 *
 * Standard left/right mouse clicks arrive on Android as MotionEvents rather
 * than KeyEvents (and can't be captured system-wide from another app's
 * window without root), so they're OUT OF SCOPE for this system — only
 * inputs that produce a KeyEvent (keyboard keys, most mice's forward/back/
 * side buttons, gamepad buttons, volume keys) are supported.
 */
object KeybindManager {
    private val KEY_BINDINGS = stringPreferencesKey("bindings")

    /** Volume keys don't produce a real KeyEvent code (they're captured via MediaSession),
     *  so we use virtual codes that won't collide with real Android KeyEvent codes. */
    const val VOLUME_UP = -1
    const val VOLUME_DOWN = -2

    @Volatile private var appContext: Context? = null

    // keyCode -> module name
    private val _bindings = MutableStateFlow<Map<Int, String>>(emptyMap())
    val bindings: StateFlow<Map<Int, String>> = _bindings.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val raw = runBlocking { safeCtx().keybindDataStore.data.map { it[KEY_BINDINGS] }.first() }
            _bindings.value = parse(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load keybinds", e)
        }
    }

    private fun safeCtx(): Context =
        appContext ?: throw IllegalStateException("KeybindManager.init() was not called!")

    private fun parse(raw: String?): Map<Int, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence()
                .mapNotNull { key -> key.toIntOrNull()?.let { it to json.getString(key) } }
                .toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse keybind JSON", e)
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
            Log.e(TAG, "Failed to save keybinds", e)
        }
    }

    /** Binds a key to a module. If that module already had a different key bound, it's cleared first. */
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
     * Called when a key is pressed. If a module is bound to it (and the
     * module isn't locked behind Rubidium Private), toggles it and returns
     * true (the caller may consume the event); returns false if there's no
     * binding or the module is locked.
     */
    fun dispatch(keyCode: Int): Boolean {
        val moduleName = _bindings.value[keyCode] ?: return false
        val module = ModuleManager.byName(moduleName) ?: return false
        if (com.rubidiumclient.config.PrivateAccessManager.isModuleLocked(module.name)) return false
        ModuleManager.toggle(module)
        return true
    }
}
