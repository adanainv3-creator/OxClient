package com.oxclient.module

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ModuleCategory(val displayName: String) {
    COMBAT("Combat"), MOVEMENT("Movement"), VISUAL("Visual"), MISC("Misc")
}

sealed class ModuleSetting<T>(val name: String) {
    abstract var value: T
}

class BoolSetting(name: String, default: Boolean = false) : ModuleSetting<Boolean>(name) {
    override var value: Boolean = default
}

class FloatSetting(name: String, val min: Float, val max: Float, default: Float) : ModuleSetting<Float>(name) {
    override var value: Float = default
}

class IntSetting(name: String, default: Int, val min: Int, val max: Int) : ModuleSetting<Int>(name) {
    override var value: Int = default
}

class EnumSetting<T : Enum<T>>(name: String, default: T, val values: Array<T>) : ModuleSetting<T>(name) {
    override var value: T = default
}

abstract class BaseModule(
    val name       : String,
    val category   : ModuleCategory,
    val description: String = ""
) : PacketEventBus.PacketListener {

    protected val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val settings: MutableList<ModuleSetting<*>> = mutableListOf()

    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()
    val isEnabled: Boolean get() = _enabledFlow.value

    fun setEnabled(v: Boolean) {
        if (_enabledFlow.value == v) return
        _enabledFlow.value = v
        if (v) onEnable() else onDisable()
    }

    protected open fun onEnable()  { PacketEventBus.register(this) }
    protected open fun onDisable() { PacketEventBus.unregister(this) }

    override fun onPacket(event: PacketEvent) {}

    protected fun bool(name: String, default: Boolean = false) =
        BoolSetting(name, default).also { settings.add(it) }

    protected fun float(name: String, min: Float, max: Float, default: Float) =
        FloatSetting(name, min, max, default).also { settings.add(it) }

    protected fun int(name: String, default: Int, min: Int, max: Int) =
        IntSetting(name, default, min, max).also { settings.add(it) }

    protected inline fun <reified T : Enum<T>> enum(name: String, default: T) =
        EnumSetting(name, default, enumValues<T>()).also { settings.add(it) }
}
