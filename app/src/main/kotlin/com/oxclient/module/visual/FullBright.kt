package com.oxclient.module.visual

import com.oxclient.events.PacketEvent
import com.oxclient.module.*

class FullBright : BaseModule(
    name        = "FullBright",
    category    = ModuleCategory.VISUAL,
    description = "Geceyi gündüz gibi görünür yapar"
) {

    enum class FbMode { Gamma, NightVision, Lighting }

    private val mode     = EnumSetting("Mode", FbMode.Gamma, FbMode.entries)
    private val strength = FloatSetting("Strength", 1000f, 100f, 10000f)
    private val shortcut = BoolSetting("Shortcut", false)

    override fun registerSettings() = listOf(mode, strength, shortcut)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Relay bağlandığında:
        // NightVision modu → SetEntityData paketi ile efekt ekle
        // Gamma modu → client-side (overlay renderer)
    }

    override fun onEnable()  {}
    override fun onDisable() {}
}
