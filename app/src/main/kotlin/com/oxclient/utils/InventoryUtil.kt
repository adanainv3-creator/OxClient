package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
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
        val packet = MobEquipmentPacket().apply {
            this.runtimeEntityId = runtimeId
            this.containerId = containerId
            this.inventorySlot = slot
            this.hotbarSlot = hotbarSlot
            this.item = item
        }
        session.serverBound(packet)
    }

    /**
     * Offhand'e ekipman gönderir.
     * @param session Oturum
     * @param fromSlot Totemin bulunduğu envanter slotu
     * @param itemData Tam ItemData nesnesi
     */
    fun sendOffhandEquip(session: OxRelaySession, fromSlot: Int, itemData: ItemData) {
        sendEquip(
            session     = session,
            runtimeId   = EntityTracker.selfRuntimeId,
            containerId = ContainerId.INVENTORY,
            slot        = fromSlot,
            hotbarSlot  = 40, // Offhand için doğru hotbar slot
            item        = itemData
        )
    }

    /**
     * NetId ve ItemDefinition'dan ItemData oluşturup offhand'e gönderir.
     */
    fun sendOffhandEquip(session: OxRelaySession, fromSlot: Int, netId: Int, definition: ItemDefinition) {
        val item = ItemData.builder()
            .definition(definition)
            .netId(netId)
            .count(1)
            .damage(0)
            .usingNetId(true)
            .build()
        sendOffhandEquip(session, fromSlot, item)
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

    /**
     * Bir ItemData'nın boş/hava slotu olup olmadığını kontrol eder.
     * ✅ FIX: `item == ItemData.AIR` referans/structural kontrolü Beta6-SNAPSHOT'ta
     * gelen boş slotlarla eşleşmiyordu (netId farkı). Artık definition/count bazlı
     * anlamsal kontrol kullanılıyor.
     */
    fun isEmpty(item: ItemData?): Boolean {
        if (item == null) return true
        return try { item.definition == null || item.count <= 0 } catch (_: Exception) { true }
    }

    /**
     * ItemData'nın totem olup olmadığını kontrol eder.
     */
    fun isTotem(item: ItemData?): Boolean {
        if (isEmpty(item)) return false
        val identifier = try { item?.definition?.identifier } catch (_: Exception) { null }
        return identifier == "minecraft:totem_of_undying"
    }

    @Deprecated("netId item tipini değil stack'i temsil eder, isTotem() kullan", ReplaceWith("isTotem(item)"))
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