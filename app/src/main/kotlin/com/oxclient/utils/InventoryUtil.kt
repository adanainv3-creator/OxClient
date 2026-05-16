package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket

object InventoryUtil {

    const val OFFHAND_SLOT = 119
    const val HOTBAR_START = 0
    const val HOTBAR_END   = 8
    const val INV_START    = 9
    const val INV_END      = 35

    fun sendEquip(
        session    : OxRelaySession,
        runtimeId  : Long,
        containerId: Int,
        slot       : Int,
        hotbarSlot : Int,
        item       : ItemData
    ) {
        session.serverBound(MobEquipmentPacket().apply {
            this.runtimeEntityId = runtimeId
            this.containerId     = containerId
            this.inventorySlot   = slot
            this.hotbarSlot      = hotbarSlot
            this.item            = item
        })
    }

    fun sendOffhandEquip(session: OxRelaySession, fromSlot: Int, itemNetworkId: Int) {
        val item = ItemData.builder()
            .netId(itemNetworkId)
            .count(1)
            .build()
        sendEquip(
            session     = session,
            runtimeId   = EntityTracker.selfRuntimeId,
            containerId = ContainerId.INVENTORY,
            slot        = fromSlot,
            hotbarSlot  = OFFHAND_SLOT,
            item        = item
        )
    }

    fun sendHotbarSelect(session: OxRelaySession, slot: Int) {
        session.serverBound(MobEquipmentPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            containerId     = ContainerId.INVENTORY
            inventorySlot   = slot
            hotbarSlot      = slot
            item            = ItemData.AIR
        })
    }

    fun isTotemNetId(netId: Int): Boolean = netId == 702

    private val FOOD_NET_IDS = setOf(
        260, 297, 319, 320, 349, 350, 354, 355,
        357, 360, 363, 364, 365, 366, 367, 420,
        423, 424, 469, 477
    )
    private val POTION_NET_IDS = setOf(373, 438, 441)
    private val WEAPON_NET_IDS = setOf(
        271, 272, 273, 274, 275, 276, 277, 278, 279, 280,
        293, 294, 295, 296, 297, 598, 599, 600, 601, 602
    )

    fun isFoodNetId(netId: Int)   : Boolean = netId in FOOD_NET_IDS
    fun isPotionNetId(netId: Int) : Boolean = netId in POTION_NET_IDS
    fun isWeaponNetId(netId: Int) : Boolean = netId in WEAPON_NET_IDS
}
