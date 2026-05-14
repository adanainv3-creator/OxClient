package com.oxclient.module

import android.util.Log
import com.oxclient.core.relay.OxRelaySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ModuleManager {

    private const val TAG = "ModuleManager"

    private val _modules = mutableListOf<BaseModule>()
    val modules: List<BaseModule> get() = _modules

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private var initialized = false

    fun registerAll(vararg mods: BaseModule) {
        if (initialized) {
            Log.w(TAG, "registerAll() zaten çağrıldı, atlanıyor")
            return
        }
        initialized = true
        _modules.addAll(mods)
        Log.d(TAG, "${_modules.size} modül yüklendi")
    }

    fun register(vararg mods: BaseModule) = registerAll(*mods)

    fun getAll(): List<BaseModule> = _modules

    fun registerToSession(session: OxRelaySession) {
        Log.d(TAG, "registerToSession: ${_modules.count { it.isEnabled }} aktif modül PacketEventBus'ta")
    }

    fun shortcutModules(): List<BaseModule> =
        _modules.filter { m ->
            m.settings.filterIsInstance<BoolSetting>()
                .any { it.name == "Shortcut" && it.value }
        }

    fun toggle(module: BaseModule) {
        module.setEnabled(!module.isEnabled)
        _version.value++
        Log.d(TAG, "${module.name} → ${module.isEnabled}")
    }

    fun enable(module: BaseModule) {
        if (!module.isEnabled) { module.setEnabled(true); _version.value++ }
    }

    fun disable(module: BaseModule) {
        if (module.isEnabled) { module.setEnabled(false); _version.value++ }
    }

    fun disableAll() {
        _modules.filter { it.isEnabled }.forEach { it.setEnabled(false) }
        _version.value++
    }

    fun byName(name: String): BaseModule? =
        _modules.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun byCategory(cat: ModuleCategory): List<BaseModule> =
        _modules.filter { it.category == cat }

    fun enabledCount(): Int = _modules.count { it.isEnabled }

    fun combatModules()   = byCategory(ModuleCategory.COMBAT)
    fun movementModules() = byCategory(ModuleCategory.MOVEMENT)
    fun visualModules()   = byCategory(ModuleCategory.VISUAL)
    fun miscModules()     = byCategory(ModuleCategory.MISC)
}
