package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.Definitions
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

class SelfTrap : BaseModule(
    name        = "SelfTrap",
    category    = ModuleCategory.COMBAT,
    description = "Kendini çevredeki bloklarla kapatır"
) {
    enum class TriggerMode { Always, EnemyNearby, Manual }
    enum class Shape { Sides, SidesAndTop, Full }

    companion object {
        private const val TICK_MS            = 100L
        private const val BLOCK_DEF_SCAN_CAP = 20000
        private const val BLOCK_DEF_MISS_LIMIT = 64
        private const val PENDING_RETRY_MS   = 200L

        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )

        private val SIDE_OFFSETS = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
        )
    }

    private val trigger        = enum("Trigger", TriggerMode.EnemyNearby)
    private val shape           = enum("Shape", Shape.SidesAndTop)
    private val blockIdentifier = string("Block", "minecraft:obsidian")
    private val enemyRange      = float("Enemy Range", 6f, 2f, 16f)
    private val friendSkip      = bool("Friend Skip", true)
    private val placePerSec     = int("Place/Sec", 20, 1, 60)
    private val noSwitch        = bool("No Switch", true)

    private val pending        = ConcurrentHashMap<Long, Long>()
    private val blockDefCache  = ConcurrentHashMap<String, BlockDefinition>()

    private val placeTokens = AtomicInteger(0)
    @Volatile private var tokenWindowStart = 0L
    @Volatile private var currentWindowCap = 0

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null

    private data class PreparedItem(val slot: Int, val item: ItemData, val revertTo: Int?)

    override fun onEnable() {
        super.onEnable()
        pending.clear(); blockDefCache.clear()
        placeTokens.set(0); tokenWindowStart = 0L
        tickJob?.cancel()
        tickJob = launchTickLoop(TICK_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        super.onDisable()
        pending.clear()
    }

    override fun onPacket(event: com.rubidiumclient.events.PacketEvent) {
        if (!isEnabled) return
        val p = event.packet
        if (p is UpdateBlockPacket) {
            val id = runCatching { p.definition?.runtimeId }.getOrNull() ?: return
            pending.remove(posKey(p.blockPosition.x, p.blockPosition.y, p.blockPosition.z))
        }
    }

    private fun tick() {
        if (trigger.value == TriggerMode.Manual) return
        if (trigger.value == TriggerMode.EnemyNearby && !enemyNearby()) return

        val session = PacketEventBus.currentSession ?: return
        doTrap(session)
    }

    private fun enemyNearby(): Boolean {
        if (EntityTracker.selfRuntimeId <= 0L) return false
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        return EntityTracker.getPlayers(enemyRange.value)
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { !friendSkip.value || !it.isFriendEntity }
            .any()
    }

    private fun doTrap(session: RubidiumRelaySession) {
        val fx = floor(EntityTracker.selfX).toInt()
        val fy = floor(EntityTracker.selfY).toInt()
        val fz = floor(EntityTracker.selfZ).toInt()

        val targets = ArrayList<Vector3i>(6)
        for (off in SIDE_OFFSETS) targets.add(Vector3i.from(fx + off[0], fy, fz + off[1]))
        if (shape.value == Shape.SidesAndTop || shape.value == Shape.Full) {
            targets.add(Vector3i.from(fx, fy + 2, fz))
        }
        if (shape.value == Shape.Full) {
            targets.add(Vector3i.from(fx, fy - 1, fz))
        }

        val hasData = WorldBlockTracker.hasAnyTerrainData()
        var prepared: PreparedItem? = null
        try {
            for (pos in targets) {
                val existing = if (hasData) WorldBlockTracker.getBlockIdentifier(pos.x, pos.y, pos.z) else null
                if (existing != null && existing !in NON_SOLID) continue

                val now = System.currentTimeMillis()
                val key = posKey(pos.x, pos.y, pos.z)
                if (now - (pending[key] ?: 0L) < PENDING_RETRY_MS) continue

                if (!takePlaceToken()) break

                if (prepared == null) prepared = prepareItemForUse(session, blockIdentifier.value) ?: break

                if (sendPlacementUseRaw(session, prepared, pos, blockIdentifier.value)) pending[key] = now
            }
        } finally {
            prepared?.revertTo?.let {
                InventoryUtil.sendHotbarSelect(session, it)
                EntityTracker.selfHotbarSlot = it
            }
        }
    }

    private fun takePlaceToken(): Boolean {
        val now = System.currentTimeMillis()
        val cap = placePerSec.value
        if (now - tokenWindowStart >= 1000L) {
            tokenWindowStart = now; currentWindowCap = cap; placeTokens.set(cap)
        } else if (cap > currentWindowCap) {
            placeTokens.addAndGet(cap - currentWindowCap); currentWindowCap = cap
        }
        return placeTokens.getAndUpdate { if (it > 0) it - 1 else it } > 0
    }

    private fun prepareItemForUse(session: RubidiumRelaySession, identifier: String): PreparedItem? {
        EntityTracker.getHeldItem()?.let { held ->
            if (InventoryUtil.resolveIdentifier(held) == identifier && held.count > 0) {
                return PreparedItem(EntityTracker.selfHotbarSlot, held, null)
            }
        }

        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            if (InventoryUtil.resolveIdentifier(item) != identifier) continue
            val original = EntityTracker.selfHotbarSlot
            InventoryUtil.sendHotbarSelect(session, slot)
            EntityTracker.selfHotbarSlot = slot
            return PreparedItem(slot, item, if (noSwitch.value) original else null)
        }
        return null
    }

    private fun sendPlacementUseRaw(session: RubidiumRelaySession, prepared: PreparedItem, blockPos: Vector3i, blockId: String): Boolean {
        val blockDef  = getBlockDefinition(session, blockId) ?: return false
        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                this.blockPosition       = blockPos
                blockFace                = 1
                hotbarSlot               = prepared.slot
                itemInHand               = prepared.item
                playerPosition           = playerPos
                clickPosition            = Vector3f.from(0.5f, 1.0f, 0.5f)
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
            true
        } catch (_: Exception) { false }
    }

    private fun getBlockDefinition(session: RubidiumRelaySession, targetId: String): BlockDefinition? {
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

        val fallbackId = when (targetId) {
            "minecraft:obsidian"        -> 49
            "minecraft:cobblestone"     -> 4
            "minecraft:bedrock"         -> 7
            else                        -> return null
        }
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

    private fun posKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
