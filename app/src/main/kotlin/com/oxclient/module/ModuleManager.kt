package com.oxclient.module

import android.util.Log
import com.oxclient.module.combat.*
import com.oxclient.module.movement.*
import com.oxclient.module.visual.*
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

    fun init() {
        if (initialized) return
        initialized = true
        register(
            KillAura(), Criticals(), CrystalAura(), AutoTotem(),
            TPAura(), Jetpack(),
            FullBright()
        )
        Log.d(TAG, "${_modules.size} modül yüklendi")
    }

    private fun register(vararg mods: BaseModule) { _modules.addAll(mods) }

    fun shortcutModules(): List<BaseModule> =
        _modules.filter { m -> m.settings.filterIsInstance<BoolSetting>()
            .any { it.name == "Shortcut" && it.value } }

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
}
