package com.oxclient.module

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

abstract class BaseModule(
    val name        : String,
    val description : String,
    val category    : ModuleCategory,
    val defaultKey  : Int = 0
) {
    companion object {
        private const val TAG = "BaseModule"
        const val TICK_MS     = 50L
    }

    private val _enabled = MutableStateFlow(false)
    val enabledFlow : StateFlow<Boolean> = _enabled.asStateFlow()
    val isEnabled   : Boolean get() = _enabled.value

    private val _settings = mutableListOf<ModuleSetting<*>>()
    val settings: List<ModuleSetting<*>> get() = _settings.toList()

    private var scope   : CoroutineScope? = null
    private var tickJob : Job?            = null

    private val listener = object : PacketListener {
        override val priority = listenerPriority
        override fun onPacket(event: PacketEvent) {
            if (!isEnabled) return
            when (event.direction) {
                PacketEvent.DIRECTION_C2S -> onPacketSend(event)
                PacketEvent.DIRECTION_S2C -> onPacketReceive(event)
            }
        }
    }

    open val listenerPriority: Int get() = 100

    internal fun enable() {
        if (_enabled.value) return
        _enabled.value = true
        scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tickJob = scope!!.launch {
            while (isActive) {
                runCatching { onTick() }.onFailure { Log.e(TAG, "$name onTick hata", it) }
                delay(TICK_MS)
            }
        }
        PacketEventBus.register(listener)
        runCatching { onEnable() }.onFailure { Log.e(TAG, "$name onEnable hata", it) }
        Log.i(TAG, "[$name] aktif")
    }

    internal fun disable() {
        if (!_enabled.value) return
        _enabled.value = false
        runCatching { onDisable() }.onFailure { Log.e(TAG, "$name onDisable hata", it) }
        tickJob?.cancel(); tickJob = null
        scope?.cancel();   scope   = null
        PacketEventBus.unregister(listener)
        Log.i(TAG, "[$name] pasif")
    }

    open fun onEnable()  {}
    open fun onDisable() {}
    open suspend fun onTick() {}
    open fun onPacketSend(event: PacketEvent)    {}
    open fun onPacketReceive(event: PacketEvent) {}

    protected fun launchOnModule(block: suspend CoroutineScope.() -> Unit): Job =
        scope?.launch(block = block) ?: error("$name etkin değil")

    protected fun <T : ModuleSetting<*>> reg(s: T): T { _settings.add(s); return s }

    protected fun floatSetting(name: String, min: Float, max: Float, default: Float, step: Float = 0.1f) =
        reg(FloatSetting(name, min, max, default, step))

    protected fun boolSetting(name: String, default: Boolean) =
        reg(BoolSetting(name, default))

    protected fun intSetting(name: String, min: Int, max: Int, default: Int) =
        reg(IntSetting(name, min, max, default))

    protected fun <E : Enum<E>> enumSetting(name: String, default: E, values: Array<E>) =
        reg(EnumSetting(name, default, values))
}

// ── Kategori ──────────────────────────────────────────────────────────────────

enum class ModuleCategory(val displayName: String) {
    COMBAT("Combat"), MOVEMENT("Movement"), VISUAL("Visual"), MISC("Misc")
}

// ── Setting tipleri ───────────────────────────────────────────────────────────

sealed class ModuleSetting<T>(val name: String, default: T) {
    private val _v = MutableStateFlow(default)
    val valueFlow: StateFlow<T> = _v.asStateFlow()
    var value: T get() = _v.value; set(v) { _v.value = v }
}

class FloatSetting(name: String, val min: Float, val max: Float, default: Float, val step: Float) : ModuleSetting<Float>(name, default)
class BoolSetting (name: String, default: Boolean)                                                 : ModuleSetting<Boolean>(name, default)
class IntSetting  (name: String, val min: Int,   val max: Int,   default: Int)                     : ModuleSetting<Int>(name, default)
class EnumSetting<E : Enum<E>>(name: String, default: E, val values: Array<E>)                    : ModuleSetting<E>(name, default)
