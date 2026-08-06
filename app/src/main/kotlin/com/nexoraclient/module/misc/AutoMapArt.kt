package com.rubidiumclient.module.misc

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.rubidiumclient.config.MapArtPlan
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.BlockPalette
import com.rubidiumclient.utils.BlockTracker
import com.rubidiumclient.utils.GameFov
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.WorldBlockTracker
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
        // FIX: eskiden onEnable()'da ANINDA hasAnyTerrainData() kontrol edilip
        // "No chunk data" uyarısı basılıyordu. Chunk verisi sunucudan ASENKRON
        // akıyor — dünyaya yeni girildiyse/modül bağlantı sonrası hemen
        // açıldıysa terrain data henüz hiç gelmemiş olabilir (bu normal),
        // ama uyarı "bağlantı sorunu var" gibi görünüyordu. tick() zaten
        // veriyi sessizce bekliyor (satır ~287) — asıl kod hiçbir zaman
        // kırılmıyordu, sadece mesaj yanlış zamanda/yanlış yorumla çıkıyordu.
        // Artık gerçekten kalıcı bir sorun (5 saniye sonra hâlâ veri yoksa)
        // varsa TEK SEFER uyarıyoruz.
        private const val CHUNK_DATA_GRACE_MS = 5000L

        private const val SCHEMATIC_MAX_PX  = 240f
        private const val SCHEMATIC_MARGIN  = 28f
        private const val ISO_CELL_MIN = 3f
        private const val ISO_CELL_MAX = 18f
        private const val GHOST_ALPHA        = 130
        private const val GHOST_TARGET_ALPHA = 235
        private const val BOUNDARY_POST_HEIGHT = 4f
        private const val BOUNDARY_COLOR = 0xFF00E5FF.toInt()
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
    private val schematicBarBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x55FFFFFF
        isAntiAlias = false
    }
    private val schematicBarFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFF4CAF50.toInt()
        isAntiAlias = false
    }
    private val worldGhostStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val worldGhostFillPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val skipMissing       = bool("Skip Missing Block", true)
    val autoScanInventory         = bool("Auto Scan Inventory", false)
    private val rotateToPlace     = bool("Rotate Towards", true)
    private val placementDelay    = int("Place Delay (ticks)", 5, 0, 40)
    private val placementRange    = int("Place Range (blocks)", 3, 1, 8)
    private val blacklistedBlocksSetting = string("Blacklisted Blocks", "")
    private val autoCollectMaterials = bool("Auto Collect Materials", true)
    private val chestSearchRange  = int("Chest Search Range (blocks)", 24, 4, 64)
    private val showSchematic     = bool("Show Schematic", true)
    private val worldOverlay         = bool ("World Overlay",          true)
    private val worldRenderDistance  = float("World Render Distance",  48f, 8f, 128f)

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

    private var enabledAtMs = 0L
    private var chunkWarningShown = false

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
        enabledAtMs = System.currentTimeMillis()
        chunkWarningShown = false

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
            val id = InventoryUtil.resolveIdentifier(item) ?: continue
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
            if (!WorldBlockTracker.hasAnyTerrainData()) {
                if (!chunkWarningShown && System.currentTimeMillis() - enabledAtMs >= CHUNK_DATA_GRACE_MS) {
                    chunkWarningShown = true
                    sendMessage("§eAutoMapArt: No chunk data — are you connected and in a world?")
                }
                return
            }
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

    private fun collectMaterials(session: RubidiumRelaySession) {
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
                InventoryUtil.resolveIdentifier(item) == type
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
            sourceContainer   = ContainerSlotType.LEVEL_ENTITY,
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

    private fun sendOpenContainerInteract(session: RubidiumRelaySession, pos: Vector3i) {
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

    private fun closeContainer(session: RubidiumRelaySession) {
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
            val id = InventoryUtil.resolveIdentifier(item) ?: continue
            if (id == identifier) return slot
        }
        return null
    }

    private fun findFreeOrMatchingSlot(identifier: String): Int? {
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
            val item = EntityTracker.getInventoryItem(slot)
            if (item == null || InventoryUtil.isEmpty(item)) return slot
            val id = InventoryUtil.resolveIdentifier(item)
            if (id == identifier) return slot
        }
        for (slot in InventoryUtil.INV_START..InventoryUtil.INV_END) {
            val item = EntityTracker.getInventoryItem(slot)
            if (item == null || InventoryUtil.isEmpty(item)) return slot
        }
        return null
    }

    private fun moveToFreeHotbarSlot(session: RubidiumRelaySession, fromSlot: Int, identifier: String) {
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
            val id = InventoryUtil.resolveIdentifier(item) ?: continue
            if (id == identifier) return slot
        }
        return null
    }

    private fun faceBlock(session: RubidiumRelaySession, dx: Float, dz: Float) {
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
        session: RubidiumRelaySession,
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
        renderWorldBoundary(canvas, width, height)
        renderWorldGhosts(canvas, width, height)
        renderCornerPanel(canvas, width, height)
    }

    // World Render Distance disinda kalindiginda bile semanin nerede oldugunu
    // uzaktan gorebilmek icin — insaatin dis cercevesini + kose isaret
    // direklerini dunya uzayinda cizer. MathUtil.worldToScreen ile ayni
    // projeksiyon, mesafe siniri yok (kose gorunur oldugu surece cizilir).
    private fun renderWorldBoundary(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!worldOverlay.value) return
        if (!originSet) return
        val grid = MapArtPlan.grid.value ?: return
        val size = grid.size
        if (size <= 0) return

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ
        val yaw   = EntityTracker.selfYaw
        val pitch = EntityTracker.selfPitch
        val fovValue = GameFov.current

        val x0 = originX.toFloat()
        val x1 = (originX + size).toFloat()
        val z0 = originZ.toFloat()
        val z1 = (originZ + size).toFloat()
        val yBase = originY.toFloat()
        val yPost = yBase + BOUNDARY_POST_HEIGHT

        val groundCorners = arrayOf(
            floatArrayOf(x0, yBase, z0),
            floatArrayOf(x1, yBase, z0),
            floatArrayOf(x1, yBase, z1),
            floatArrayOf(x0, yBase, z1)
        )
        val postTops = arrayOf(
            floatArrayOf(x0, yPost, z0),
            floatArrayOf(x1, yPost, z0),
            floatArrayOf(x1, yPost, z1),
            floatArrayOf(x0, yPost, z1)
        )

        val screenGround = arrayOfNulls<FloatArray>(4)
        val screenPost    = arrayOfNulls<FloatArray>(4)
        var anyVisible = false

        for (i in 0..3) {
            val g = groundCorners[i]
            val p = MathUtil.worldToScreen(g[0], g[1], g[2], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fovValue)
            if (p != null) { screenGround[i] = floatArrayOf(p.first, p.second); anyVisible = true }

            val t = postTops[i]
            val pt = MathUtil.worldToScreen(t[0], t[1], t[2], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fovValue)
            if (pt != null) screenPost[i] = floatArrayOf(pt.first, pt.second)
        }
        if (!anyVisible) return

        boundaryPaint.color = BOUNDARY_COLOR
        boundaryPaint.alpha = 220
        boundaryPaint.strokeWidth = 2.5f

        for (i in 0..3) {
            val a = screenGround[i] ?: continue
            val b = screenGround[(i + 1) % 4] ?: continue
            canvas.drawLine(a[0], a[1], b[0], b[1], boundaryPaint)
        }
        for (i in 0..3) {
            val a = screenGround[i] ?: continue
            val b = screenPost[i] ?: continue
            canvas.drawLine(a[0], a[1], b[0], b[1], boundaryPaint)
        }
    }

    // Litematica tarzi: henuz konulmamis bloklari, gercek dunya konumlarinda
    // (MathUtil.worldToScreen ile ESP.kt'deki drawBox3D'nin ayni projeksiyonu
    // kullanilarak) yari saydam hayalet kup olarak ekrana basar.
    private fun renderWorldGhosts(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!worldOverlay.value) return
        if (!originSet) return
        val grid = MapArtPlan.grid.value ?: return
        val size = grid.size
        if (size <= 0) return

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ
        val yaw   = EntityTracker.selfYaw
        val pitch = EntityTracker.selfPitch
        val fovValue = GameFov.current
        val range = worldRenderDistance.value

        val colMin = floor(selfX - range - originX).toInt().coerceAtLeast(0)
        val colMax = kotlin.math.ceil(selfX + range - originX).toInt().coerceAtMost(size - 1)
        val rowMin = floor(selfZ - range - originZ).toInt().coerceAtLeast(0)
        val rowMax = kotlin.math.ceil(selfZ + range - originZ).toInt().coerceAtMost(size - 1)
        if (colMin > colMax || rowMin > rowMax) return

        val target = queue.firstOrNull()
        val rangeSq = range * range

        val cells = ArrayList<GhostCell>()
        for (row in rowMin..rowMax) {
            for (col in colMin..colMax) {
                val id = grid[row][col]
                val wx = originX + col
                val wz = originZ + row
                val dx = (wx + 0.5f) - selfX
                val dz = (wz + 0.5f) - selfZ
                val distSq = dx * dx + dz * dz
                if (distSq > rangeSq) continue

                if (WorldBlockTracker.getBlockIdentifier(wx, originY, wz) == id) continue

                val isTarget = target != null && target.first == col && target.second == row
                cells.add(GhostCell(row, col, id, distSq, isTarget))
            }
        }
        if (cells.isEmpty()) return

        // Uzaktan yakina ciz — yakin kupler uzaktakilerin ustune dogru binsin.
        cells.sortByDescending { it.distSq }

        for (cell in cells) {
            val colorArgb = 0xFF000000.toInt() or BlockPalette.colorOf(cell.id)
            drawWorldGhostBlock(
                canvas,
                originX + cell.col, originY, originZ + cell.row,
                selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fovValue,
                colorArgb, cell.isTarget
            )
        }
    }

    private class GhostCell(val row: Int, val col: Int, val id: String, val distSq: Float, val isTarget: Boolean)

    private fun drawWorldGhostBlock(
        canvas: Canvas,
        bx: Int, blockY: Int, bz: Int,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int, fovValue: Float,
        colorArgb: Int, isTarget: Boolean
    ) {
        val x0 = bx.toFloat(); val x1 = bx + 1f
        val y0 = blockY.toFloat(); val y1 = blockY + 1f
        val z0 = bz.toFloat(); val z1 = bz + 1f

        val worldCorners = arrayOf(
            floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0),
            floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1),
            floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0),
            floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1)
        )

        val screenCorners = arrayOfNulls<FloatArray>(8)
        for (i in worldCorners.indices) {
            val c = worldCorners[i]
            val p = MathUtil.worldToScreen(
                c[0], c[1], c[2], selfX, selfY, selfZ, yaw, pitch, screenW, screenH, fovValue
            ) ?: return
            screenCorners[i] = floatArrayOf(p.first, p.second)
        }

        worldGhostStrokePaint.color = colorArgb
        worldGhostStrokePaint.alpha = if (isTarget) 255 else 150
        worldGhostStrokePaint.strokeWidth = if (isTarget) 3f else 1.5f

        val edges = intArrayOf(
            0, 1,  1, 2,  2, 3,  3, 0,
            4, 5,  5, 6,  6, 7,  7, 4,
            0, 4,  1, 5,  2, 6,  3, 7
        )
        var i = 0
        while (i < edges.size) {
            val a = screenCorners[edges[i]]!!
            val b = screenCorners[edges[i + 1]]!!
            canvas.drawLine(a[0], a[1], b[0], b[1], worldGhostStrokePaint)
            i += 2
        }

        worldGhostFillPaint.color = colorArgb
        worldGhostFillPaint.alpha = if (isTarget) 130 else 60
        val topPath = Path().apply {
            val p4 = screenCorners[4]!!; moveTo(p4[0], p4[1])
            val p5 = screenCorners[5]!!; lineTo(p5[0], p5[1])
            val p6 = screenCorners[6]!!; lineTo(p6[0], p6[1])
            val p7 = screenCorners[7]!!; lineTo(p7[0], p7[1])
            close()
        }
        canvas.drawPath(topPath, worldGhostFillPaint)
    }

    private fun renderCornerPanel(canvas: Canvas, width: Int, height: Int) {
        if (!showSchematic.value) return
        val grid = MapArtPlan.grid.value ?: return
        val size = grid.size
        if (size <= 0) return

        val cellPx = (SCHEMATIC_MAX_PX / size.toFloat()).coerceIn(ISO_CELL_MIN, ISO_CELL_MAX)
        val tileW  = cellPx
        val tileH  = cellPx * 0.5f
        val blockH = cellPx * 0.45f

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (row in 0 until size) {
            for (col in 0 until size) {
                val ix = (col - row) * (tileW / 2f)
                val iy = (col + row) * (tileH / 2f)
                if (ix - tileW / 2f < minX) minX = ix - tileW / 2f
                if (ix + tileW / 2f > maxX) maxX = ix + tileW / 2f
                if (iy - tileH / 2f < minY) minY = iy - tileH / 2f
                if (iy + tileH / 2f + blockH > maxY) maxY = iy + tileH / 2f + blockH
            }
        }
        val isoW = maxX - minX
        val isoH = maxY - minY

        val panelLeft = width - isoW - SCHEMATIC_MARGIN
        val panelTop  = SCHEMATIC_MARGIN

        canvas.drawRect(
            panelLeft - 10f, panelTop - 10f,
            panelLeft + isoW + 10f, panelTop + isoH + 46f,
            schematicBgPaint
        )

        val order = ArrayList<Pair<Int, Int>>(size * size)
        for (row in 0 until size) for (col in 0 until size) order.add(row to col)
        order.sortWith(compareBy({ it.first + it.second }, { it.first }))

        val target = queue.firstOrNull()
        var placedCount = 0
        val total = size * size

        for ((row, col) in order) {
            val id = grid[row][col]
            val placed = originSet &&
                WorldBlockTracker.getBlockIdentifier(originX + col, originY, originZ + row) == id
            if (placed) placedCount++

            val baseColor = 0xFF000000.toInt() or BlockPalette.colorOf(id)
            val isTarget  = target != null && target.first == col && target.second == row

            val cx = panelLeft - minX + (col - row) * (tileW / 2f)
            val cy = panelTop  - minY + (col + row) * (tileH / 2f)

            if (placed) {
                drawTopFaceColor(canvas, cx, cy, tileW, tileH, dimColor(baseColor))
            } else {
                val alpha = if (isTarget) GHOST_TARGET_ALPHA else GHOST_ALPHA
                drawIsoGhostCube(canvas, cx, cy, tileW, tileH, blockH, baseColor, alpha)
                if (isTarget) {
                    drawTopFaceOutline(canvas, cx, cy, tileW, tileH, schematicHighlightPaint)
                }
            }
        }

        val progress = if (total > 0) placedCount.toFloat() / total.toFloat() else 0f
        val barY = panelTop + isoH + 18f
        canvas.drawRect(panelLeft, barY, panelLeft + isoW, barY + 8f, schematicBarBgPaint)
        canvas.drawRect(panelLeft, barY, panelLeft + isoW * progress, barY + 8f, schematicBarFillPaint)

        val label = when {
            stage == Stage.COLLECTING ->
                "Collecting: ${collectingBlockType?.let { BlockPalette.displayName(it) } ?: "?"}"
            paused -> "Paused — $placedCount/$total"
            currentBlockType != null ->
                "${BlockPalette.displayName(currentBlockType!!)} — $placedCount/$total"
            else -> "AutoMapArt — $placedCount/$total"
        }
        canvas.drawText(label, panelLeft, barY + 28f, schematicTextPaint)
    }

    // Ust yuz (dama tasi) diamond path'i — placed bloklar icin duz zemin karosu
    private fun topFacePath(cx: Float, cy: Float, tileW: Float, tileH: Float): Path = Path().apply {
        moveTo(cx, cy - tileH / 2f)
        lineTo(cx + tileW / 2f, cy)
        lineTo(cx, cy + tileH / 2f)
        lineTo(cx - tileW / 2f, cy)
        close()
    }

    private fun drawTopFaceColor(canvas: Canvas, cx: Float, cy: Float, tileW: Float, tileH: Float, colorArgb: Int) {
        schematicFillPaint.color = colorArgb
        canvas.drawPath(topFacePath(cx, cy, tileW, tileH), schematicFillPaint)
    }

    private fun drawTopFaceOutline(canvas: Canvas, cx: Float, cy: Float, tileW: Float, tileH: Float, paint: Paint) {
        canvas.drawPath(topFacePath(cx, cy, tileW, tileH), paint)
    }

    // Henuz konulmamis bloklari litematica tarzi yari saydam "hayalet" kup
    // olarak (ust + iki yan yuz, golgelendirilmis) ciziyor.
    private fun drawIsoGhostCube(
        canvas: Canvas, cx: Float, cy: Float,
        tileW: Float, tileH: Float, blockH: Float,
        argb: Int, alpha: Int
    ) {
        val left = Path().apply {
            moveTo(cx - tileW / 2f, cy)
            lineTo(cx, cy + tileH / 2f)
            lineTo(cx, cy + tileH / 2f + blockH)
            lineTo(cx - tileW / 2f, cy + blockH)
            close()
        }
        val right = Path().apply {
            moveTo(cx, cy + tileH / 2f)
            lineTo(cx + tileW / 2f, cy)
            lineTo(cx + tileW / 2f, cy + blockH)
            lineTo(cx, cy + tileH / 2f + blockH)
            close()
        }

        schematicFillPaint.color = shade(argb, 0.55f, alpha)
        canvas.drawPath(left, schematicFillPaint)
        schematicFillPaint.color = shade(argb, 0.75f, alpha)
        canvas.drawPath(right, schematicFillPaint)
        schematicFillPaint.color = shade(argb, 1.0f, alpha)
        canvas.drawPath(topFacePath(cx, cy, tileW, tileH), schematicFillPaint)
    }

    private fun shade(argb: Int, factor: Float, alpha: Int): Int {
        val r = (((argb ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((argb ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
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
        private val PATH_FOLLOWING_TIMEOUT = 200

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

        fun run(session: RubidiumRelaySession): Boolean {
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

        private fun checkDone(session: RubidiumRelaySession): Boolean {
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

        private fun moveTowardNode(session: RubidiumRelaySession, node: Vector3i) {
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
