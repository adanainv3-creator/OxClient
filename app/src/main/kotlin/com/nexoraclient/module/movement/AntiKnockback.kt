package com.nexoraclient.module.movement

import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.events.PacketEvent
import com.nexoraclient.module.*
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

class AntiKnockback : BaseModule(
    name        = "AntiKnockback",
    category    = ModuleCategory.MOVEMENT,
    description = "Gelen knockback'i tamamen engeller"
) {
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

        val pkt = event.packet
        if (pkt !is SetEntityMotionPacket) return
        if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return

        event.cancel()
    }
}
