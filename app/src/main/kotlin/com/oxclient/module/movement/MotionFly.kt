package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin

class MotionFly : BaseModule(
    name        = "MotionFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Motion paketi tabanlı uçuş"
) {
    private val horizontalSpeed = float("Horizontal Speed", 3.5f, 0.5f, 10.0f)
    private val verticalSpeed   = float("Vertical Speed",   1.5f, 0.5f, 5.0f)
    private val glideSpeed      = float("Glide Speed",      0.1f, -0.01f, 1.0f)
    private val motionInterval  = float("Delay",            50.0f, 10.0f, 100.0f)
    private val shortcut        = bool ("Shortcut",         false)

    @Volatile private var lastMotionTime = 0L
    @Volatile private var jitterState    = false
    @Volatile private var canFly         = false
    @Volatile private var lastSession    : OxRelaySession? = null

    private val flyPacket = UpdateAbilitiesPacket().apply {
        playerPermission  = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        uniqueEntityId    = -1
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS,
                    Ability.MAY_FLY,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed  = 0.5f
        })
    }

    private val resetPacket = UpdateAbilitiesPacket().apply {
        playerPermission  = PlayerPermission.VISITOR
        commandPermission = CommandPermission.ANY
        uniqueEntityId    = -1
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed  = 0.05f
        })
    }

    override fun onEnable() {
        super.onEnable()
        lastMotionTime = 0L
        jitterState = false
        canFly = false
    }

    override fun onDisable() {
        super.onDisable()
        lastSession?.let { applyFlyAbilities(false, it) }
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        lastSession = event.session
        applyFlyAbilities(true, event.session)

        if (System.currentTimeMillis() - lastMotionTime < motionInterval.value) return

        val vertical = when {
            pkt.inputData.contains(PlayerAuthInputData.WANT_UP)   -> verticalSpeed.value
            pkt.inputData.contains(PlayerAuthInputData.WANT_DOWN) -> -verticalSpeed.value
            else -> glideSpeed.value
        }

        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val yaw    = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        val strafe  = inputX * horizontalSpeed.value
        val forward = inputZ * horizontalSpeed.value

        val motionX = strafe * cosYaw - forward * sinYaw
        val motionZ = forward * cosYaw + strafe * sinYaw

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(
                motionX,
                vertical + if (jitterState) 0.05f else -0.05f,
                motionZ
            )
        }

        event.session.clientBound(motionPacket)
        jitterState = !jitterState
        lastMotionTime = System.currentTimeMillis()
    }

    private fun applyFlyAbilities(enabled: Boolean, session: OxRelaySession) {
        if (canFly == enabled) return
        val id = EntityTracker.selfUniqueId
        flyPacket.uniqueEntityId = id
        resetPacket.uniqueEntityId = id
        session.clientBound(if (enabled) flyPacket else resetPacket)
        canFly = enabled
    }
}
