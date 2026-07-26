package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.utils.InventoryUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import com.oxclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import kotlin.math.floor

class AutoScaffold : BaseModule(
    name        = "AutoScaffold",
    category    = ModuleCategory.MOVEMENT,
    description = "Elindeki bloğu ayağının altına otomatik koyar"
) {
    companion object {
        private const val TICK_INTERVAL_MS = 50L
        // Bedrock blockFace sırası: 0=down 1=up 2=north(-z) 3=south(+z) 4=west(-x) 5=east(+x)
        private const val FACE_DOWN  = 0
        private const val FACE_UP    = 1
        private const val FACE_NORTH = 2
        private const val FACE_SOUTH = 3
        private const val FACE_WEST  = 4
        private const val FACE_EAST  = 5

        // Komşu yönler: [alt, kuzey, güney, batı, doğu]
        private val NEIGHBOR_OFFSETS = arrayOf(
            Triple(0, -1, 0),  // alt
            Triple(0, 0, -1),  // kuzey (-z)
            Triple(0, 0, 1),   // güney (+z)
            Triple(-1, 0, 0),  // batı (-x)
            Triple(1, 0, 0)    // doğu (+x)
        )
    }

    private val placeDelay = int ("Place Delay", 100, 30, 500)
    private val rotate     = bool("Rotate",      false)
    private val shortcut   = bool("Shortcut",    true)

    private var tickJob: Job? = null
    private var lastPlaceMs = 0L

    override fun onEnable() {
        super.onEnable()
        lastPlaceMs = 0L
        tickJob?.cancel()
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                val session = PacketEventBus.currentSession
                if (session != null) tryPlace(session)
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun tryPlace(session: OxRelaySession) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return

        val heldItem = EntityTracker.getHeldItem() ?: return
        if (InventoryUtil.isEmpty(heldItem)) return

        val footX = floor(EntityTracker.selfX).toInt()
        val footY = floor(EntityTracker.selfY).toInt()
        val footZ = floor(EntityTracker.selfZ).toInt()

        // Hedef: ayağının altındaki blok
        val targetY = footY - 1

        // Hedef hücre boş mu kontrol et
        val targetId = WorldBlockTracker.getBlockIdentifier(footX, targetY, footZ)
        if (targetId != null && targetId != "minecraft:air") return

        // Hedef hücrenin altını ve 4 yanını tara, ilk dolu bloğu bul
        var refX = footX
        var refY = targetY
        var refZ = footZ
        var foundRef = false
        var face = FACE_UP

        for ((dx, dy, dz) in NEIGHBOR_OFFSETS) {
            val checkX = footX + dx
            val checkY = targetY + dy
            val checkZ = footZ + dz

            val blockId = WorldBlockTracker.getBlockIdentifier(checkX, checkY, checkZ)
            if (blockId != null && blockId != "minecraft:air") {
                // Dolu blok bulundu
                refX = checkX
                refY = checkY
                refZ = checkZ
                foundRef = true

                // ref -> target yönü neyse, o yönün TERSİ tıklanacak yüzdür
                // (yeni blok, tıklanan yüzün dışına doğru oluşur)
                face = when {
                    dy == -1 -> FACE_UP    // ref altta  -> ref'in üst yüzüne tıkla
                    dx == -1 -> FACE_EAST  // ref batıda -> ref'in doğu yüzüne tıkla
                    dx == 1  -> FACE_WEST  // ref doğuda -> ref'in batı yüzüne tıkla
                    dz == -1 -> FACE_SOUTH // ref kuzeyde -> ref'in güney yüzüne tıkla
                    dz == 1  -> FACE_NORTH // ref güneyde -> ref'in kuzey yüzüne tıkla
                    else -> FACE_UP
                }
                break
            }
        }

        if (!foundRef) return // Hiç dolu blok yok, yerleştirilemez

        // Rotasyon ayarı
        if (rotate.value) {
            val r = RotationUtil.toPoint(
                footX + 0.5f,
                targetY + 0.5f,
                footZ + 0.5f
            )
            PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        }

        try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType = 0
                blockPosition = Vector3i.from(refX, refY, refZ)
                blockFace = face
                hotbarSlot = EntityTracker.selfHotbarSlot
                itemInHand = heldItem
                playerPosition = Vector3f.from(
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ
                )
                clickPosition = Vector3f.from(0.5f, 0.5f, 0.5f)
                triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState = 0
            })
            lastPlaceMs = now
        } catch (_: Exception) {}
    }
}
