package com.nexoraclient.module.misc

import android.graphics.Canvas
import android.graphics.Paint
import com.nexoraclient.config.MapArtPlan
import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.core.relay.NexoraRelaySession
import com.nexoraclient.events.PacketEvent
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.BaseModule
import com.nexoraclient.module.ModuleCategory
import com.nexoraclient.utils.BlockPalette
import com.nexoraclient.utils.BlockTracker
import com.nexoraclient.utils.InventoryUtil
import com.nexoraclient.utils.WorldBlockTracker
import kotlinx.coroutines.Job
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

class AutoMapArt : BaseModule(
    name        = "AutoMapArt",
    category    = ModuleCategory.MOVEMENT,
    description = "Automatically builds the analyzed image on the ground as pixel art"
) {
    companion object {
        private const val TICK_INTERVAL_MS      = 50L
        private const val STEP_SIZE             = 0.22f
        private const val NODE_ARRIVE_DIST      = 0.35f
        private const val SCAN_INTERVAL_TICKS   = 20
        private const val MAX_BLOCK_PLACE_ATTEMPTS = 10
        private const val AIR = "minecraft:air"

        private const val CHEST_INTERACT_RANGE   = 4f
        private const val CONTAINER_OPEN_TIMEOUT_MS = 3000L

        private const val SCHEMATIC_MAX_PX  = 240f
        private const val SCHEMATIC_MARGIN  = 28f
    }

    private val schematicFillPaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val schematicHighlightPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFFFFEB3B.toInt()
        isAntiAlias = false
    }
    private val schematicBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xAA000000.toInt()
    }
    private val schematicTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 26f
        isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
    }

    private val skipMissing       = bool("Skip Missing Block", true)
    val autoScanInventory         = bool("Auto Scan Inventory", false)
    private val rotateToPlace     = bool("Rotate Towards", true)
    private val placementDelay    = int("Place Delay (ticks)", 5, 0, 40)
    private val placementRange    = int("Place Range (blocks)", 3, 1, 8)
    private val blacklistedBlocksSetting = string("Blacklisted Blocks", "")
    private val autoCollectMaterials = bool("Auto Collect Materials", true)
    private val chestSearchRange  = int("Chest Search Range (blocks)", 24, 4, 64)
    private val showSchematic     = bool("Show Schematic", true)

    private var tickJob: Job? = null

    private var originSet         = false
    private var originX           = 0
    private var originY           = 0
    private var originZ           = 0

    private var lastGridRef: Array<Array<String>>? = null

    @Volatile private var paused  = false

    private val completedTypes    = mutableSetOf<String>()
    private var currentBlockType: String? = null
    private val queue             = ArrayDeque<Pair<Int, Int>>()

    private var placeTimer        = 0
    private var currentFailedAttempts = 0

    private var pathRunner: MapArtPathRunner? = null

    private var scanTick          = 0

    private enum class Stage { BUILDING, COLLECTING }
    private var stage: Stage = Stage.BUILDING

    private var collectingBlockType: String? = null
    private val checkedChests     = mutableSetOf<Long>()
    private var targetChest: BlockTracker.TrackedBlock? = null
    private var chestPathRunner: MapArtPathRunner? = null

    @Volatile private var containerOpen     = false
    @Volatile private var openContainerId   = -1
    @Volatile private var lastContainerContents: List<ItemData>? = null
    private var containerAwaitSinceMs = 0L

    @Volatile var availableBlocks: Set<String> = emptySet()
        set(value) {
            val changed = field != value
            field = value
            if (changed) MapArtPlan.reanalyze(value)
        }

    @Volatile var lastScannedBlocks: Set<String> = emptySet()

    private val allPaletteIds: Set<String> by lazy {
        BlockPalette.ALL.map { it.identifier }.toHashSet()
    }

    override fun onEnable() {
        super.onEnable()

        if (MapArtPlan.grid.value == null) {
            sendMessage("§eAutoMapArt: No image analyzed yet. Pick one from Dashboard > Configs > AutoMapArt.")
        }
        if (!WorldBlockTracker.hasAnyTerrainData()) {
            sendMessage("§eAutoMapArt: No chunk data — are you connected and in a world?")
        }

        originSet   = false
        paused      = false
        lastGridRef = null
        completedTypes.clear()
        currentBlockType = null
        queue.clear()
        placeTimer = 0
        currentFailedAttempts = 0
        pathRunner = null
        scanTick   = 0

        stage = Stage.BUILDING
        collectingBlockType = null
        checkedChests.clear()
        targetChest = null
        chestPathRunner = null
        containerOpen = false
        openContainerId = -1
        lastContainerContents = null
        containerAwaitSinceMs = 0L

        tickJob?.cancel()
        tickJob = launchTickLoop(TICK_INTERVAL_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        pathRunner = null
        chestPathRunner = null
        if (containerOpen) {
            PacketEventBus.currentSession?.let { closeContainer(it) }
        }
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

        when (val p = event.packet) {
            is ContainerOpenPacket -> {
                val chest = targetChest ?: return
                val pos = p.blockPosition ?: return
                if (pos.x == chest.pos.x && pos.y == chest.pos.y && pos.z == chest.pos.z) {
                    openContainerId = p.id.toInt()
                    containerOpen = true
                }
            }
            is InventoryContentPacket -> {
                if (containerOpen && p.containerId == openContainerId) {
                    lastContainerContents = p.contents.toList()
                }
            }
            is ContainerClosePacket -> {
                if (p.id.toInt() == openContainerId) {
                    containerOpen = false
                    openContainerId = -1
                    lastContainerContents = null
                }
            }
            else -> {}
        }
    }

    fun isPaused(): Boolean = paused

    fun pause() {
        if (paused) { sendMessage("§eAutoMapArt is already paused"); return }
        paused = true
        sendMessage("§eAutoMapArt paused — use unpause to continue")
    }

    fun unpause() {
        if (!paused) { sendMessage("§eAutoMapArt is already running"); return }
        paused = false
        sendMessage("§aAutoMapArt resumed")
    }

    fun skipCurrentBlock() {
        if (paused) { sendMessage("§eAutoMapArt is paused"); return }
        currentBlockType?.let { completedTypes.add(it) }
        currentBlockType = null
        queue.clear()
        pathRunner = null
        currentFailedAttempts = 0
        sendMessage("§eSkipping current block type")
    }

    fun scanInventoryBlocks(): Set<String> {
        val found = mutableSetOf<String>()
        for (slot in 0..35) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull() ?: continue
            if (id in allPaletteIds) found.add(id)
        }
        return found
    }

    private fun tick() {
        if (autoScanInventory.value) {
            scanTick++
            if (scanTick >= SCAN_INTERVAL_TICKS) {
                scanTick = 0
                val scanned = scanInventoryBlocks()
                if (scanned != availableBlocks) availableBlocks = scanned
            }
        }

        if (paused) return

        val session = PacketEventBus.currentSession ?: return
        val grid = MapArtPlan.grid.value ?: return

        if (grid !== lastGridRef) {
            lastGridRef = grid
            completedTypes.clear()
            currentBlockType = null
            queue.clear()
            pathRunner = null
            currentFailedAttempts = 0
        }

        if (!originSet) {
            if (!WorldBlockTracker.hasAnyTerrainData()) return
            originX = floor(EntityTracker.selfX).toInt()
            originY = floor(EntityTracker.selfY - 1f).toInt()
            originZ = floor(EntityTracker.selfZ).toInt()
            originSet = true
            sendMessage("§aAutoMapArt started — origin: ($originX, $originY, $originZ)")
        }

        if (stage == Stage.COLLECTING) {
            collectMaterials(session)
            return
        }

        if (currentBlockType == null || queue.isEmpty()) {
            if (queue.isEmpty() && !assignNextBlockType(grid)) {
                sendMessage("§aAutoMapArt complete!")
                setEnabled(false)
                return
            }
            if (queue.isEmpty()) return
        }

        if (placeTimer > 0) {
            placeTimer--
            return
        }

        val (col, row) = queue.first()
        val blockId  = currentBlockType ?: return
        val targetX  = originX + col
        val targetZ  = originZ + row
        val targetY  = originY

        if (!WorldBlockTracker.hasData(targetX, targetY, targetZ)) {
            queue.removeFirst()
            return
        }

        if (WorldBlockTracker.getBlockIdentifier(targetX, targetY, targetZ) == blockId) {
            queue.removeFirst()
            pathRunner = null
            return
        }

        val hotbarSlot = findBlockInHotbar(blockId)
        if (hotbarSlot == null) {
            when {
                skipMissing.value -> queue.removeFirst()
                autoCollectMaterials.value -> {
                    stage = Stage.COLLECTING
                    collectingBlockType = blockId
                    checkedChests.clear()
                    targetChest = null
                    chestPathRunner = null
                    sendMessage("§eMissing block: ${BlockPalette.displayName(blockId)} — scanning nearby chests")
                }
                else -> {
                    pause()
                    sendMessage("§cMissing block: ${BlockPalette.displayName(blockId)} — restock your inventory and type .unpause")
                }
            }
            return
        }

        val curX = EntityTracker.selfX
        val curZ = EntityTracker.selfZ
        val dx   = (targetX + 0.5f) - curX
        val dz   = (targetZ + 0.5f) - curZ
        val dist = sqrt(dx * dx + dz * dz)

        if (dist > placementRange.value) {
            val runner = pathRunner ?: MapArtPathRunner(
                Vector3i.from(floor(curX).toInt(), floor(EntityTracker.selfY - 1f).toInt(), floor(curZ).toInt()),
                Vector3i.from(targetX, targetY, targetZ)
            ).also { pathRunner = it }

            if (!runner.active) {
                sendMessage("§cPathfinding failed: ($targetX, $targetY, $targetZ) — skipping block")
                queue.removeFirst()
                pathRunner = null
                return
            }

            runner.run(session)
            return
        }

        pathRunner = null

        if (rotateToPlace.value) faceBlock(session, dx, dz)

        InventoryUtil.sendHotbarSelect(session, hotbarSlot)
        EntityTracker.selfHotbarSlot = hotbarSlot

        if (tryPlace(session, targetX, targetY, targetZ, hotbarSlot)) {
            currentFailedAttempts = 0
            queue.removeFirst()
            placeTimer = placementDelay.value
        } else {
            currentFailedAttempts++
            if (currentFailedAttempts >= MAX_BLOCK_PLACE_ATTEMPTS) {
                currentFailedAttempts = 0
                val failed = queue.removeFirst()
                queue.addLast(failed)
            }
        }
    }

    private fun assignNextBlockType(grid: Array<Array<String>>): Boolean {
        currentBlockType?.let { completedTypes.add(it) }
        currentBlockType = null

        val blacklist = parseBlacklist()
        val size = grid.size

        outer@ for (row in 0 until size) {
            val cols = if (row % 2 == 0) (0 until size) else (size - 1 downTo 0)
            for (col in cols) {
                val id = grid[row][col]
                if (id in completedTypes || id in blacklist) continue

                val tx = originX + col
                val tz = originZ + row
                if (WorldBlockTracker.hasData(tx, originY, tz) &&
                    WorldBlockTracker.getBlockIdentifier(tx, originY, tz) == id
                ) continue

                currentBlockType = id
                break@outer
            }
        }

        val type = currentBlockType ?: return false

        queue.clear()
        for (row in 0 until size) {
            val cols = if (row % 2 == 0) (0 until size) else (size - 1 downTo 0)
            for (col in cols) {
                if (grid[row][col] != type) continue
                val tx = originX + col
                val tz = originZ + row
                if (WorldBlockTracker.hasData(tx, originY, tz) &&
                    WorldBlockTracker.getBlockIdentifier(tx, originY, tz) == type
                ) continue
                queue.addLast(col to row)
            }
        }

        if (queue.isEmpty()) {
            completedTypes.add(type)
            currentBlockType = null
            return assignNextBlockType(grid)
        }

        sendMessage("§bNow building: ${BlockPalette.displayName(type)} (${queue.size} blocks)")
        return true
    }

    private fun parseBlacklist(): Set<String> =
        blacklistedBlocksSetting.value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun collectMaterials(session: NexoraRelaySession) {
        val type = collectingBlockType ?: run { stage = Stage.BUILDING; return }

        if (findBlockInHotbar(type) != null) {
            finishCollecting("§a${BlockPalette.displayName(type)} found, resuming build")
            return
        }
        val invSlot = findInMainInventory(type)
        if (invSlot != null) {
            moveToFreeHotbarSlot(session, invSlot, type)
            finishCollecting("§a${BlockPalette.displayName(type)} found, resuming build")
            return
        }

        if (targetChest == null) {
            if (!findNextChest(type)) {
                if (containerOpen) closeContainer(session)
                pause()
                sendMessage("§cNo chest with ${BlockPalette.displayName(type)} found nearby (${chestSearchRange.value} blocks) — pausing")
                stage = Stage.BUILDING
                return
            }
        }

        val chest = targetChest ?: return
        val curX = EntityTracker.selfX
        val curY = EntityTracker.selfY
        val curZ = EntityTracker.selfZ
        val dx = (chest.pos.x + 0.5f) - curX
        val dz = (chest.pos.z + 0.5f) - curZ
        val dist = sqrt(dx * dx + dz * dz)

        if (dist > CHEST_INTERACT_RANGE) {
            val runner = chestPathRunner ?: MapArtPathRunner(
                Vector3i.from(floor(curX).toInt(), floor(curY - 1f).toInt(), floor(curZ).toInt()),
                chest.pos
            ).also { chestPathRunner = it }

            if (!runner.active) {
                sendMessage("§cCouldn't reach the chest, trying another one")
                checkedChests.add(BlockTracker.packKey(chest.pos.x, chest.pos.y, chest.pos.z))
                targetChest = null
                chestPathRunner = null
                return
            }
            runner.run(session)
            return
        }
        chestPathRunner = null

        if (!containerOpen) {
            if (containerAwaitSinceMs == 0L) {
                sendOpenContainerInteract(session, chest.pos)
                containerAwaitSinceMs = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - containerAwaitSinceMs > CONTAINER_OPEN_TIMEOUT_MS) {
                checkedChests.add(BlockTracker.packKey(chest.pos.x, chest.pos.y, chest.pos.z))
                targetChest = null
                containerAwaitSinceMs = 0L
            }
            return
        }
        containerAwaitSinceMs = 0L

        val contents = lastContainerContents
        if (contents == null) return

        val slotIndex = contents.indexOfFirst { item ->
            !InventoryUtil.isEmpty(item) &&
                runCatching { item.definition?.identifier }.getOrNull() == type
        }

        if (slotIndex < 0) {
            checkedChests.add(BlockTracker.packKey(chest.pos.x, chest.pos.y, chest.pos.z))
            closeContainer(session)
            targetChest = null
            return
        }

        val sourceItem = contents[slotIndex]
        val destSlot = findFreeOrMatchingSlot(type)
        if (destSlot == null) {
            closeContainer(session)
            pause()
            sendMessage("§cInventory full — make room and type .unpause")
            stage = Stage.BUILDING
            return
        }
        val destItem = EntityTracker.getInventoryItem(destSlot) ?: ItemData.AIR

        InventoryUtil.sendInventoryMove(
            session           = session,
            sourceContainer   = ContainerSlotType.CONTAINER,
            sourceContainerId = openContainerId,
            sourceSlot        = slotIndex,
            sourceItem        = sourceItem,
            destContainer     = ContainerSlotType.HOTBAR_AND_INVENTORY,
            destContainerId   = 0,
            destSlot          = destSlot,
            destItem          = destItem
        )

        closeContainer(session)
        targetChest = null
    }

    private fun finishCollecting(msg: String) {
        stage = Stage.BUILDING
        collectingBlockType = null
        checkedChests.clear()
        targetChest = null
        chestPathRunner = null
        containerAwaitSinceMs = 0L
        sendMessage(msg)
    }

    private fun findNextChest(neededType: String): Boolean {
        val self = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val nearby = BlockTracker.getAllInRange(self.x, self.y, self.z, chestSearchRange.value.toFloat())
            .filter {
                (it.type == BlockTracker.TrackedBlockType.CHEST ||
                    it.type == BlockTracker.TrackedBlockType.TRAPPED_CHEST ||
                    it.type == BlockTracker.TrackedBlockType.BARREL) &&
                    BlockTracker.packKey(it.pos.x, it.pos.y, it.pos.z) !in checkedChests
            }
            .sortedBy { distSq(self.x, self.z, it.pos.x + 0.5f, it.pos.z + 0.5f) }

        targetChest = nearby.firstOrNull()
        return targetChest != null
    }

    private fun distSq(x1: Float, z1: Float, x2: Float, z2: Float): Float {
        val dx = x1 - x2; val dz = z1 - z2
        return dx * dx + dz * dz
    }

    private fun sendOpenContainerInteract(session: NexoraRelaySession, pos: Vector3i) {
        val hotbarSlot = EntityTracker.selfHotbarSlot
        val heldItem = EntityTracker.getInventoryItem(hotbarSlot) ?: ItemData.AIR
        try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType      = 0
                blockPosition   = pos
                blockFace       = 1
                this.hotbarSlot = hotbarSlot
                itemInHand      = heldItem
                playerPosition  = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                clickPosition   = Vector3f.from(0.5f, 0.5f, 0.5f)
                triggerType               = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction  = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState       = 0
            })
        } catch (_: Exception) {}
    }

    private fun closeContainer(session: NexoraRelaySession) {
        if (!containerOpen) return
        try {
            session.serverBound(ContainerClosePacket().apply {
                id = openContainerId.toByte()
                isServerInitiated = false
            })
        } catch (_: Exception) {}
        containerOpen = false
        openContainerId = -1
        lastContainerContents = null
    }

    private fun findInMainInventory(identifier: String): Int? {
        for (slot in InventoryUtil.INV_START..InventoryUtil.INV_END) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (InventoryUtil.isEmpty(item)) continue
            val id = runCatching { item.definition?.identifier }.getOrNull() ?: continue
            if (id == identifier) return slot
        }
        return null
    }

    private fun findFreeOrMatchingSlot(identifier: String): Int? {
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot)
            if (item == null || InventoryUtil.isEmpty(item)) return slot
            val id = runCatching { item.definition?.identifier }.getOrNull()
            if (id == identifier) return slot
        }
        for (slot in InventoryUtil.INV_START..InventoryUtil.INV_END) {
            val item = EntityTracker.getInventoryItem(slot)
            if (item == null || InventoryUtil.isEmpty(item)) return slot
        }
        return null
    }

    private fun moveToFreeHotbarSlot(session: NexoraRelaySession, fromSlot: Int, identifier: String) {
        if (fromSlot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) return
        var destSlot = -1
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot)
            if (item == null || InventoryUtil.isEmpty(item)) { destSlot = slot; break }
        }
        if (destSlot < 0) return

        val sourceItem = EntityTracker.getInventoryItem(fromSlot) ?: return
        val destItem = EntityTracker.getInventoryItem(destSlot) ?: ItemData.AIR
        InventoryUtil.sendInventoryMove(
            session           = session,
            sourceContainer   = ContainerSlotType.HOTBAR_AND_INVENTORY,
            sourceContainerId = 0,
            sourceSlot        = fromSlot,
            sourceItem        = sourceItem,
            destContainer     = ContainerSlotType.HOTBAR_AND_INVENTORY,
            destContainerId   = 0,
            destSlot          = destSlot,
            destItem          = destItem
        )
    }

    private fun findBlockInHotbar(identifier: String): Int? {
        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull() ?: continue
            if (id == identifier) return slot
        }
        return null
    }

    private fun faceBlock(session: NexoraRelaySession, dx: Float, dz: Float) {
        val yaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val pos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)

        session.serverBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = pos
            rotation              = Vector3f.from(EntityTracker.selfPitch, yaw, yaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        })
        session.clientBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = pos
            rotation              = Vector3f.from(EntityTracker.selfPitch, yaw, yaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        })
        EntityTracker.selfYaw = yaw
    }

    private fun tryPlace(
        session: NexoraRelaySession,
        x: Int, y: Int, z: Int,
        hotbarSlot: Int
    ): Boolean {
        val heldItem = EntityTracker.getInventoryItem(hotbarSlot) ?: return false
        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType      = 0
                blockPosition   = Vector3i.from(x, y - 1, z)
                blockFace       = 1
                this.hotbarSlot = hotbarSlot
                itemInHand      = heldItem
                playerPosition  = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                clickPosition   = Vector3f.from(0.5f, 1.0f, 0.5f)
                triggerType               = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction  = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState       = 0
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    fun render(canvas: Canvas, width: Int, height: Int) {
        if (!showSchematic.value) return
        val grid = MapArtPlan.grid.value ?: return
        val size = grid.size
        if (size <= 0) return

        val cellPx = (SCHEMATIC_MAX_PX / size.toFloat()).coerceIn(2f, 16f)
        val gridPx = cellPx * size
        val left = width - gridPx - SCHEMATIC_MARGIN
        val top  = SCHEMATIC_MARGIN

        canvas.drawRect(
            left - 8f, top - 8f, left + gridPx + 8f, top + gridPx + 34f,
            schematicBgPaint
        )

        for (row in 0 until size) {
            for (col in 0 until size) {
                val id = grid[row][col]
                val placed = originSet &&
                    WorldBlockTracker.getBlockIdentifier(originX + col, originY, originZ + row) == id
                val argb = 0xFF000000.toInt() or BlockPalette.colorOf(id)
                schematicFillPaint.color = if (placed) dimColor(argb) else argb

                val x = left + col * cellPx
                val y = top + row * cellPx
                canvas.drawRect(x, y, x + cellPx, y + cellPx, schematicFillPaint)
            }
        }

        val target = queue.firstOrNull()
        if (target != null) {
            val (col, row) = target
            val x = left + col * cellPx
            val y = top + row * cellPx
            canvas.drawRect(x, y, x + cellPx, y + cellPx, schematicHighlightPaint)
        }

        val label = when {
            stage == Stage.COLLECTING ->
                "Collecting: ${collectingBlockType?.let { BlockPalette.displayName(it) } ?: "?"}"
            paused -> "Paused"
            currentBlockType != null ->
                "${BlockPalette.displayName(currentBlockType!!)} — ${queue.size} left"
            else -> "AutoMapArt"
        }
        canvas.drawText(label, left, top + gridPx + 24f, schematicTextPaint)
    }

    private fun dimColor(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb ushr 16) and 0xFF) / 3
        val g = ((argb ushr 8) and 0xFF) / 3
        val b = (argb and 0xFF) / 3
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun sendMessage(msg: String) {
        val session = PacketEventBus.currentSession ?: return
        try {
            session.clientBound(TextPacket().apply {
                type               = TextPacket.Type.SYSTEM
                isNeedsTranslation = false
                sourceName         = "__ox_internal__"
                xuid               = ""
                platformChatId     = ""
                setMessage(msg)
                setFilteredMessage("")
            })
        } catch (_: Exception) {}
    }

    private class PathNode(val x: Int, val y: Int, val z: Int) {
        var g: Int = 0
        var f: Double = 0.0
        var prev: PathNode? = null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PathNode) return false
            return x == other.x && y == other.y && z == other.z
        }

        override fun hashCode(): Int = (x * 31 + y) * 31 + z
    }

    private object MapArtPathfinder {
        private const val MAX_LENGTH = 200
        private const val TIMEOUT_MS = 5000L

        fun findPath(start: Vector3i, goal: Vector3i): List<Vector3i>? {
            if (start.x == goal.x && start.y == goal.y && start.z == goal.z) {
                return listOf(start)
            }

            val startNode = PathNode(start.x, start.y, start.z)
            val endNode   = PathNode(goal.x, goal.y, goal.z)

            val open   = HashSet<PathNode>()
            val closed = HashSet<PathNode>()
            open.add(startNode)

            val startTime = System.currentTimeMillis()

            while (open.isNotEmpty()) {
                if (System.currentTimeMillis() - startTime >= TIMEOUT_MS) return null

                val current = open.minByOrNull { it.f } ?: break
                open.remove(current)
                closed.add(current)

                val neighbors = neighborsOf(current, goal)
                if (current.g >= MAX_LENGTH || neighbors == null) {
                    return buildPath(current)
                }

                for (neighbor in neighbors) {
                    if (closed.contains(neighbor)) continue

                    val tentativeG = current.g + 1
                    val existing = open.firstOrNull { it == neighbor }

                    if (existing == null || tentativeG < existing.g) {
                        neighbor.g = tentativeG
                        neighbor.f = tentativeG + heuristic(neighbor, endNode)
                        neighbor.prev = current
                        if (existing != null) open.remove(existing)
                        open.add(neighbor)
                    }
                }
            }
            return null
        }

        private fun buildPath(node: PathNode): List<Vector3i> {
            val list = mutableListOf<Vector3i>()
            var cur: PathNode? = node
            while (cur != null) {
                list.add(Vector3i.from(cur.x, cur.y, cur.z))
                cur = cur.prev
            }
            return list.asReversed()
        }

        private fun heuristic(a: PathNode, b: PathNode): Double {
            val dx = (a.x - b.x).toDouble()
            val dy = (a.y - b.y).toDouble()
            val dz = (a.z - b.z).toDouble()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        private fun canWalk(x: Int, y: Int, z: Int): Boolean {
            if (!WorldBlockTracker.hasData(x, y, z)) return false
            val at    = WorldBlockTracker.getBlockIdentifier(x, y, z)     ?: return false
            val below = WorldBlockTracker.getBlockIdentifier(x, y - 1, z) ?: return false
            val above = WorldBlockTracker.getBlockIdentifier(x, y + 1, z) ?: return false
            return at == AIR && below != AIR && above == AIR
        }

        private fun neighborsOf(node: PathNode, goal: Vector3i): List<PathNode>? {
            val neighbors = mutableListOf<PathNode>()

            for (dx in -1..1) {
                for (dz in -1..1) {
                    if (!WorldBlockTracker.hasData(node.x + dx, node.y, node.z + dz)) return null

                    for (dy in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue

                        val nx = node.x + dx
                        val ny = node.y + dy
                        val nz = node.z + dz

                        if (goal.x == nx && goal.y == ny && goal.z == nz) return null

                        if (!canWalk(nx, ny, nz)) continue

                        if (dx != 0 && dz != 0) {
                            if (!canWalk(node.x, ny, nz) || !canWalk(nx, ny, node.z)) continue
                        }

                        neighbors.add(PathNode(nx, ny, nz))
                    }
                }
            }
            return neighbors
        }
    }

    private inner class MapArtPathRunner(start: Vector3i, private val goal: Vector3i) {
        private companion object { const val PATH_FOLLOWING_TIMEOUT = 200 }

        var active = true
            private set

        private var path: List<Vector3i>? = null
        private var current = 0
        private var timeoutCounter = 0

        init { path = generatePath(start) }

        private fun generatePath(from: Vector3i): List<Vector3i>? {
            val p = MapArtPathfinder.findPath(from, goal)
            current = 0
            active = p != null
            return p
        }

        fun getPath(): List<Vector3i>? = path

        fun run(session: NexoraRelaySession): Boolean {
            if (!active) return false
            val p = path ?: return false
            if (p.isEmpty()) return checkDone(session)

            timeoutCounter++
            if (timeoutCounter >= PATH_FOLLOWING_TIMEOUT) {
                path = generatePath(currentFeet())
                timeoutCounter = 0
                return false
            }

            if (current >= p.size) return checkDone(session)

            val node = p[current]
            val curX = EntityTracker.selfX
            val curZ = EntityTracker.selfZ
            val dx = (node.x + 0.5f) - curX
            val dz = (node.z + 0.5f) - curZ
            val dist = sqrt(dx * dx + dz * dz)

            if (dist <= NODE_ARRIVE_DIST) {
                timeoutCounter = 0
                current++
                if (current >= p.size) return checkDone(session)
            }

            moveTowardNode(session, p[current])
            return false
        }

        private fun checkDone(session: NexoraRelaySession): Boolean {
            val feet = currentFeet()
            if (feet.x == goal.x && feet.z == goal.z) return true
            path = generatePath(feet)
            return false
        }

        private fun currentFeet(): Vector3i = Vector3i.from(
            floor(EntityTracker.selfX).toInt(),
            floor(EntityTracker.selfY - 1f).toInt(),
            floor(EntityTracker.selfZ).toInt()
        )

        private fun moveTowardNode(session: NexoraRelaySession, node: Vector3i) {
            val curX = EntityTracker.selfX
            val curZ = EntityTracker.selfZ
            val dxRaw = (node.x + 0.5f) - curX
            val dzRaw = (node.z + 0.5f) - curZ
            val dist = sqrt(dxRaw * dxRaw + dzRaw * dzRaw).coerceAtLeast(0.0001f)

            val step = STEP_SIZE.coerceAtMost(dist)
            val nx = curX + (dxRaw / dist) * step
            val nz = curZ + (dzRaw / dist) * step
            val ny = node.y + 1f

            val pos = Vector3f.from(nx, ny, nz)
            val yaw = Math.toDegrees(atan2(-dxRaw.toDouble(), dzRaw.toDouble())).toFloat()

            session.serverBound(MovePlayerPacket().apply {
                runtimeEntityId       = EntityTracker.selfRuntimeId
                position              = pos
                rotation              = Vector3f.from(EntityTracker.selfPitch, yaw, yaw)
                mode                  = MovePlayerPacket.Mode.NORMAL
                isOnGround            = true
                ridingRuntimeEntityId = 0L
            })
            session.clientBound(MovePlayerPacket().apply {
                runtimeEntityId       = EntityTracker.selfRuntimeId
                position              = pos
                rotation              = Vector3f.from(EntityTracker.selfPitch, yaw, yaw)
                mode                  = MovePlayerPacket.Mode.NORMAL
                isOnGround            = true
                ridingRuntimeEntityId = 0L
            })

            EntityTracker.selfX = nx
            EntityTracker.selfY = ny
            EntityTracker.selfZ = nz
            EntityTracker.selfYaw = yaw
        }
    }
}
