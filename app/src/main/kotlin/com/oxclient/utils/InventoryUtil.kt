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

    private val TOTEM_NET_IDS = mutableSetOf<Int>()

    private val TOTEM_IDENTIFIERS = setOf(
        "minecraft:totem",
        "minecraft:totem_of_undying",
        "minecraft:totem_of_undying_legacy"
    )

    fun registerTotemNetIds(definitions: Collection<ItemDefinition>) {
        definitions.forEach { def ->
            val id = def.identifier
            if (id in TOTEM_IDENTIFIERS) {
                val runtimeId = def.runtimeId
                TOTEM_NET_IDS.add(runtimeId)
                OverlayLogger.d(TAG, "✅ Totem runtimeId kaydedildi: $runtimeId (${def.identifier})")
            }
        }
        if (TOTEM_NET_IDS.isNotEmpty()) {
            OverlayLogger.d(TAG, "Toplam ${TOTEM_NET_IDS.size} totem netId kaydedildi: $TOTEM_NET_IDS")
        }
    }

    fun addTotemNetId(netId: Int) {
        TOTEM_NET_IDS.add(netId)
        OverlayLogger.d(TAG, "Totem netId eklendi: $netId")
    }

    fun clearTotemNetIds() {
        TOTEM_NET_IDS.clear()
        OverlayLogger.d(TAG, "Totem netId'leri temizlendi")
    }

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

    fun sendOffhandEquip(session: OxRelaySession, fromSlot: Int, itemData: ItemData) {
        sendEquip(
            session     = session,
            runtimeId   = EntityTracker.selfRuntimeId,
            containerId = ContainerId.INVENTORY,
            slot        = fromSlot,
            hotbarSlot  = 40,
            item        = itemData
        )
    }

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

    fun isEmpty(item: ItemData?): Boolean {
        if (item == null) return true
        
        val netId = try { item.netId } catch (_: Exception) { 0 }
        if (netId <= 0) return true
        
        return try {
            val def = item.definition
            if (def == null) {
                netId <= 0
            } else {
                item.count <= 0 || def.identifier == "minecraft:air"
            }
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "isEmpty exception: ${e.message} — netId=$netId, fallback to netId check")
            netId <= 0
        }
    }

    fun isTotem(item: ItemData?): Boolean {
        if (isEmpty(item)) return false

        // 1. Definition ile kontrol
        try {
            val def = item?.definition
            if (def != null) {
                val identifier = def.identifier
                if (identifier in TOTEM_IDENTIFIERS) {
                    return true
                }
                if (identifier.contains("totem", ignoreCase = true)) {
                    return true
                }
            }
        } catch (e: Exception) {
            OverlayLogger.v(TAG, "isTotem: definition okunamadı — ${e.message}")
        }

        // 2. Kayıtlı netId'ler ile kontrol
        val netId = try { item?.netId ?: 0 } catch (_: Exception) { 0 }
        if (netId > 0) {
            if (netId in TOTEM_NET_IDS) {
                return true
            }
            
            // 3. Fallback: netId 500-600 aralığında ve definition null ise totem olarak kabul et
            if (netId in 500..600) {
                try {
                    val def = item?.definition
                    if (def == null) {
                        OverlayLogger.v(TAG, "isTotem: netId=$netId aralıkta, definition null → totem olarak kabul")
                        return true
                    }
                } catch (_: Exception) {}
            }
        }

        return false
    }

    fun isTotemNetId(netId: Int): Boolean {
        if (netId <= 0) return false
        if (netId in TOTEM_NET_IDS) return true
        return netId in 500..600
    }

    fun isTotemDefinition(def: ItemDefinition?): Boolean {
        if (def == null) return false
        val id = def.identifier
        return id in TOTEM_IDENTIFIERS || id.contains("totem", ignoreCase = true)
    }

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