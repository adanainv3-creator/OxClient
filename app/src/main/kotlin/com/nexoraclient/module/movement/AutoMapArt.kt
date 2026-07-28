package com.nexoraclient.module.movement

import com.nexoraclient.config.MapArtPlan
import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.core.relay.NexoraRelaySession
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.BaseModule
import com.nexoraclient.module.ModuleCategory
import com.nexoraclient.utils.InventoryUtil
import com.nexoraclient.utils.WorldBlockTracker
import kotlinx.coroutines.Job
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import kotlin.math.floor
import kotlin.math.sqrt

class AutoMapArt : BaseModule(
    name        = "AutoMapArt",
    category    = ModuleCategory.MOVEMENT,
    description = "Analiz edilen görseli yere pixel art olarak otomatik döşer"
) {
    companion object {
        private const val TICK_INTERVAL_MS = 50L
        private const val STEP_SIZE        = 0.20f
        private const val ARRIVE_DIST      = 0.35f
    }

    private val skipMissing = bool("Skip Missing Block", true)

    private var tickJob: Job? = null
    private var started = false
    private var targets: List<Triple<Int, Int, String>> = emptyList()
    private var index = 0
    private var originX = 0
    private var originY = 0
    private var originZ = 0
    private var missingCount = 0

    override fun onEnable() {
        super.onEnable()
        started = false
        index = 0
        missingCount = 0
        targets = emptyList()
        tickJob?.cancel()
        tickJob = launchTickLoop(TICK_INTERVAL_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        super.onDisable()
    }

    private fun buildSnakeOrder(grid: Array<Array<String>>): List<Triple<Int, Int, String>> {
        val size = grid.size
        val list = mutableListOf<Triple<Int, Int, String>>()
        for (row in 0 until size) {
            val cols = if (row % 2 == 0) (0 until size) else (size - 1 downTo 0)
            for (col in cols) list.add(Triple(col, row, grid[row][col]))
        }
        return list
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val grid = MapArtPlan.grid.value
        if (grid == null) {
            setEnabled(false)
            return
        }

        if (!started) {
            originX = floor(EntityTracker.selfX).toInt()
            originY = floor(EntityTracker.selfY).toInt()
            originZ = floor(EntityTracker.selfZ).toInt()
            targets = buildSnakeOrder(grid)
            index = 0
            missingCount = 0
            started = true
        }

        if (index >= targets.size) {
            setEnabled(false)
            return
        }

        val (col, row, blockId) = targets[index]
        val targetX = originX + col
        val targetZ = originZ + row
        val targetY = originY

        val existing = WorldBlockTracker.getBlockIdentifier(targetX, targetY, targetZ)
        if (existing == blockId) {
            index++
            return
        }

        val curX = EntityTracker.selfX
        val curZ = EntityTracker.selfZ
        val dx = (targetX + 0.5f) - curX
        val dz = (targetZ + 0.5f) - curZ
        val dist = sqrt(dx * dx + dz * dz)

        if (dist > ARRIVE_DIST) {
            moveToward(session, curX, curZ, dx, dz, dist)
            return
        }

        val hotbarSlot = findBlockInHotbar(blockId)
        if (hotbarSlot == null) {
            missingCount++
            index++
            if (!skipMissing.value && missingCount > 3) setEnabled(false)
            return
        }

        InventoryUtil.sendHotbarSelect(session, hotbarSlot)
        EntityTracker.selfHotbarSlot = hotbarSlot

        val placed = tryPlace(session, targetX, targetY, targetZ, hotbarSlot)
        if (placed) index++
    }

    private fun moveToward(session: NexoraRelaySession, curX: Float, curZ: Float, dx: Float, dz: Float, dist: Float) {
        val step = STEP_SIZE.coerceAtMost(dist)
        val nx = curX + (dx / dist) * step
        val nz = curZ + (dz / dist) * step

        val movePacket = MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(nx, EntityTracker.selfY, nz)
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        }
        session.serverBound(movePacket)
        session.clientBound(movePacket)

        EntityTracker.selfX = nx
        EntityTracker.selfZ = nz
    }

    private fun findBlockInHotbar(identifier: String): Int? {
        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull()
            if (id == identifier) return slot
        }
        return null
    }

    private fun tryPlace(session: NexoraRelaySession, x: Int, y: Int, z: Int, hotbarSlot: Int): Boolean {
        val heldItem = EntityTracker.getInventoryItem(hotbarSlot) ?: return false
        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType = 0
                blockPosition = Vector3i.from(x, y - 1, z)
                blockFace = 1
                this.hotbarSlot = hotbarSlot
                itemInHand = heldItem
                playerPosition = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
                triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState = 0
            })
            true
        } catch (_: Exception) {
            false
        }
    }
}
