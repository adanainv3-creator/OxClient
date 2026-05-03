package com.oxclient.module.combat

import com.oxclient.core.proxy.PacketFactory
import com.oxclient.core.proxy.PacketIds
import com.oxclient.events.PacketEvent
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.session.SessionManager
import com.oxclient.utils.BinaryUtils

/**
 * Criticals — forces 1.5× critical hit damage on every melee attack.
 *
 * Modes:
 *   Vanilla    — no-op, natural jump apex
 *   MovePacket — Y+0.1 → Y+0 before attack
 *   Jump       — PlayerAction JUMP
 *   TPJump     — Y+0.42 → Y+0 (ProtoHax style, most reliable)
 */
class Criticals : BaseModule(
    name        = "Criticals",
    description = "Forces 1.5× critical hits via packet manipulation",
    category    = ModuleCategory.COMBAT
) {
    val mode = enumSetting("Mode", Mode.TP_JUMP, Mode.values())

    private var lastCritMs = 0L

    override fun onPacketSend(event: PacketEvent) {
        if (event.packetId != PacketIds.INVENTORY_TRANSACTION) return
        val b = BinaryUtils.wrap(event.payload)
        BinaryUtils.skipVarInt(b); BinaryUtils.skipVarInt(b)
        if (BinaryUtils.readVarInt(b) != PacketIds.TX_USE_ITEM_ON_ENTITY) return
        val now = System.currentTimeMillis()
        if (now - lastCritMs < 50L) return
        inject(); lastCritMs = now
    }

    private fun inject() {
        val proxy = SessionManager.proxy ?: return
        val et = SessionManager.entityTracker
        val id = et.selfId; val x = et.selfX; val y = et.selfY; val z = et.selfZ

        when (mode.value) {
            Mode.VANILLA -> {}
            Mode.MOVE_PACKET -> {
                proxy.injectC2S(PacketFactory.buildMovePlayer(id,x,y+0.1f,z,0f,0f,0f,false))
                proxy.injectC2S(PacketFactory.buildMovePlayer(id,x,y,z,0f,0f,0f,true))
            }
            Mode.JUMP -> {
                proxy.injectC2S(PacketFactory.buildPlayerAction(id, PacketIds.ACTION_JUMP))
            }
            Mode.TP_JUMP -> {
                proxy.injectC2S(PacketFactory.buildMovePlayer(id,x,y+0.42f,z,0f,0f,0f,false))
                proxy.injectC2S(PacketFactory.buildMovePlayer(id,x,y,z,0f,0f,0f,true))
            }
        }
    }

    enum class Mode { VANILLA, MOVE_PACKET, JUMP, TP_JUMP }
}
