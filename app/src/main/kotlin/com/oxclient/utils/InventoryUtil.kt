package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEventBus
import com.oxclient.ui.overlay.OverlayLogger
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import java.util.concurrent.atomic.AtomicInteger

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
        OverlayLogger.d(TAG, "sendEquip: runtimeId=$runtimeId containerId=$containerId slot=$slot hotbarSlot=$hotbarSlot netId=${item.netId} count=${item.count}")
        val packet = MobEquipmentPacket().apply {
            this.runtimeEntityId = runtimeId
            this.containerId = containerId
            this.inventorySlot = slot
            this.hotbarSlot = hotbarSlot
            this.item = item
        }
        session.serverBound(packet)
        OverlayLogger.d(TAG, "sendEquip: MobEquipmentPacket gönderildi")
    }

    fun sendOffhandEquip(session: OxRelaySession, fromSlot: Int, itemData: ItemData) {
        OverlayLogger.d(TAG, "sendOffhandEquip: fromSlot=$fromSlot containerId=OFFHAND(${ContainerId.OFFHAND}) netId=${itemData.netId} count=${itemData.count} defId=${runCatching { itemData.definition?.identifier }.getOrElse { "ERR" }}")
        sendEquip(
            session     = session,
            runtimeId   = EntityTracker.selfRuntimeId,
            containerId = ContainerId.OFFHAND,
            slot        = fromSlot,
            hotbarSlot  = 0,
            item        = itemData
        )
    }

    fun sendOffhandEquip(session: OxRelaySession, fromSlot: Int, netId: Int, definition: ItemDefinition) {
        OverlayLogger.d(TAG, "sendOffhandEquip(def): fromSlot=$fromSlot netId=$netId defId=${runCatching { definition.identifier }.getOrElse { "ERR" }}")
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
        OverlayLogger.d(TAG, "sendHotbarSelect: slot=$slot")
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
        return item.count <= 0
    }

    fun isTotem(item: ItemData?): Boolean {
        if (isEmpty(item)) return false

        val identifier = resolveIdentifier(item!!)

        if (identifier != null) {
            val result = identifier == "minecraft:totem_of_undying"
            OverlayLogger.v(TAG, "isTotem check: identifier=$identifier runtimeId=${runCatching { item.definition?.runtimeId }.getOrElse { -1 }} netId=${item.netId} count=${item.count} sonuc=$result")
            return result
        }

        val runtimeId = runCatching { item.definition?.runtimeId }.getOrElse { null } ?: -1
        val totemRuntimeId = resolveTotemRuntimeIdFromCodec()
        val result = totemRuntimeId > 0 && runtimeId == totemRuntimeId
        OverlayLogger.v(TAG, "isTotem fallback: runtimeId=$runtimeId totemRuntimeId=$totemRuntimeId netId=${item.netId} count=${item.count} sonuc=$result")
        return result
    }

    enum class ArmorSlotType(val slotIndex: Int) { HELMET(0), CHESTPLATE(1), LEGGINGS(2), BOOTS(3) }

    fun resolveArmorSlotType(item: ItemData?): ArmorSlotType? {
        if (isEmpty(item)) return null
        val identifier = resolveIdentifier(item!!) ?: return null
        return when {
            identifier.endsWith("_helmet") || identifier == "minecraft:turtle_helmet" -> ArmorSlotType.HELMET
            identifier.endsWith("_chestplate") || identifier == "minecraft:elytra" -> ArmorSlotType.CHESTPLATE
            identifier.endsWith("_leggings") -> ArmorSlotType.LEGGINGS
            identifier.endsWith("_boots") -> ArmorSlotType.BOOTS
            else -> null
        }
    }

    private val ARMOR_MATERIAL_TIER = mapOf(
        "leather" to 1, "golden" to 2, "chainmail" to 2,
        "iron" to 3, "diamond" to 4, "netherite" to 5
    )

    fun armorMaterialTier(item: ItemData?): Int {
        if (isEmpty(item)) return -1
        val identifier = resolveIdentifier(item!!) ?: return 0
        val material = identifier.removePrefix("minecraft:").substringBefore("_")
        return ARMOR_MATERIAL_TIER[material] ?: if (identifier == "minecraft:turtle_helmet") 2 else 0
    }

    private val stackRequestIdCounter = AtomicInteger(0)
    fun nextStackRequestId(): Int = stackRequestIdCounter.decrementAndGet()

    fun sendSlotSwap(
        session: OxRelaySession,
        sourceSlot: Int,
        sourceNetId: Int,
        destContainer: ContainerSlotType,
        destSlot: Int,
        destNetId: Int
    ) {
        val source = ItemStackRequestSlotData(
            ContainerSlotType.HOTBAR_AND_INVENTORY,
            sourceSlot,
            sourceNetId,
            FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null)
        )
        val destination = ItemStackRequestSlotData(
            destContainer,
            destSlot,
            destNetId,
            FullContainerName(destContainer, null)
        )
        val request = ItemStackRequest(
            nextStackRequestId(),
            arrayOf<ItemStackRequestAction>(SwapAction(source, destination)),
            arrayOf<String>()
        )
        session.serverBound(ItemStackRequestPacket().apply { requests.add(request) })
        OverlayLogger.d(TAG, "ItemStackRequest (Swap) gönderildi: srcSlot=$sourceSlot srcNetId=$sourceNetId -> $destContainer[$destSlot] destNetId=$destNetId")
    }

    private fun resolveIdentifier(item: ItemData): String? {
        val fromDefinition = runCatching { item.definition?.identifier }.getOrElse { null }
        if (!fromDefinition.isNullOrBlank()) return fromDefinition

        val runtimeId = runCatching { item.definition?.runtimeId }.getOrElse { null } ?: return null
        if (runtimeId <= 0) return null

        val session = PacketEventBus.currentSession ?: return null
        val reg = runCatching { session.clientSession.peer.codecHelper.itemDefinitions }.getOrElse { null } ?: return null
        return runCatching { reg.getDefinition(runtimeId)?.identifier }.getOrElse { null }
    }

    private fun resolveTotemRuntimeIdFromCodec(): Int {
        val session = PacketEventBus.currentSession ?: return -1
        val reg = runCatching { session.clientSession.peer.codecHelper.itemDefinitions }.getOrElse { null } ?: return -1
        for (rid in 600..1800) {
            val def = runCatching { reg.getDefinition(rid) }.getOrElse { null } ?: continue
            if (def.identifier == "minecraft:totem_of_undying") return rid
        }
        return -1
    }

    @Deprecated("netId item tipini değil stack'i temsil eder, isTotem() kullan", ReplaceWith("isTotem(item)"))
    fun isTotemNetId(netId: Int): Boolean = false

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
