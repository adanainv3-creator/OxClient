package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.module.*
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
