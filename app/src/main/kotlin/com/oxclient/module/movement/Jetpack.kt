package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class Jetpack : BaseModule(
    name        = "Jetpack",
    category    = ModuleCategory.MOVEMENT,
    description = "Paket tabanlı uçuş / jetpack hareketi"
) {
    enum class JetMode { Jetpack, Glide, YPort, Motion, Teleport, Jump, Hover, Boost }

    private val mode            = enum ("Mode",             JetMode.Jetpack)
    private val verticalSpeed   = float("Vertical Speed",   1.5f,  0.1f, 10f)
    private val horizontalSpeed = float("Horizontal Speed", 1.5f,  0.1f, 10f)
    private val add             = float("Add",             -0.02f,-0.5f,  0.5f)
    private val hoverHeight     = float("Hover Height",     1.0f,  0.1f,  5f)
    private val boostMultiplier = float("Boost Multiplier", 2.0f,  1.0f, 10f)
    private val pressJump       = bool ("Press Jump",       true)
    private val antiKick        = bool ("Anti Kick",        false)
    private val shortcut        = bool ("Shortcut",         false)

    @Volatile private var jumpPressed  = false
    @Volatile private var tickCount    = 0
    @Volatile private var hoverBaseY   = 0f

    override fun onEnable() {
        super.onEnable()
        tickCount  = 0
        hoverBaseY = EntityTracker.selfY
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is PlayerAuthInputPacket -> handleAuthInput(event, pkt)
            is MovePlayerPacket      -> handleMovePlayer(event, pkt)
            else -> {}
        }
    }

    private fun handleAuthInput(event: PacketEvent, pkt: PlayerAuthInputPacket) {
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        jumpPressed = pkt.inputData?.contains(
            org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.JUMP_DOWN
        ) == true
        tickCount++
        val newY = calcNewY(pkt.position.y)
        if (newY == pkt.position.y) return
        event.replacementPacket = PlayerAuthInputPacket().apply {
            tick             = pkt.tick
            position         = Vector3f.from(pkt.position.x, newY, pkt.position.z)
            rotation         = pkt.rotation
            delta            = pkt.delta
            inputData        = pkt.inputData
            motionX          = pkt.motionX
            motionZ          = pkt.motionZ
            analogMoveVector = pkt.analogMoveVector
        }
    }

    private fun handleMovePlayer(event: PacketEvent, pkt: MovePlayerPacket) {
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
        tickCount++
        val newY = calcNewY(pkt.position.y)
        if (newY == pkt.position.y) return
        event.replacementPacket = MovePlayerPacket().apply {
            runtimeEntityId       = pkt.runtimeEntityId
            position              = Vector3f.from(pkt.position.x, newY, pkt.position.z)
            rotation              = pkt.rotation
            mode                  = if (mode.value == JetMode.Teleport) MovePlayerPacket.Mode.TELEPORT
                                    else MovePlayerPacket.Mode.NORMAL
            isOnGround            = false
            ridingRuntimeEntityId = 0L
        }
    }

    private fun calcNewY(currentY: Float): Float {
        val v = verticalSpeed.value
        val a = add.value
        return when (mode.value) {
            JetMode.Jetpack  -> if (pressJump.value && jumpPressed) currentY + v * 0.05f else currentY + a
            JetMode.Glide    -> if (currentY > EntityTracker.selfY) currentY else currentY - 0.05f
            JetMode.YPort    -> if (tickCount % 20 < 10) currentY + v * 0.1f else currentY - v * 0.08f
            JetMode.Motion   -> currentY + v * 0.05f + a
            JetMode.Teleport -> currentY + v * 0.5f
            JetMode.Jump     -> if (tickCount % 10 == 0) currentY + 0.42f else currentY + a
            JetMode.Hover    -> {
                val target = hoverBaseY + hoverHeight.value
                currentY + (target - currentY) * 0.15f
            }
            JetMode.Boost    -> {
                val mult = boostMultiplier.value
                if (pressJump.value && jumpPressed) currentY + v * 0.05f * mult else currentY + a
            }
        }
    }
}
