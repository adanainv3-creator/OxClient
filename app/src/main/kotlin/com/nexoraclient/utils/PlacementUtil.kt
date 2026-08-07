package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.Definitions
import com.rubidiumclient.core.relay.RubidiumRelaySession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import java.util.concurrent.ConcurrentHashMap

object PlacementUtil {

    private const val BLOCK_DEF_SCAN_CAP   = 20000
    private const val BLOCK_DEF_MISS_LIMIT = 64

    data class PreparedItem(val slot: Int, val item: ItemData, val revertTo: Int?)

    private val blockDefCache = ConcurrentHashMap<String, BlockDefinition>()

    // Canlı registry taraması başarısız olursa (bazı sunucu/versiyon
    // kombinasyonlarında olabilir) diye son çare sabit runtime id'ler.
    // Bunlar vanilla Bedrock'ta yaygın olarak görülen id'ler — sunucunun
    // custom bir block palette'i varsa yanlış olabilir, o yüzden bu sadece
    // canlı tarama tamamen başarısız olduğunda devreye giriyor.
    private val FALLBACK_IDS = mapOf(
        "minecraft:obsidian"    to 49,
        "minecraft:cobblestone" to 4,
        "minecraft:bedrock"     to 7,
        "minecraft:end_crystal" to 198,
        "minecraft:piston"      to 33,
        "minecraft:sticky_piston" to 29,
        "minecraft:lever"       to 69,
        "minecraft:red_bed"     to 26,
        "minecraft:respawn_anchor" to 502,
        "minecraft:glowstone"   to 89
    )

    fun findItemInInventory(identifier: String): Pair<Int, ItemData>? {
        EntityTracker.getHeldItem()?.let { held ->
            if (held.count > 0 && InventoryUtil.resolveIdentifier(held) == identifier) {
                return EntityTracker.selfHotbarSlot to held
            }
        }
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            if (InventoryUtil.resolveIdentifier(item) == identifier) return slot to item
        }
        return null
    }

    fun prepareItemForUse(session: RubidiumRelaySession, identifier: String, noSwitch: Boolean = true): PreparedItem? {
        val (slot, item) = findItemInInventory(identifier) ?: return moveFromInventory(session, identifier, noSwitch)

        if (slot == EntityTracker.selfHotbarSlot) return PreparedItem(slot, item, null)
        val original = EntityTracker.selfHotbarSlot
        InventoryUtil.sendHotbarSelect(session, slot)
        EntityTracker.selfHotbarSlot = slot
        return PreparedItem(slot, item, if (noSwitch) original else null)
    }

    private fun moveFromInventory(session: RubidiumRelaySession, identifier: String, noSwitch: Boolean): PreparedItem? {
        for (slot in InventoryUtil.INV_START..InventoryUtil.INV_END) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0 || InventoryUtil.resolveIdentifier(item) != identifier) continue
            val destSlot = findEmptyHotbarSlot() ?: continue
            val destItem = EntityTracker.getInventoryItem(destSlot) ?: ItemData.AIR
            try {
                InventoryUtil.sendInventoryMove(
                    session = session,
                    sourceContainer = ContainerSlotType.HOTBAR_AND_INVENTORY,
                    sourceContainerId = ContainerId.INVENTORY,
                    sourceSlot = slot, sourceItem = item,
                    destContainer = ContainerSlotType.HOTBAR_AND_INVENTORY,
                    destContainerId = ContainerId.INVENTORY,
                    destSlot = destSlot, destItem = destItem
                )
            } catch (_: Exception) { continue }
            val original = EntityTracker.selfHotbarSlot
            InventoryUtil.sendHotbarSelect(session, destSlot)
            EntityTracker.selfHotbarSlot = destSlot
            return PreparedItem(destSlot, item, if (noSwitch) original else null)
        }
        return null
    }

    private fun findEmptyHotbarSlot(): Int? {
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot)
            if (item == null || item.count <= 0) return slot
        }
        return null
    }

    fun revert(session: RubidiumRelaySession, prepared: PreparedItem) {
        prepared.revertTo?.let {
            InventoryUtil.sendHotbarSelect(session, it)
            EntityTracker.selfHotbarSlot = it
        }
    }

    fun sendPlacementUseRaw(
        session: RubidiumRelaySession,
        prepared: PreparedItem,
        blockPos: Vector3i,
        blockId: String,
        blockFace: Int = 1,
        clickPosition: Vector3f = Vector3f.from(0.5f, 1.0f, 0.5f)
    ): Boolean {
        val blockDef  = getBlockDefinition(session, blockId) ?: return false
        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                this.blockPosition       = blockPos
                this.blockFace           = blockFace
                hotbarSlot               = prepared.slot
                itemInHand               = prepared.item
                playerPosition           = playerPos
                this.clickPosition       = clickPosition
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
            true
        } catch (_: Exception) { false }
    }

    // Elde item olmadan bir bloğa sağ tık (lever/buton toggle, yatakta uyuma/patlatma
    // interaction'ı). Bedrock server, itemInHand boşsa bunu "place" değil "interact"
    // olarak yorumluyor — aynı ITEM_USE transaction'ı, farklı niyet.
    fun sendInteract(
        session: RubidiumRelaySession,
        blockPos: Vector3i,
        blockId: String,
        blockFace: Int = 1,
        clickPosition: Vector3f = Vector3f.from(0.5f, 0.5f, 0.5f)
    ): Boolean {
        val blockDef  = getBlockDefinition(session, blockId) ?: return false
        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                this.blockPosition       = blockPos
                this.blockFace           = blockFace
                hotbarSlot               = EntityTracker.selfHotbarSlot
                itemInHand               = ItemData.AIR
                playerPosition           = playerPos
                this.clickPosition       = clickPosition
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
            true
        } catch (_: Exception) { false }
    }

    fun getBlockDefinition(session: RubidiumRelaySession, targetId: String): BlockDefinition? {
        blockDefCache[targetId]?.let { return it }
        try {
            val blockDefs = session.clientSession.peer.codecHelper.blockDefinitions
            if (blockDefs != null) {
                var i = 0; var misses = 0
                while (i < BLOCK_DEF_SCAN_CAP && misses < BLOCK_DEF_MISS_LIMIT) {
                    val def = try { blockDefs.getDefinition(i) } catch (_: Exception) { null }
                    if (def == null) { misses++; i++; continue }
                    misses = 0
                    val id = when (def) {
                        is SimpleBlockDefinition -> def.identifier
                        is Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
                        else -> null
                    }
                    if (id == targetId) { blockDefCache[targetId] = def; return def }
                    i++
                }
            }
        } catch (_: Exception) {}

        val fallbackId = FALLBACK_IDS[targetId] ?: return null
        val fallback = SimpleBlockDefinition(
            targetId, fallbackId,
            org.cloudburstmc.nbt.NbtMap.builder()
                .putString("name", targetId)
                .putCompound("states", org.cloudburstmc.nbt.NbtMap.builder().build())
                .build()
        )
        blockDefCache[targetId] = fallback
        return fallback
    }

    fun posKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
