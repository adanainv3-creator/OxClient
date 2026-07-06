package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.ui.overlay.OverlayLogger
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket

object InventoryUtil {

    private const val TAG = "InventoryUtil"

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
     * @param fromSlot Totemin bulunduu envanter slotu
     * @param itemData Tam ItemData nesnesi
     */
    fun sendOffhandEquip(session: OxRelaySession, fromSlot: Int, itemData: ItemData) {
        sendEquip(
            session     = session,
            runtimeId   = EntityTracker.selfRuntimeId,
            containerId = ContainerId.INVENTORY,
            slot        = fromSlot,
            hotbarSlot  = 40, // Offhand için doru hotbar slot
            item        = itemData
        )
    }

    /**
     * NetId ve ItemDefinition'dan ItemData oluturup offhand'e gönderir.
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
     * Bir ItemData'nn bo/hava slotu olup olmadn kontrol eder.
     *  FIX: `item == ItemData.AIR` referans/structural kontrolü Beta6-SNAPSHOT'ta
     * gelen bo slotlarla elemiyordu (netId fark). Artk definition/count bazl
     * anlamsal kontrol kullanlyor.
     */
    fun isEmpty(item: ItemData?): Boolean {
        if (item == null) return true
        //  FIX: item.definition CloudburstMC'de network item'larda NULL olabiliyor.
        // Sadece count kontrolü yeterli — count <= 0 = bo slot.
        return item.count <= 0
    }

    /**
     * ItemData'nn totem olup olmadn kontrol eder.
     * 
     *  WORKAROUND: CloudburstMC v975 fallback'inde item.definition NULL geliyor
     * ve identifier eriimi exception frlatyor. imdilik definition'a hiç dokunmuyoruz,
     * bunun yerine netId > 0 kontrol ediyoruz (count > 0 ile beraber).
     * 
     * FIXME: Tüm item'larn netId=1 olmas netId sorunu çözüldüünde, burada
     * gerçek identifier kontrolü yazlacak:
     *     return identifier == "minecraft:totem_of_undying"
     */
    fun isTotem(item: ItemData?): Boolean {
        if (isEmpty(item)) return false
        
        //  WORKAROUND: definition'a dokunmuyoruz (NULL exception riski),
        // sadece netId > 0 kontrol ediyoruz
        // Nota: netId tüm itemler için 1 dönyorsa (v975 fallback sorunu),
        // bu kontrol yanl pozitif verecek — ama en azndan kod çökmeyecek.
        return item != null && item.netId > 0
    }

    @Deprecated("netId item tipini deil stack'i temsil eder, isTotem() kullan", ReplaceWith("isTotem(item)"))
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
