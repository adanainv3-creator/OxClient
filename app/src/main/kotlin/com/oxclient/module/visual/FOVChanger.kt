package com.oxclient.module.visual

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.GameFov
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

class FOVChanger : BaseModule(
    name        = "FOVChanger",
    category    = ModuleCategory.VISUAL,
    description = "walkSpeed değerini sahteleyerek görüş açısını (FOV) değiştirir"
) {
    private val fov = float("FOV", 110f, 30f, 300f)

    private val DEFAULT_FOV   = GameFov.VANILLA_DEFAULT
    private val DEFAULT_SPEED = 0.1f

    private var isFovApplied  = false
    private var appliedSpeed  = DEFAULT_SPEED

    override fun onPacket(event: PacketEvent) {
        val packet = event.packet
        if (packet !is PlayerAuthInputPacket) return

        if (isEnabled) {
            val targetSpeed = DEFAULT_SPEED * (fov.value / DEFAULT_FOV)
            if (!isFovApplied || appliedSpeed != targetSpeed) {
                applySpeed(targetSpeed)
                appliedSpeed = targetSpeed
                isFovApplied = true
                GameFov.set(fov.value)
            }
        } else if (isFovApplied) {
            applySpeed(DEFAULT_SPEED)
            appliedSpeed = DEFAULT_SPEED
            isFovApplied = false
            GameFov.reset()
        }
    }

    private fun applySpeed(speedValue: Float) {
        val session = PacketEventBus.currentSession ?: return
        session.clientBound(buildAbilitiesPacket(EntityTracker.selfUniqueId, speedValue))
    }

    private fun buildAbilitiesPacket(entityId: Long, speedValue: Float): UpdateAbilitiesPacket {
        return UpdateAbilitiesPacket().apply {
            uniqueEntityId     = entityId
            playerPermission   = PlayerPermission.OPERATOR
            commandPermission  = CommandPermission.OWNER
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
                        Ability.OPERATOR_COMMANDS
                    )
                )
                this.walkSpeed = speedValue
            })
        }
    }
}
