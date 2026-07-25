package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.InventoryUtil
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import com.oxclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class AutoRegear : BaseModule(
    name        = "AutoRegear",
    category    = ModuleCategory.COMBAT,
    description = "Yakındaki sandık/shulker içinden zırh, silah, altın elma, totem ve güç iksiri toplar"
) {
    private enum class State { IDLE, OPENING, LOOTING, CLOSING }

    companion object {
        private const val TICK_INTERVAL_MS  = 50L
        private const val OPEN_TIMEOUT_MS   = 1500L
        private const val VISIT_COOLDOWN_MS = 30_000L
        private val CONTAINER_IDS = setOf(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"
        )
        private val STRENGTH_POTION_META = setOf(22, 23, 24)
    }

    private val range          = float("Range",           5f,  2f,   8f)
    private val scanRadius     = int  ("Scan Radius",      16,  4,   32)
    private val scanInterval   = int  ("Scan Interval",    1000, 200, 5000)
    private val actionDelay    = int  ("Action Delay",     150, 50,  1000)
    private val takeArmor      = bool ("Take Armor",       true)
    private val takeWeapons    = bool ("Take Weapons",     true)
    private val takeGapples    = bool ("Take Gapples",     true)
    private val takeTotems     = bool ("Take Totems",      true)
    private val takeStrength   = bool ("Take Strength Pot", true)
    private val closeAfter     = bool ("Auto Close",       true)
    private val shortcut       = bool ("Shortcut",         true)

    private var tickJob: Job? = null
    private var state = State.IDLE
    private var targetPos: Vector3i? = null
    private var windowId: Int? = null
    private var containerType: ContainerType? = null
    private var stateSinceMs = 0L

    private val visited = ConcurrentHashMap<Long, Long>()

    override fun onEnable() {
        super.onEnable()
        state = State.IDLE
        targetPos = null
        windowId = null
        containerType = null
        visited.clear()
        tickJob?.cancel()
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        state = State.IDLE
        targetPos = null
        windowId = null
        containerType = null
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is ContainerOpenPacket -> {
                if (state != State.OPENING) return
                val pos = targetPos ?: return
                val openedAt = runCatching { pkt.blockPosition }.getOrNull()
                if (openedAt != null && openedAt != pos) return
                windowId = runCatching { pkt.id.toInt() }.getOrNull() ?: return
                containerType = runCatching { pkt.type }.getOrNull()
                state = State.LOOTING
                stateSinceMs = System.currentTimeMillis()
            }
            is InventoryContentPacket -> {
                if (state != State.LOOTING) return
                val wid = windowId ?: return
                val containerId = runCatching { pkt.containerId }.getOrNull() ?: return
                if (containerId != wid) return
                handleContents(event.session, wid, runCatching { pkt.contents }.getOrNull() ?: return)
            }
            is ContainerClosePacket -> {
                val id = runCatching { pkt.id.toInt() }.getOrNull()
                if (id != null && id == windowId) {
                    finishContainer()
                }
            }
            else -> {}
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                val session = PacketEventBus.currentSession
                if (session != null) {
                    when (state) {
                        State.IDLE      -> tryOpenNextContainer(session)
                        State.OPENING   -> checkOpenTimeout()
                        State.LOOTING   -> {}
                        State.CLOSING   -> {}
                    }
                }
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun checkOpenTimeout() {
        if (System.currentTimeMillis() - stateSinceMs > OPEN_TIMEOUT_MS) {
            targetPos?.let { visited[posKey(it.x, it.y, it.z)] = System.currentTimeMillis() }
            state = State.IDLE
            targetPos = null
        }
    }

    private fun tryOpenNextContainer(session: OxRelaySession) {
        val pos = findNearestContainer() ?: return
        targetPos = pos
        state = State.OPENING
        stateSinceMs = System.currentTimeMillis()

        val cx = pos.x + 0.5f
        val cy = pos.y + 0.5f
        val cz = pos.z + 0.5f
        val r = RotationUtil.toPoint(cx, cy, cz)
        PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)

        val heldItem = EntityTracker.getHeldItem() ?: ItemData.AIR
        try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                blockPosition            = pos
                blockFace                = 1
                hotbarSlot               = EntityTracker.selfHotbarSlot
                itemInHand               = heldItem
                playerPosition           = Vector3f.from(
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                clickPosition            = Vector3f.from(0.5f, 0.5f, 0.5f)
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
        } catch (_: Exception) {
            visited[posKey(pos.x, pos.y, pos.z)] = System.currentTimeMillis()
            state = State.IDLE
            targetPos = null
        }
    }

    private fun findNearestContainer(): Vector3i? {
        if (!WorldBlockTracker.hasAnyTerrainData()) return null

        val sx = floor(EntityTracker.selfX).toInt()
        val sy = floor(EntityTracker.selfY).toInt()
        val sz = floor(EntityTracker.selfZ).toInt()
        val r = scanRadius.value
        val now = System.currentTimeMillis()

        var best: Vector3i? = null
        var bestDistSq = Float.MAX_VALUE

        for (dx in -r..r) {
            for (dy in -4..4) {
                for (dz in -r..r) {
                    val bx = sx + dx
                    val by = sy + dy
                    val bz = sz + dz

                    val key = posKey(bx, by, bz)
                    val lastVisit = visited[key]
                    if (lastVisit != null && now - lastVisit < VISIT_COOLDOWN_MS) continue

                    val id = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
                    if (id !in CONTAINER_IDS && !id.endsWith("shulker_box")) continue

                    val cx = bx + 0.5f
                    val cy = by + 0.5f
                    val cz = bz + 0.5f
                    val distSq = MathUtil.dist3(
                        cx, cy, cz,
                        EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ
                    ).let { it * it }
                    if (distSq > range.value * range.value) continue

                    if (distSq < bestDistSq) {
                        bestDistSq = distSq
                        best = Vector3i.from(bx, by, bz)
                    }
                }
            }
        }
        return best
    }

    private fun handleContents(session: OxRelaySession, wid: Int, contents: List<ItemData>) {
        val matches = contents.mapIndexedNotNull { slot, item ->
            if (!InventoryUtil.isEmpty(item) && matchesWanted(item)) slot to item else null
        }

        if (matches.isEmpty()) {
            finishContainer()
            return
        }

        scope.launch {
            for ((slot, item) in matches) {
                val destSlot = findEmptyPlayerSlot() ?: break
                try {
                    InventoryUtil.sendInventoryMove(
                        session            = session,
                        sourceContainer    = ContainerSlotType.DYNAMIC_CONTAINER,
                        sourceContainerId  = wid,
                        sourceSlot         = slot,
                        sourceItem         = item,
                        destContainer      = ContainerSlotType.HOTBAR_AND_INVENTORY,
                        destContainerId    = ContainerId.INVENTORY,
                        destSlot           = destSlot,
                        destItem           = ItemData.AIR
                    )
                } catch (_: Exception) {}
                delay(actionDelay.value.toLong())
            }
            finishContainer()
        }
    }

    private fun findEmptyPlayerSlot(): Int? {
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.INV_END) {
            val item = EntityTracker.getInventoryItem(slot)
            if (InventoryUtil.isEmpty(item)) return slot
        }
        return null
    }

    private fun matchesWanted(item: ItemData): Boolean {
        val id = runCatching { item.definition?.identifier }.getOrNull() ?: return false
        return when {
            takeArmor.value && (
                id.endsWith("_helmet") || id.endsWith("_chestplate") ||
                id.endsWith("_leggings") || id.endsWith("_boots") ||
                id == "minecraft:turtle_helmet" || id == "minecraft:elytra"
            ) -> true
            takeWeapons.value && (id.endsWith("_sword") || id == "minecraft:trident") -> true
            takeGapples.value && id == "minecraft:enchanted_golden_apple" -> true
            takeTotems.value && id == "minecraft:totem_of_undying" -> true
            takeStrength.value && id == "minecraft:potion" && item.damage in STRENGTH_POTION_META -> true
            else -> false
        }
    }

    private fun finishContainer() {
        val pos = targetPos
        val session = PacketEventBus.currentSession
        if (closeAfter.value && session != null && windowId != null) {
            try {
                session.serverBound(ContainerClosePacket().apply {
                    id = windowId!!.toByte()
                    containerType?.let { type = it }
                })
            } catch (_: Exception) {}
        }
        if (pos != null) visited[posKey(pos.x, pos.y, pos.z)] = System.currentTimeMillis()
        state = State.IDLE
        targetPos = null
        windowId = null
        containerType = null
    }

    private fun posKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
