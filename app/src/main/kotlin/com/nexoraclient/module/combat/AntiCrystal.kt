package com.rubidiumclient.module.combat

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class AntiCrystal : BaseModule(
    name        = "AntiCrystal",
    category    = ModuleCategory.COMBAT,
    description = "Reports a lowered position to the server to reduce end crystal explosion damage"
) {
    private val yLevel = float("Y Level", 0.4f, 0.1f, 1.61f)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val packet = event.packet
        if (packet !is PlayerAuthInputPacket) return

        packet.position = packet.position.add(0f, -yLevel.value, 0f)
        event.cancelAndReplace(packet)
    }
}
