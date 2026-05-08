package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.module.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Ölümsüzlük totemini otomatik takkar"
) {

    private val healthThreshold = FloatSetting("HealthThreshold", 10f, 1f, 20f)
    private val delay           = IntSetting("Delay", 50, 0, 500)
    private val offhand         = BoolSetting("Offhand", true)
    private val shortcut        = BoolSetting("Shortcut", false)

    override fun registerSettings() = listOf(healthThreshold, delay, offhand, shortcut)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Relay bağlandığında:
        // - oyuncunun canı healthThreshold altına düşünce
        //   offhand slotuna totem paketi gönder
    }

    override fun onEnable()  {}
    override fun onDisable() {}
}
