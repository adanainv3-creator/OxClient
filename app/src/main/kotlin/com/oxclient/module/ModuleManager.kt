package com.oxclient.module

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.oxclient.module.combat.AutoTotem
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.KillAura
import com.oxclient.module.movement.TPAura
import com.oxclient.ui.overlay.OverlayNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val Context.moduleDataStore by preferencesDataStore("ox_modules")

/**
 * ModuleManager — central registry for all OxClient modules.
 * Handles registration, enable/disable, DataStore persistence,
 * and reactive state for Compose UI.
 */
object ModuleManager {
    private const val TAG = "ModuleManager"

    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = mutableListOf<BaseModule>()
    private var ctx: Context? = null
    private var initialised = false

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialised) return
        ctx = context.applicationContext
        initialised = true
        registerAll()
        restoreState()
        Log.i(TAG, "ModuleManager init — ${registry.size} modules")
    }

    // ── Registration ──────────────────────────────────────────────────────

    private fun registerAll() {
        // Combat
        register(AutoTotem())
        register(KillAura())
        register(Criticals())

        // Movement
        register(TPAura())
        // register(Sprint())
        // register(Speed())
        // register(NoFall())

        // Visual (placeholders)
        // register(FullBright())
        // register(ESP())
        // register(ChestESP())

        // Misc (placeholders)
        // register(AutoRespawn())
        // register(AutoEat())
    }

    private fun register(m: BaseModule) {
        registry.add(m)
        Log.d(TAG, "Registered: ${m.name} [${m.category.displayName}]")
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private fun restoreState() {
        val c = ctx ?: return
        scope.launch {
            c.moduleDataStore.data.collect { prefs ->
                registry.forEach { m ->
                    val enabled = prefs[booleanPreferencesKey(m.name)] ?: false
                    if (enabled && !m.isEnabled)       m.enable()
                    else if (!enabled && m.isEnabled)  m.disable()
                }
            }
        }
    }

    private fun persist(m: BaseModule) {
        val c = ctx ?: return
        scope.launch {
            c.moduleDataStore.edit { prefs ->
                prefs[booleanPreferencesKey(m.name)] = m.isEnabled
            }
        }
    }

    // ── Enable / Disable / Toggle ─────────────────────────────────────────

    fun enable(m: BaseModule) {
        if (m.isEnabled) return
        m.enable(); persist(m)
        OverlayNotifier.showModuleToast(m.name, enabled = true)
        _version.value++
    }

    fun disable(m: BaseModule) {
        if (!m.isEnabled) return
        m.disable(); persist(m)
        OverlayNotifier.showModuleToast(m.name, enabled = false)
        _version.value++
    }

    fun toggle(m: BaseModule) { if (m.isEnabled) disable(m) else enable(m) }

    fun disableAll() { registry.filter { it.isEnabled }.forEach { disable(it) } }

    // ── Queries ───────────────────────────────────────────────────────────

    val modules: List<BaseModule> get() = registry.toList()
    fun byCategory(cat: ModuleCategory) = registry.filter { it.category == cat }
    fun byName(name: String) = registry.firstOrNull { it.name.equals(name, true) }
    val enabled: List<BaseModule> get() = registry.filter { it.isEnabled }
}
