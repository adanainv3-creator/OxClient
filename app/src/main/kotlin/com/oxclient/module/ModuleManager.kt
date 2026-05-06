package com.oxclient.module

import android.util.Log
import com.oxclient.module.combat.AutoTotem
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.KillAura
import com.oxclient.module.movement.NoFall
import com.oxclient.module.movement.Sprint
import com.oxclient.module.movement.TPAura
import com.oxclient.module.visual.ESP
import com.oxclient.module.visual.FullBright
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ModuleManager
 *
 * Tüm modülleri kaydeder ve toggle/sorgulama işlemlerini yönetir.
 * [version] Flow'u her toggle'da güncellenerek Compose UI'nin
 * yeniden çizilmesini tetikler.
 *
 * Yeni modül eklemek: sadece sınıfı oluştur ve register() içine ekle.
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
            // Combat
            AutoTotem(),
            KillAura(),
            Criticals(),
            // Movement
            TPAura(),
            Sprint(),
            NoFall(),
            // Visual
            ESP(),
            FullBright()
        )

        Log.d(TAG, "${_modules.size} modül yüklendi: ${_modules.map { it.name }}")
    }

    private fun register(vararg mods: BaseModule) {
        _modules.addAll(mods)
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun toggle(module: BaseModule) {
        module.setEnabled(!module.isEnabled)
        _version.value++
        Log.d(TAG, "${module.name} → ${if (module.isEnabled) "ON" else "OFF"}")
        com.oxclient.ui.overlay.OverlayNotifier.showModuleToast(module.name, module.isEnabled)
    }

    fun enable(module: BaseModule) {
        if (module.isEnabled) return
        module.setEnabled(true)
        _version.value++
        com.oxclient.ui.overlay.OverlayNotifier.showModuleToast(module.name, true)
    }

    fun disable(module: BaseModule) {
        if (!module.isEnabled) return
        module.setEnabled(false)
        _version.value++
        com.oxclient.ui.overlay.OverlayNotifier.showModuleToast(module.name, false)
    }

    fun disableAll() {
        _modules.filter { it.isEnabled }.forEach { it.setEnabled(false) }
        _version.value++
        Log.d(TAG, "Tüm modüller devre dışı")
    }

    fun byName(name: String): BaseModule? =
        _modules.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun byCategory(cat: ModuleCategory): List<BaseModule> =
        _modules.filter { it.category == cat }
}
