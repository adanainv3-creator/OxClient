package com.oxclient.utils

import com.oxclient.core.relay.OxRelaySession
import com.oxclient.core.proxy.EntityTracker
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemDefinition
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket

object InventoryUtil {

    const val OFFHAND_SLOT  = 119
    const val HOTBAR_START  = 0
    const val HOTBAR_END    = 8
    const val INV_START     = 9
    const val INV_END       = 35

    fun sendEquip(
        session: OxRelaySession,
        runtimeId: Long,
        containerId: Int,
        slot: Int,
        hotbarSlot: Int,
        item: ItemData
    ) {
        session.serverBound(MobEquipmentPacket().apply {
            runtimeEntityId  = runtimeId
            this.containerId = containerId
            // FIX: In 3.x, MobEquipmentPacket uses inventorySlot / hotbarSlot
            this.inventorySlot = slot
            this.hotbarSlot    = hotbarSlot
            this.item          = item
        })
    }

    fun sendOffhandEquip(session: OxRelaySession, fromSlot: Int, itemId: Int) {
        sendEquip(
            session,
            EntityTracker.selfRuntimeId,
            containerId = 0,
            slot        = fromSlot,
            hotbarSlot  = OFFHAND_SLOT,
            // FIX: In 3.x use ItemData.builder() with definition(ItemDefinition) and damage
            item        = ItemData.builder()
                .definition(ItemDefinition.of(itemId))
                .count(1)
                .damage(0)
                .build()
        )
    }

    fun sendHotbarSelect(session: OxRelaySession, slot: Int) {
        session.serverBound(MobEquipmentPacket().apply {
            runtimeEntityId  = EntityTracker.selfRuntimeId
            containerId      = 0
            inventorySlot    = slot
            hotbarSlot       = slot
            item             = ItemData.AIR
        })
    }

    fun isWeaponId(id: Int): Boolean = id in WEAPON_IDS

    fun isTotemId(id: Int): Boolean = id == 702

    fun isFoodId(id: Int): Boolean = id in FOOD_IDS

    fun isPotionId(id: Int): Boolean = id in POTION_IDS

    private val WEAPON_IDS = setOf(
        271, 272, 273, 274, 275,
        276, 277, 278, 279, 280,
        293, 294, 295, 296, 297,
        598, 599, 600, 601, 602
    )

    private val FOOD_IDS = setOf(
        260, 297, 319, 320, 349, 350, 354, 355,
        357, 360, 363, 364, 365, 366, 367, 420,
        423, 424, 469, 477
    )

    private val POTION_IDS = setOf(373, 438, 441)
}
