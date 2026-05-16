package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket

object PacketUtil {

    fun sendSwing(session: OxRelaySession) {
        session.serverBound(AnimatePacket().apply {
            action          = AnimatePacket.Action.SWING_ARM
            runtimeEntityId = EntityTracker.selfRuntimeId
        })
    }

    fun sendAttack(session: OxRelaySession, targetRid: Long, hotbarSlot: Int = 0) {
        session.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionPacket.TYPE_ITEM_USE_ON_ENTITY
            actionType      = InventoryTransactionPacket.ACTION_ENTITY_ATTACK
            this.runtimeEntityId = targetRid
            this.hotbarSlot      = hotbarSlot
        })
    }

    fun sendSwingAndAttack(session: OxRelaySession, targetRid: Long, hotbarSlot: Int = 0) {
        sendSwing(session)
        sendAttack(session, targetRid, hotbarSlot)
    }

    fun sendMove(
        session  : OxRelaySession,
        x        : Float,
        y        : Float,
        z        : Float,
        yaw      : Float,
        pitch    : Float,
        onGround : Boolean = true,
        teleport : Boolean = false
    ) {
        session.serverBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(x, y, z)
            rotation              = Vector3f.from(pitch, yaw, yaw)
            mode                  = if (teleport) MovePlayerPacket.Mode.TELEPORT
                                    else          MovePlayerPacket.Mode.NORMAL
            isOnGround            = onGround
            ridingRuntimeEntityId = 0L
        })
    }

    fun sendMoveAtSelf(
        session  : OxRelaySession,
        yaw      : Float   = EntityTracker.selfYaw,
        pitch    : Float   = EntityTracker.selfPitch,
        dyOffset : Float   = 0f,
        onGround : Boolean = true,
        teleport : Boolean = false
    ) = sendMove(
        session,
        EntityTracker.selfX,
        EntityTracker.selfY + dyOffset,
        EntityTracker.selfZ,
        yaw, pitch, onGround, teleport
    )
}
