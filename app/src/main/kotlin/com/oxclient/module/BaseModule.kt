package com.oxclient.module

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ── Kategoriler ───────────────────────────────────────────────────────────────

enum class ModuleCategory(val displayName: String) {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    VISUAL("Visual"),
    MISC("Misc")
}

// ── Ayar sınıfları ────────────────────────────────────────────────────────────

sealed class ModuleSetting<T>(val name: String) {
    abstract var value: T
}

class FloatSetting(name: String, default: Float, val min: Float, val max: Float) : ModuleSetting<Float>(name) {
    override var value: Float = default
}

class IntSetting(name: String, default: Int, val min: Int, val max: Int) : ModuleSetting<Int>(name) {
    override var value: Int = default
}

class BoolSetting(name: String, default: Boolean) : ModuleSetting<Boolean>(name) {
    override var value: Boolean = default
}

class EnumSetting<T : Enum<T>>(name: String, default: T, val values: List<T>) : ModuleSetting<T>(name) {
    override var value: T = default
}

// ── Temel modül sınıfı ────────────────────────────────────────────────────────

abstract class BaseModule(
    val name        : String,
    val category    : ModuleCategory,
    val description : String = ""
) {
    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    var isEnabled: Boolean
        get()      = _enabledFlow.value
        private set(v) { _enabledFlow.value = v }

    val settings: List<ModuleSetting<*>> by lazy { registerSettings() }

    /** Alt sınıflar ayarlarını burada tanımlar */
    protected open fun registerSettings(): List<ModuleSetting<*>> = emptyList()

    /** Modül etkinleştiğinde çağrılır */
    open fun onEnable()  {}

    /** Modül devre dışı bırakıldığında çağrılır */
    open fun onDisable() {}

    /** PacketEventBus üzerinden gelen paket olaylarını işler */
    open fun onPacket(event: com.oxclient.events.PacketEvent) {}

    internal fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        if (enabled) onEnable() else onDisable()
    }
}
