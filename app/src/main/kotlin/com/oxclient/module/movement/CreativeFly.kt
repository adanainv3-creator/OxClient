package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.module.*
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

class CreativeFly : BaseModule(
    name        = "CreativeFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Ability tabanlı native uçuş (MotionFly'daki gibi motion paket hilesi değil)"
) {
    private val flySpeed  = float("Fly Speed",  0.5f, 0.05f, 2.0f)
    private val walkSpeed = float("Walk Speed", 0.1f, 0.02f, 0.5f)
    private val keepAlive = bool ("Keep Alive", true) // sunucu abilities'i sıfırlarsa hemen geri uygula

    @Volatile private var lastSession: OxRelaySession? = null
    @Volatile private var abilitiesSent = false

    override fun onEnable() {
        super.onEnable()
        abilitiesSent = false
    }

    override fun onDisable() {
        super.onDisable()
        lastSession?.let { sendAbilities(it, false) }
        abilitiesSent = false
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet

        if (pkt is PlayerAuthInputPacket && event.direction == PacketEvent.Direction.CLIENT_TO_SERVER) {
            lastSession = event.session
            if (!abilitiesSent) {
                sendAbilities(event.session, true)
                abilitiesSent = true
            }
        }

        // Sunucu kendi UpdateAbilitiesPacket'ini gönderip bizimkini ezerse
        // (Keep Alive açıksa) hemen tekrar uçuş yetkisini uygula.
        if (keepAlive.value && pkt is UpdateAbilitiesPacket &&
            event.direction == PacketEvent.Direction.SERVER_TO_CLIENT
        ) {
            event.cancel()
            lastSession?.let { sendAbilities(it, true) }
        }
    }

    private fun sendAbilities(session: OxRelaySession, enabled: Boolean) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = if (enabled) PlayerPermission.OPERATOR else PlayerPermission.VISITOR
            commandPermission = if (enabled) CommandPermission.OWNER  else CommandPermission.ANY
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())

                val values = mutableListOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
                if (enabled) {
                    values += Ability.MAY_FLY
                    values += Ability.FLYING
                    values += Ability.OPERATOR_COMMANDS
                }
                abilityValues.addAll(values.toTypedArray())

                this.walkSpeed = walkSpeed.value
                this.flySpeed  = if (enabled) flySpeed.value else 0.05f
            })
        }
        session.clientBound(packet)
    }
}
