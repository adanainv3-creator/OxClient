package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
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

    // ✅ FIX: eskiden hotbarSlot varsayılanı sabit 0'dı — kılıç başka bir hotbar
    // slotundaysa server itemInHand'i slot 0'dan okuyup "elde silah yok" sanıyor
    // ve yumruk hasarı uyguluyordu. Artık EntityTracker'ın gerçekten takip ettiği
    // seçili slotu kullanıyoruz (bkz. EntityTracker.selfHotbarSlot / handleMobEquipment).
    fun sendAttack(session: OxRelaySession, targetRid: Long, hotbarSlot: Int = EntityTracker.selfHotbarSlot) {
        // ── FIX: InventoryTransactionSerializer.writeItemUseOnEntity() bu üç alanı
        // KOŞULSUZ okuyor (bkz. CloudburstMC/Protocol v291→v1001, hiç değişmemiş).
        // null bırakılırsa helper.writeVector3f(null)/writeItem(null) encode
        // aşamasında NPE atıyor — paket ağa hiç çıkmadan sessizce kayboluyor.
        // Bu yüzden KillAura/CrystalAura/AutoTotem'in saldırıları hiç işlemiyordu.
        val heldItem = EntityTracker.getInventoryItem(hotbarSlot) ?: ItemData.AIR
        val target   = EntityTracker.getById(targetRid)

        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val clickPos  = if (target != null) {
            // Hedefin yaklaşık gövde-orta yüksekliğine tıklamış gibi davran.
            Vector3f.from(target.x, target.y + 1.0f, target.z)
        } else {
            playerPos
        }

        session.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
            runtimeEntityId = targetRid
            actionType      = 1   // 1 = ATTACK
            this.hotbarSlot = hotbarSlot
            itemInHand      = heldItem
            playerPosition  = playerPos
            clickPosition   = clickPos
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
}
