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
import kotlin.math.floor
import kotlin.math.sqrt

class AutoMapArt : BaseModule(
    name        = "AutoMapArt",
    category    = ModuleCategory.MOVEMENT,
    description = "Analiz edilen görseli yere pixel art olarak otomatik döşer"
) {
    companion object {
        private const val TICK_INTERVAL_MS  = 50L
        private const val STEP_SIZE         = 0.22f
        private const val ARRIVE_DIST       = 0.40f
        private const val SCAN_INTERVAL_TICKS = 20   // 1 saniye
    }

    // Settings
    private val skipMissing       = bool("Skip Missing Block", true)
    val autoScanInventory         = bool("Auto Scan Inventory", false)

    // Runtime state
    private var tickJob: Job?     = null
    private var started           = false
    private var targets: List<Triple<Int, Int, String>> = emptyList()
    private var index             = 0
    private var originX           = 0
    private var originY           = 0   // zemin Y'si (player duracağı yer)
    private var originZ           = 0
    private var missingCount      = 0
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

        // Resim seçilmemişse HATA VER ama modülü kapatma — kullanıcı seçebilsin
        if (MapArtPlan.grid.value == null) {
            sendMessage("§eAutoMapArt: Henüz resim analiz edilmedi. Dashboard > Configs > AutoMapArt'tan resim seç.")
            // Modülü açık bırak, tick içinde de kontrol edilecek
        }

        // chunk verisi yoksa uyar
        if (!WorldBlockTracker.hasAnyTerrainData()) {
            sendMessage("§eAutoMapArt: Chunk verisi yok — sunucuya bağlı ve dünyada mısın?")
        }

        started = false
        index   = 0
        missingCount = 0
        scanTick = 0
        targets  = emptyList()
        tickJob?.cancel()
        tickJob  = launchTickLoop(TICK_INTERVAL_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        super.onDisable()
    }

    // -------------------------------------------------------------------------
    // Public API (overlay panel kullanır)
    // -------------------------------------------------------------------------

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
    // Tick
    // -------------------------------------------------------------------------

    private fun tick() {
        // Auto scan
        if (autoScanInventory.value) {
            scanTick++
            if (scanTick >= SCAN_INTERVAL_TICKS) {
                scanTick = 0
                val scanned = scanInventoryBlocks()
                if (scanned != availableBlocks) availableBlocks = scanned
            }
        }

        val session = PacketEventBus.currentSession ?: return

        // Grid kontrolü — null ise sessizce bekle (modülü kapatma!)
        val grid = MapArtPlan.grid.value ?: return

        // İlk tick: origin ve target listesini kur
        if (!started) {
            if (!WorldBlockTracker.hasAnyTerrainData()) return  // chunk yüklenene kadar bekle

            // selfY'dan zemin bulunur: player ayağı = selfY - 1 (Bedrock player height offset)
            originX = floor(EntityTracker.selfX).toInt()
            originY = floor(EntityTracker.selfY - 1f).toInt()   // zemin bloğunun Y'si
            originZ = floor(EntityTracker.selfZ).toInt()
            targets = buildSnakeOrder(grid)
            index   = 0
            missingCount = 0
            started = true
            sendMessage("§aAutoMapArt başladı — ${targets.size} blok, origin: ($originX, $originY, $originZ)")
        }

        if (index >= targets.size) {
            sendMessage("§aAutoMapArt tamamlandı!")
            setEnabled(false)
            return
        }

        val (col, row, blockId) = targets[index]
        val targetX = originX + col
        val targetZ = originZ + row
        val targetY = originY   // flat zemin varsayımı

        // Chunk yüklü değilse o bloğu atla
        if (!WorldBlockTracker.hasData(targetX, targetY, targetZ)) {
            index++
            return
        }

        // Blok zaten doğruysa atla
        val existing = WorldBlockTracker.getBlockIdentifier(targetX, targetY, targetZ)
        if (existing == blockId) {
            index++
            return
        }

        // Hedefe git
        val curX = EntityTracker.selfX
        val curZ = EntityTracker.selfZ
        val dx   = (targetX + 0.5f) - curX
        val dz   = (targetZ + 0.5f) - curZ
        val dist = sqrt(dx * dx + dz * dz)

        if (dist > ARRIVE_DIST) {
            moveToward(session, curX, curZ, dx, dz, dist)
            return
        }

        // Hotbar'da blok ara
        val hotbarSlot = findBlockInHotbar(blockId)
        if (hotbarSlot == null) {
            missingCount++
            if (!skipMissing.value && missingCount > 3) {
                sendMessage("§cAutoMapArt: Çok fazla eksik blok ($blockId). Durduruluyor.")
                setEnabled(false)
                return
            }
            index++
            return
        }

        // Seç ve yerleştir
        InventoryUtil.sendHotbarSelect(session, hotbarSlot)
        EntityTracker.selfHotbarSlot = hotbarSlot

        if (tryPlace(session, targetX, targetY, targetZ, hotbarSlot)) {
            index++
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildSnakeOrder(grid: Array<Array<String>>): List<Triple<Int, Int, String>> {
        val size = grid.size
        val list = mutableListOf<Triple<Int, Int, String>>()
        for (row in 0 until size) {
            val cols = if (row % 2 == 0) (0 until size) else (size - 1 downTo 0)
            for (col in cols) list.add(Triple(col, row, grid[row][col]))
        }
        return list
    }

    private fun moveToward(
        session: NexoraRelaySession,
        curX: Float, curZ: Float,
        dx: Float, dz: Float, dist: Float
    ) {
        val step = STEP_SIZE.coerceAtMost(dist)
        val nx   = curX + (dx / dist) * step
        val nz   = curZ + (dz / dist) * step

        session.serverBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(nx, EntityTracker.selfY, nz)
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        })

        // Client tarafını da güncelle (görsel tutarlılık)
        session.clientBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(nx, EntityTracker.selfY, nz)
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        })

        EntityTracker.selfX = nx
        EntityTracker.selfZ = nz
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

    /**
     * Blok yerleştirme.
     *
     * Bedrock'ta zemin bloğunun ÜSTÜNE yerleştirmek için:
     *   blockPosition = zemin bloğunun pozisyonu (targetX, targetY - 1, targetZ)
     *   blockFace     = 1 (UP — üst yüzey)
     *
     * targetY zaten zemin Y'si olduğundan (originY = floor(selfY-1)):
     *   yerleştirme hedef → (x, targetY-1, z) üstüne face=UP
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
                blockPosition   = Vector3i.from(x, y - 1, z)   // zemin bloğu
                blockFace       = 1            // UP yüzey
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
}
