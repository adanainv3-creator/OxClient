package com.nexoraclient.module.movement

import com.nexoraclient.config.MapArtPlan
import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.core.relay.NexoraRelaySession
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.BaseModule
import com.nexoraclient.module.ModuleCategory
import com.nexoraclient.utils.BlockPalette
import com.nexoraclient.utils.InventoryUtil
import com.nexoraclient.utils.WorldBlockTracker
import kotlinx.coroutines.Job
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * AutoMapArt — referans Meteor Client modülündeki (AutoMapArt.java / Astar.java /
 * PathRunner.java / AutoMapArtUtils.java) mantığın Bedrock relay ortamına portu.
 *
 * Orijinaldeki litematica/chest/waste-dump sistemi bu projede yok (proxy bot, gerçek
 * envanter chest etkileşimi yapmıyor) — o kısımlar yerine `skipMissing` + pause akışı
 * kullanılıyor. Geri kalan her şey (gerçek A* pathfinding, blok-tipine-göre-gruplu
 * inşa sırası, yerleştirme retry/cooldown, blacklist, rotateToPlace, pause/unpause/skip
 * public API'si) referans dosyalardaki mantığın birebir karşılığı.
 */
class AutoMapArt : BaseModule(
    name        = "AutoMapArt",
    category    = ModuleCategory.MOVEMENT,
    description = "Analiz edilen görseli yere pixel art olarak otomatik döşer"
) {
    companion object {
        private const val TICK_INTERVAL_MS      = 50L
        private const val STEP_SIZE             = 0.22f
        private const val NODE_ARRIVE_DIST      = 0.35f
        private const val SCAN_INTERVAL_TICKS   = 20   // 1 saniye
        private const val MAX_BLOCK_PLACE_ATTEMPTS = 10 // Astar/PathRunner referansındaki MAX_BLOCK_PLACE_ATTEMPTS
        private const val AIR = "minecraft:air"
    }

    // ---------------------------------------------------------------------
    // Settings (AutoMapArt.java'daki sgGeneral karşılığı)
    // ---------------------------------------------------------------------
    private val skipMissing       = bool("Skip Missing Block", true)
    val autoScanInventory         = bool("Auto Scan Inventory", false)
    private val rotateToPlace     = bool("Rotate Towards", true)                 // rotateToPlace
    private val placementDelay    = int("Place Delay (ticks)", 5, 0, 40)         // PlacementDelay
    private val placementRange    = int("Place Range (blocks)", 3, 1, 8)         // PlacementRange
    private val blacklistedBlocksSetting = string("Blacklisted Blocks", "")      // blackListedBlocks (virgülle ayrılmış id listesi)

    // ---------------------------------------------------------------------
    // Runtime state
    // ---------------------------------------------------------------------
    private var tickJob: Job? = null

    private var originSet         = false
    private var originX           = 0
    private var originY           = 0   // zemin Y'si (player duracağı yer)
    private var originZ           = 0

    private var lastGridRef: Array<Array<String>>? = null

    // stage.BUILDING / paused karşılığı
    @Volatile private var paused  = false

    // GetNextBlocks() mantığı: bir blok tipini bitirmeden diğerine geçmiyoruz
    private val completedTypes    = mutableSetOf<String>()
    private var currentBlockType: String? = null
    private val queue             = ArrayDeque<Pair<Int, Int>>() // (col, row)

    // Build() içindeki placeTimer / currentFailedAttempts karşılığı
    private var placeTimer        = 0
    private var currentFailedAttempts = 0

    private var pathRunner: MapArtPathRunner? = null

    private var scanTick          = 0

    // Available block filter (overlay panelden veya auto scan ile set edilir)
    @Volatile var availableBlocks: Set<String> = emptySet()
        set(value) {
            val changed = field != value
            field = value
            if (changed) MapArtPlan.reanalyze(value)
        }

    private val allPaletteIds: Set<String> by lazy {
        BlockPalette.ALL.map { it.identifier }.toHashSet()
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onEnable() {
        super.onEnable()

        if (MapArtPlan.grid.value == null) {
            sendMessage("§eAutoMapArt: Henüz resim analiz edilmedi. Dashboard > Configs > AutoMapArt'tan resim seç.")
        }
        if (!WorldBlockTracker.hasAnyTerrainData()) {
            sendMessage("§eAutoMapArt: Chunk verisi yok — sunucuya bağlı ve dünyada mısın?")
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

        tickJob?.cancel()
        tickJob = launchTickLoop(TICK_INTERVAL_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        pathRunner = null
        super.onDisable()
    }

    // -------------------------------------------------------------------------
    // Public API (overlay panel ve komutlar kullanır — PauseAutoMapArt /
    // UnpauseAutoMapArt / SkipBlocksAutoMapArt referanslarının karşılığı)
    // -------------------------------------------------------------------------

    fun isPaused(): Boolean = paused

    fun pause() {
        if (paused) { sendMessage("§eAutoMapArt zaten duraklatılmış"); return }
        paused = true
        sendMessage("§eAutoMapArt duraklatıldı — devam etmek için unpause")
    }

    fun unpause() {
        if (!paused) { sendMessage("§eAutoMapArt zaten çalışıyor"); return }
        paused = false
        sendMessage("§aAutoMapArt devam ediyor")
    }

    /** SkipBlocksAutoMapArt referansındaki skipCurrentBlock() karşılığı */
    fun skipCurrentBlock() {
        if (paused) { sendMessage("§eAutoMapArt duraklatılmış"); return }
        currentBlockType?.let { completedTypes.add(it) }
        currentBlockType = null
        queue.clear()
        pathRunner = null
        currentFailedAttempts = 0
        sendMessage("§eMevcut blok tipi atlanıyor")
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

    // -------------------------------------------------------------------------
    // Tick — AutoMapArt.java'daki onTick()/Build() karşılığı
    // -------------------------------------------------------------------------

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

        // Resim değiştiyse (yeniden analiz) ilerlemeyi sıfırla
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
            sendMessage("§aAutoMapArt başladı — origin: ($originX, $originY, $originZ)")
        }

        // GetNextBlocks(): mevcut tip bittiyse yeni tip seç
        if (currentBlockType == null || queue.isEmpty()) {
            if (queue.isEmpty() && !assignNextBlockType(grid)) {
                sendMessage("§aAutoMapArt tamamlandı!")
                setEnabled(false)
                return
            }
            if (queue.isEmpty()) return // tip seçildi ama tüm hücreler zaten doğruydu → sonraki tick
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

        // Chunk yüklü değilse atla
        if (!WorldBlockTracker.hasData(targetX, targetY, targetZ)) {
            queue.removeFirst()
            return
        }

        // blockAlreadyThere()
        if (WorldBlockTracker.getBlockIdentifier(targetX, targetY, targetZ) == blockId) {
            queue.removeFirst()
            pathRunner = null
            return
        }

        // Hotbar'da blok var mı? (chest sistemi yok → skipMissing / pause)
        val hotbarSlot = findBlockInHotbar(blockId)
        if (hotbarSlot == null) {
            if (skipMissing.value) {
                queue.removeFirst()
            } else {
                pause()
                sendMessage("§cEksik blok: ${BlockPalette.displayName(blockId)} — envanteri doldur ve .unpause yaz")
            }
            return
        }

        // Goal.TravelTo(): PlacementRange içindeyse direkt yerleştirmeye geç,
        // değilse A* ile hedefe git (Astar.java / PathRunner.java karşılığı)
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
                sendMessage("§cPathfinding başarısız: ($targetX, $targetY, $targetZ) — blok atlanıyor")
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
                queue.addLast(failed) // daha sonra tekrar dene
            }
        }
    }

    // -------------------------------------------------------------------------
    // GetNextBlocks() karşılığı — blok tipine göre gruplu inşa sırası
    // -------------------------------------------------------------------------

    /** true dönerse yeni bir blok tipi bulundu ve queue dolduruldu. false → resim tamamlandı. */
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
                ) continue // zaten doğru

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
            return assignNextBlockType(grid) // bu tip zaten tamamdı, sıradakine geç
        }

        sendMessage("§bŞimdi döşeniyor: ${BlockPalette.displayName(type)} (${queue.size} blok)")
        return true
    }

    private fun parseBlacklist(): Set<String> =
        blacklistedBlocksSetting.value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun findBlockInHotbar(identifier: String): Int? {
        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull() ?: continue
            if (id == identifier) return slot
        }
        return null
    }

    /** AutoMapArtUtils.lookTowardBlock() karşılığı — bloğa dönük yaw hesaplayıp gönderir */
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

    /**
     * Blok yerleştirme. Zemin bloğunun ÜSTÜNE (targetX, targetY-1, targetZ) face=UP ile.
     */
    private fun tryPlace(
        session: NexoraRelaySession,
        x: Int, y: Int, z: Int,
        hotbarSlot: Int
    ): Boolean {
        val heldItem = EntityTracker.getInventoryItem(hotbarSlot) ?: return false
        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType      = 0            // place
                blockPosition   = Vector3i.from(x, y - 1, z)
                blockFace       = 1             // UP yüzey
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

    /**
     * Overlay veya tick loop mesajlarını sadece lokal (kendi) chat ekranına basar.
     * ChatSpammer'daki gibi TextPacket üretilir ama sunucuya değil, clientBound
     * ile sadece oyuncunun kendi cihazına gönderilir — sunucu chat'ine spam atmaz.
     * Session yoksa sessizce yutulur.
     */
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

    // =========================================================================
    // Astar.java / PathRunner.java portu
    // =========================================================================

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

    /** Astar.java portu — WorldBlockTracker üzerinden yürünebilirlik kontrolü yapar */
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

        /** AutoMapArtUtils.canWalk() karşılığı: ayak seviyesi boş, altı dolu, üstü boş */
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

    /**
     * PathRunner.java portu. Gerçek key-press simülasyonu yerine (bu proxy'de gerçek
     * bir client input'u yok) her tick MovePlayerPacket ile path node'una doğru
     * ilerletir — mantık (timeout'ta yeniden pathfind, goal'a varınca dur) birebir aynı.
     */
    private class MapArtPathRunner(start: Vector3i, private val goal: Vector3i) {
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

        /** Tek tick ilerleme. true dönerse goal'a ulaşıldı. */
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
            // Zıplama fiziği yok (proxy bot) — hedef node'un Y'sine direkt hizalanır
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
