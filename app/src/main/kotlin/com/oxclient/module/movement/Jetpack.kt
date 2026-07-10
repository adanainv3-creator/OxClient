package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.sin

class Jetpack : BaseModule(
    name        = "Jetpack",
    category    = ModuleCategory.MOVEMENT,
    description = "Zıplama tuşuna basılı tutulduğunda karakterin baktığı yöne doğru motion paketiyle itiş uygular"
) {
    private val thrustSpeed = float("Thrust Speed", 1.2f, 0.1f, 3.0f)
    private val requireJump = bool ("Require Jump", true)
    private val shortcut    = bool ("Shortcut",     false)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val jumping = pkt.inputData.contains(PlayerAuthInputData.JUMPING) ||
                      pkt.inputData.contains(PlayerAuthInputData.WANT_UP)

        if (requireJump.value && !jumping) return

        val pitch = Math.toRadians(pkt.rotation.x.toDouble()).toFloat()
        val yaw   = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()

        val cosPitch = cos(pitch)

        val dirX = -sin(yaw) * cosPitch
        val dirY = -sin(pitch)
        val dirZ = cos(yaw) * cosPitch

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(
                dirX * thrustSpeed.value,
                dirY * thrustSpeed.value,
                dirZ * thrustSpeed.value
            )
        }

        event.session.clientBound(motionPacket)
    }
}
