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

/**
 * ✅ EXTREME FOV CHANGER
 * 
 * GÜÇLENDIRMELER:
 *  - FOV: 110-300 → 110-540 (EXTREME wide)
 *  - walkSpeed: 0.1 → 0.01 base (10x daha agresif)
 *  - serverBound() + clientBound() dual sending
 *  - Multiplier tuning: daha agresif FOV→speed mapping
 */
class FOVChanger : BaseModule(
    name        = "FOVChanger",
    category    = ModuleCategory.VISUAL,
    description = "Çok geniş görüş açısı (EXTREME FOV)"
) {
    // ✅ FOV: 110 → 540 (5x daha geniş!)
    private val fov = float("FOV", 110f, 10f, 540f)

    private val DEFAULT_FOV   = GameFov.VANILLA_DEFAULT  // Genellikle 70
    private val DEFAULT_SPEED = 0.01f  // 0.1 → 0.01 (daha agresif)

    private var isFovApplied  = false
    private var appliedSpeed  = DEFAULT_SPEED

    override fun onPacket(event: PacketEvent) {
        val packet = event.packet
        if (packet !is PlayerAuthInputPacket) return

        if (isEnabled) {
            // ✅ AGRESIF HESAPLAMA: FOV → walkSpeed
            // Formula: walkSpeed = baseSpeed * (FOV / vanillaFOV) * multiplier
            val fovRatio = (fov.value / DEFAULT_FOV).coerceAtLeast(1f)
            val targetSpeed = DEFAULT_SPEED * fovRatio * 1.5f  // 1.5x extra multiplier
            
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
        
        // ✅ DUO PAKET: clientBound + serverBound (tutarlılık için)
        try {
            // Client→Server: player abilities update
            session.serverBound(buildAbilitiesPacket(EntityTracker.selfUniqueId, speedValue))
        } catch (e: Exception) {
        }
        
        try {
            // Server→Client: visual feedback (clientBound fallback)
            session.clientBound(buildAbilitiesPacket(EntityTracker.selfUniqueId, speedValue))
        } catch (e: Exception) {
        }
    }

    private fun buildAbilitiesPacket(entityId: Long, speedValue: Float): UpdateAbilitiesPacket {
        return UpdateAbilitiesPacket().apply {
            uniqueEntityId     = entityId
            playerPermission   = PlayerPermission.OPERATOR
            commandPermission  = CommandPermission.OWNER
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())
                
                // ✅ ALL ABILITIES ENABLED
                abilityValues.addAll(
                    arrayOf(
                        Ability.BUILD,
                        Ability.MINE,
                        Ability.DOORS_AND_SWITCHES,
                        Ability.OPEN_CONTAINERS,
                        Ability.ATTACK_PLAYERS,
                        Ability.ATTACK_MOBS,
                        Ability.OPERATOR_COMMANDS,
                        Ability.TELEPORT
                    )
                )
                
                // ✅ AGRESIF SPEED SETTING
                this.walkSpeed = speedValue.coerceAtLeast(0.001f)  // Min 0.001f
                this.flySpeed = speedValue * 2f  // Uçabilirse daha hızlı
                this.mineSpeed = speedValue
                this.buildSpeed = speedValue
            })
        }
    }
}
