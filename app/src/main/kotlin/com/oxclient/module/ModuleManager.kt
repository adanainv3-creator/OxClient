package com.oxclient.module

import android.util.Log
import com.oxclient.module.combat.AutoTotem
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.KillAura
import com.oxclient.module.movement.TPAura
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ModuleManager
 *
 * Tüm modülleri kaydeder ve toggle/sorgulama işlemlerini yönetir.
 * [version] Flow'u her toggle'da güncellenerek Compose UI'nin
 * yeniden çizilmesini tetikler.
 */
object ModuleManager {

    private const val TAG = "ModuleManager"

    private val _modules = mutableListOf<BaseModule>()
    val modules: List<BaseModule> get() = _modules

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private var initialized = false

    // ── Init ──────────────────────────────────────────────────────────────

    fun init() {
        if (initialized) return
        initialized = true

        register(
            KillAura(),
            Criticals(),
            AutoTotem(),
            TPAura()
        )

        Log.d(TAG, "${_modules.size} modül yüklendi")
    }

    private fun register(vararg mods: BaseModule) {
        _modules.addAll(mods)
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun toggle(module: BaseModule) {
        module.setEnabled(!module.isEnabled)
        _version.value++
        Log.d(TAG, "${module.name} → ${module.isEnabled}")
    }

    fun enable(module: BaseModule) {
        if (!module.isEnabled) {
            module.setEnabled(true)
            _version.value++
        }
    }

    fun disable(module: BaseModule) {
        if (module.isEnabled) {
            module.setEnabled(false)
            _version.value++
        }
    }

    fun disableAll() {
        _modules.filter { it.isEnabled }.forEach { it.setEnabled(false) }
        _version.value++
        Log.d(TAG, "Tüm modüller devre dışı bırakıldı")
    }

    fun byName(name: String): BaseModule? =
        _modules.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun byCategory(cat: ModuleCategory): List<BaseModule> =
        _modules.filter { it.category == cat }
}
