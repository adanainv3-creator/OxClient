package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.module.*

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "End kristallerini otomatik yerleştirir ve patlatır"
) {

    private val range           = FloatSetting("Range",           5f,   1f, 10f)
    private val suicide         = BoolSetting("Suicide",          false)
    private val place           = BoolSetting("Place",            true)
    private val delay           = IntSetting("Delay",             400,  0, 2000)
    private val removeParticles = BoolSetting("RemoveParticles",  true)
    private val shortcut        = BoolSetting("Shortcut",         false)

    override fun registerSettings() = listOf(
        range, suicide, place, delay, removeParticles, shortcut
    )

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Relay bağlandığında:
        // 1) place=true → yakına kristal yerleştir paketi
        // 2) delay ms sonra kristali patlat paketi
        // 3) suicide=false → kendine zarar verecekse atla
        // 4) removeParticles → patlama partikül paketlerini filtrele
    }

    override fun onEnable()  {}
    override fun onDisable() {}
}
