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
    description = "Zıplama tuşuna basılı tutulduğunda motion paketiyle yukarı itiş uygular"
) {
    private val verticalSpeed   = float("Vertical Speed",   1.2f, 0.1f, 3.0f)
    private val horizontalBoost = float("Horizontal Boost", 0.3f, 0.0f, 2.0f)
    private val requireJump     = bool ("Require Jump",     true)
    private val shortcut        = bool ("Shortcut",         false)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val jumping = pkt.inputData.contains(PlayerAuthInputData.JUMPING) ||
                      pkt.inputData.contains(PlayerAuthInputData.WANT_UP)

        if (requireJump.value && !jumping) return

        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val yaw    = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        val strafe  = inputX * horizontalBoost.value
        val forward = inputZ * horizontalBoost.value

        val motionX = strafe * cosYaw - forward * sinYaw
        val motionZ = forward * cosYaw + strafe * sinYaw

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(
                motionX,
                verticalSpeed.value,
                motionZ
            )
        }

        event.session.clientBound(motionPacket)
    }
}
