package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

object PacketUtil {

    fun sendSwing(session: OxRelaySession) {
        session.serverBound(AnimatePacket().apply {
            action          = AnimatePacket.Action.SWING_ARM
            runtimeEntityId = EntityTracker.selfRuntimeId
        })
    }

    fun sendAttack(
        session: OxRelaySession,
        targetRid: Long,
        hotbarSlot: Int = EntityTracker.selfHotbarSlot,
        clickPos: Vector3f? = null
    ) {

        val heldItem = EntityTracker.getInventoryItem(hotbarSlot) ?: ItemData.AIR
        val target   = EntityTracker.getById(targetRid)

        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val resolvedClickPos = clickPos ?: if (target != null) {
            val heightDiff = EntityTracker.selfY - target.y
            val clickY = when {
                heightDiff < -0.5f -> target.y + 0.1f
                heightDiff > 1.8f  -> target.y + 1.7f
                else               -> target.y + 1.0f
            }
            Vector3f.from(target.x, clickY, target.z)
        } else {
            playerPos
        }

        session.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
            runtimeEntityId = targetRid
            actionType      = 1
            this.hotbarSlot = hotbarSlot
            itemInHand      = heldItem
            playerPosition  = playerPos
            clickPosition   = resolvedClickPos
        })
    }

    fun sendSwingAndAttack(session: OxRelaySession, targetRid: Long, hotbarSlot: Int = EntityTracker.selfHotbarSlot) {
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

    /**
     * Server-authoritative movement (auth-input) sunucularında fall distance/kritik hesaplaması
     * MovePlayerPacket'ten değil, ardışık PlayerAuthInputPacket.position.y tick'lerinden çıkarılıyor.
     * Bu fonksiyon gerçek, o an client'tan gelen auth-input paketinin Y'sini geçici olarak
     * dyOffset kadar aşağı kaydırır — paket iptal/replace edilmeden aynı nesne üzerinden devam eder.
     */
    fun applyAuthInputFallOffset(p: PlayerAuthInputPacket, dyOffset: Float) {
        val pos = p.position ?: return
        p.position = Vector3f.from(pos.x, pos.y - dyOffset, pos.z)
    }
}
