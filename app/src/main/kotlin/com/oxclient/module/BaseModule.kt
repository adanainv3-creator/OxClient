package com.oxclient.module

import com.oxclient.events.PacketEventBus

/**
 * BaseModule
 *
 * Tüm modüllerin türetildiği taban sınıf.
 * Modüller PacketEventBus'a abone olarak paketlere müdahale eder.
 */
abstract class BaseModule(
    val name       : String,
    val description: String,
    val category   : Category
) {
    enum class Category { COMBAT, MOVEMENT, VISUAL, MISC }

    @Volatile var enabled: Boolean = false
        private set

    // Her modül kendi listener referanslarını burada tutar (unsubscribe için)
    protected val listeners = mutableListOf<com.oxclient.events.PacketListener>()

    open fun onEnable()  {}
    open fun onDisable() {}
    open fun onTick()    {}       // 20 TPS tick (opsiyonel)

    fun toggle() { if (enabled) disable() else enable() }

    fun enable() {
        if (enabled) return
        enabled = true
        onEnable()
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        listeners.forEach { PacketEventBus.unsubscribe(it) }
        listeners.clear()
        onDisable()
    }

    protected fun subscribe(
        packetId  : Int?                              = null,
        direction : com.oxclient.events.PacketDirection? = null,
        priority  : Int                               = 100,
        block     : com.oxclient.events.PacketListener
    ) {
        listeners.add(block)
        PacketEventBus.subscribe(packetId, direction, priority, block)
    }
}
