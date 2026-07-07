package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.Definitions
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "End kristallerini otomatik yerleştirir ve patlatır"
) {
    enum class Mode { Single, Full5x5 }

    private val range           = float("Range", 5f, 3f, 10f)
    private val suicide         = bool ("Suicide", false)
    private val place           = bool ("Place", true)
    private val delay           = int  ("Delay", 400, 100, 1000)
    private val removeParticles = bool ("RemoveParticles", true)
    private val placeMode       = enum ("Place Mode", Mode.Single)
    private val breakMode       = enum ("Break Mode", Mode.Single)

    private val activeCrystals  = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    private val placedPositions = ConcurrentHashMap<Long, Long>()

    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var tickJob: Job? = null

    private var cachedObsidianDef: BlockDefinition? = null

    private companion object { const val TAG = "CrystalAura" }

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); placedPositions.clear()
        cachedObsidianDef = null
        OverlayLogger.d(TAG, "Enabled: place=${place.value} placeMode=${placeMode.value} breakMode=${breakMode.value}")
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled")
        activeCrystals.clear(); uniqueToRuntime.clear(); placedPositions.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is AddEntityPacket -> {
                if (pkt.identifier.contains("crystal", ignoreCase = true)) {
                    activeCrystals[pkt.runtimeEntityId] = pkt.position
                    uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
                }
            }
            is RemoveEntityPacket -> {
                val rid = uniqueToRuntime.remove(pkt.uniqueEntityId)
                if (rid != null) activeCrystals.remove(rid)
            }
            is LevelEventPacket -> {
                if (removeParticles.value) {
                    val typeName = pkt.type?.toString()?.uppercase() ?: ""
                    if (typeName.contains("PARTICLE") || typeName.contains("EXPLOSION")) event.cancel()
                }
            }
            else -> {}
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                doBreak()
                if (place.value) {
                    val target = selectTarget()
                    if (target != null) doPlace(target)
                }
            }
            delay(10L)
        }
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? =
        EntityTracker.getEntitiesInRange(range.value).minByOrNull { EntityTracker.distanceTo(it) }

    private fun identifierOf(def: BlockDefinition): String? = when (def) {
        is SimpleBlockDefinition -> def.identifier
        is Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
        else -> null
    }

    private fun getObsidianDefinition(session: OxRelaySession): BlockDefinition? {
        cachedObsidianDef?.let { return it }
        val possibleIds = listOf(49, 158, 48, 160, 247, 43, 45)

        try {
            val defs = session.clientSession.peer.codecHelper.blockDefinitions
            if (defs != null) {
                for (id in possibleIds) {
                    val def = defs.getDefinition(id)
                    if (def != null && identifierOf(def) == "minecraft:obsidian") {
                        cachedObsidianDef = def
                        OverlayLogger.d(TAG, "Obsidian definition bulundu (client registry): runtimeId=$id")
                        return def
                    }
                }
            }

            val blockDefs = Definitions.getClosestDefinitions(session.activeCodec.protocolVersion).blockDefinitions
            for (id in possibleIds) {
                val def = blockDefs.getDefinition(id)
                if (def != null && identifierOf(def) == "minecraft:obsidian") {
                    cachedObsidianDef = def
                    OverlayLogger.d(TAG, "Obsidian definition bulundu (Definitions): runtimeId=$id")
                    return def
                }
            }

            OverlayLogger.w(TAG, "Obsidian definition bulunamadı, fallback oluşturuluyor")
            val fallback = SimpleBlockDefinition(
                "minecraft:obsidian",
                49,
                org.cloudburstmc.nbt.NbtMap.builder()
                    .putString("name", "minecraft:obsidian")
                    .putCompound("states", org.cloudburstmc.nbt.NbtMap.builder().build())
                    .build()
            )
            cachedObsidianDef = fallback
            return fallback
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Obsidian definition hatası: ${e.message}", e)
            return null
        }
    }

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < delay.value) return
        lastPlaceMs = now

        val session = PacketEventBus.currentSession ?: return
        val obsidianDef = getObsidianDefinition(session) ?: return

        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()
        val positions = mutableListOf<Triple<Int, Int, Int>>()

        when (placeMode.value) {
            Mode.Single -> positions.add(Triple(tx, ty + 1, tz))
            Mode.Full5x5 -> {
                positions.add(Triple(tx, ty + 1, tz))
                for (dx in -2..2) for (dz in -2..2) {
                    if (dx == 0 && dz == 0) continue
                    positions.add(Triple(tx + dx, ty + 1, tz + dz))
                }
            }
        }

        for ((bx, by, bz) in positions) {
            tryPlaceCrystal(bx, by, bz, session, obsidianDef)
        }
    }

    private fun tryPlaceCrystal(bx: Int, by: Int, bz: Int, session: OxRelaySession, obsidianDef: BlockDefinition) {
        val bKey = packKey(bx, by, bz)
        if (placedPositions.containsKey(bKey)) return

        val alreadyPlaced = activeCrystals.values.any { c ->
            Math.abs(c.x - bx - 0.5f) < 0.5f &&
            Math.abs(c.y - by - 1f)   < 0.5f &&
            Math.abs(c.z - bz - 0.5f) < 0.5f
        }
        if (alreadyPlaced) return

        if (MathUtil.dist3(bx.toFloat(), by.toFloat(), bz.toFloat(),
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) > range.value) return

        // ✅ Suicide=false: kendine >=3 blok'tan yakın yerleştirmeyi engeller (yakın
        // dövüşte hâlâ yerleştirebilmek için sabit ve düşük bir eşik — eski
        // "Anti Suicide" (varsayılan selfDmgLimit=6) melee menzilinden büyük olduğu
        // için pratikte HER pozisyonu eliyor, hiç yerleştirme olmuyordu.
        if (!suicide.value) {
            val selfDist = MathUtil.dist3(bx + 0.5f, by + 1f, bz + 0.5f,
                EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ)
            if (selfDist < 3f) return
        }

        val heldItem = EntityTracker.getInventoryItem(0) ?: ItemData.AIR

        try {
            val packet = InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType = 0
                blockPosition = Vector3i.from(bx, by, bz)
                blockFace = 1
                hotbarSlot = 0
                itemInHand = heldItem
                playerPosition = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
                this.blockDefinition = obsidianDef
                triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState = 0
            }
            session.serverBound(packet)
            placedPositions[bKey] = System.currentTimeMillis()
            OverlayLogger.d(TAG, "Crystal yerleştirildi: ($bx,$by,$bz)")
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Crystal yerleştirme hatası: ${e.message}")
        }
    }

    private fun doBreak() {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < delay.value) return
        lastBreakMs = now

        val session = PacketEventBus.currentSession ?: return

        val sorted = activeCrystals.entries
            .filter { (_, p) ->
                MathUtil.dist3(p.x, p.y, p.z,
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) <= range.value
            }
            .sortedBy { (_, p) ->
                MathUtil.dist3(p.x, p.y, p.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            }

        if (sorted.isEmpty()) return

        when (breakMode.value) {
            Mode.Single -> {
                sorted.firstOrNull()?.let { (rid, _) ->
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
            }
            Mode.Full5x5 -> {
                for ((rid, _) in sorted) {
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
            }
        }
    }

    private fun attackCrystal(rid: Long, session: OxRelaySession) {
        val pos = activeCrystals[rid] ?: return
        val r = RotationUtil.toPoint(pos.x, pos.y, pos.z)
        PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        PacketUtil.sendSwingAndAttack(session, rid)
    }

    private fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
