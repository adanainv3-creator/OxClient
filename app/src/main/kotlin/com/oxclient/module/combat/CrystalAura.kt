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
    enum class BreakMode     { Instant, Sequential, Closest }
    enum class PlaceMode     { Safe, Aggressive, Smart }
    enum class TargetPriority{ Distance, Health, DamageRatio }

    private val autoPlace      = bool ("Auto Place",    true)
    private val autoBreak      = bool ("Auto Break",    true)
    private val breakMode      = enum ("Break Mode",    BreakMode.Instant)
    private val placeMode      = enum ("Place Mode",    PlaceMode.Smart)
    private val targetPriority = enum ("Priority",      TargetPriority.Distance)
    private val placeRange     = float("Place Range",   6f,  1f, 12f)
    private val breakRange     = float("Break Range",   6f,  1f, 12f)
    private val wallsRange     = float("Walls Range",   5f,  1f, 10f)
    private val throughWalls   = bool ("Through Walls", true)
    private val placeDelay     = int  ("Place Delay",   0,   0,  500)
    private val breakDelay     = int  ("Break Delay",   0,   0,  500)
    private val maxPlace       = int  ("Max Place",     25,  1,  50)
    private val maxBreak       = int  ("Max Break",     25,  1,  50)
    private val selfDmgLimit   = float("Self Dmg Limit",6f,  0f, 20f)
    private val minDmg         = float("Min Dmg",       4f,  0f, 20f)
    private val antiSuicide    = bool ("Anti Suicide",  true)
    private val rotate         = bool ("Rotate",        true)
    private val shortcut       = bool ("Shortcut",      true)

    private val activeCrystals  = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    private val placedPositions = ConcurrentHashMap<Long, Long>()

    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var seqIndex = 0
    private var tickJob: Job? = null

    private var cachedObsidianDef: BlockDefinition? = null

    private companion object { const val TAG = "CrystalAura" }

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); placedPositions.clear()
        seqIndex = 0
        cachedObsidianDef = null
        OverlayLogger.d(TAG, "Enabled: autoPlace=${autoPlace.value} autoBreak=${autoBreak.value} placeMode=${placeMode.value} breakMode=${breakMode.value}")
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled — activeCrystals=${activeCrystals.size} temizlendi")
        activeCrystals.clear(); uniqueToRuntime.clear(); placedPositions.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is AddEntityPacket -> {
                if (pkt.identifier.contains("crystal", ignoreCase = true)) {
                    activeCrystals[pkt.runtimeEntityId] = pkt.position
                    uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
                    OverlayLogger.v(TAG, "Crystal AddEntity: runtimeId=${pkt.runtimeEntityId} pos=${pkt.position} (toplam=${activeCrystals.size})")
                }
            }
            is RemoveEntityPacket -> {
                val rid = uniqueToRuntime.remove(pkt.uniqueEntityId)
                if (rid != null) {
                    activeCrystals.remove(rid)
                    OverlayLogger.v(TAG, "Crystal RemoveEntity: runtimeId=$rid (kalan=${activeCrystals.size})")
                }
            }
            is LevelEventPacket -> {
                val type = pkt.type
                if (type != null) {
                    val typeName = type.toString().uppercase()
                    if (typeName.contains("PARTICLE") || typeName.contains("EXPLOSION")) {
                        event.cancel()
                    }
                }
            }
            else -> {}
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                val target = selectTarget()
                if (target != null) {
                    if (autoBreak.value) doBreak(target)
                    if (autoPlace.value) doPlace(target)
                } else if (System.currentTimeMillis() % 3000L < 10L) {
                    OverlayLogger.v(TAG, "tickLoop: hedef bulunamadı")
                }
            }
            delay(10L)
        }
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val r = if (throughWalls.value) wallsRange.value else placeRange.value
        val candidates = EntityTracker.getEntitiesInRange(r)
        return when (targetPriority.value) {
            TargetPriority.Distance    -> candidates.minByOrNull { EntityTracker.distanceTo(it) }
            TargetPriority.Health      -> candidates.minByOrNull { it.health }
            TargetPriority.DamageRatio -> candidates.minByOrNull { it.health / (EntityTracker.distanceTo(it) + 0.1f) }
        }
    }

    // ──── GET OBSIDIAN DEFINITION ──────────────────────────────────────────

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
                        OverlayLogger.d(TAG, "Obsidian definition bulundu: runtimeId=$id")
                        return def
                    }
                }
            }

            val blockDefs = Definitions.blockDefinitions
            for (id in possibleIds) {
                val def = blockDefs.getDefinition(id)
                if (def != null && identifierOf(def) == "minecraft:obsidian") {
                    cachedObsidianDef = def
                    OverlayLogger.d(TAG, "Obsidian definition Definitions.blockDefinitions'den: runtimeId=$id")
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

    // ──── DO PLACE ──────────────────────────────────────────────────────────

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return
        lastPlaceMs = now

        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "doPlace: session null")
            return
        }

        val obsidianDef = getObsidianDefinition(session)
        if (obsidianDef == null) {
            OverlayLogger.w(TAG, "Obsidian definition alınamadı, place atlanıyor")
            return
        }

        when (placeMode.value) {
            PlaceMode.Safe -> doPlaceSafe(target, session, obsidianDef)
            PlaceMode.Aggressive -> doPlaceAggressive(target, session, obsidianDef)
            PlaceMode.Smart -> doPlaceSmart(target, session, obsidianDef)
        }
    }

    private fun doPlaceSafe(target: EntityTracker.TrackedEntity, session: OxRelaySession, obsidianDef: BlockDefinition) {
        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()
        var placed = 0
        val range = if (throughWalls.value) wallsRange.value else placeRange.value

        val positions = mutableListOf<Triple<Int, Int, Int>>()
        for (dx in -1..1) for (dz in -1..1) for (dy in 0..1) {
            positions.add(Triple(tx + dx, ty + dy, tz + dz))
        }
        positions.sortByDescending { (_, y, _) -> y }

        for ((bx, by, bz) in positions) {
            if (placed >= maxPlace.value) return
            if (!tryPlaceCrystal(bx, by, bz, session, obsidianDef, range)) continue
            placed++
        }
    }

    private fun doPlaceAggressive(target: EntityTracker.TrackedEntity, session: OxRelaySession, obsidianDef: BlockDefinition) {
        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()
        var placed = 0
        val range = if (throughWalls.value) wallsRange.value else placeRange.value

        val positions = mutableListOf<Triple<Int, Int, Int>>()
        positions.add(Triple(tx, ty + 1, tz))
        for (dx in -2..2) for (dz in -2..2) for (dy in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            positions.add(Triple(tx + dx, ty + dy, tz + dz))
        }

        for ((bx, by, bz) in positions) {
            if (placed >= maxPlace.value) return
            if (!tryPlaceCrystal(bx, by, bz, session, obsidianDef, range)) continue
            placed++
        }
    }

    private fun doPlaceSmart(target: EntityTracker.TrackedEntity, session: OxRelaySession, obsidianDef: BlockDefinition) {
        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()
        var placed = 0
        val range = if (throughWalls.value) wallsRange.value else placeRange.value

        val positions = mutableListOf<Triple<Int, Int, Int>>()
        positions.add(Triple(tx, ty + 1, tz))
        for (dx in -1..1) for (dz in -1..1) {
            if (dx == 0 && dz == 0) continue
            positions.add(Triple(tx + dx, ty + 1, tz + dz))
        }
        for (dx in -2..2) for (dz in -2..2) {
            if (kotlin.math.abs(dx) <= 1 && kotlin.math.abs(dz) <= 1) continue
            positions.add(Triple(tx + dx, ty + 1, tz + dz))
        }

        for ((bx, by, bz) in positions) {
            if (placed >= maxPlace.value) return
            if (!tryPlaceCrystal(bx, by, bz, session, obsidianDef, range)) continue
            placed++
        }
    }

    private fun tryPlaceCrystal(
        bx: Int, by: Int, bz: Int,
        session: OxRelaySession,
        obsidianDef: BlockDefinition,
        range: Float
    ): Boolean {
        val bKey = packKey(bx, by, bz)
        if (placedPositions.containsKey(bKey)) return false

        val alreadyPlaced = activeCrystals.values.any { c ->
            Math.abs(c.x - bx - 0.5f) < 0.5f &&
            Math.abs(c.y - by - 1f)   < 0.5f &&
            Math.abs(c.z - bz - 0.5f) < 0.5f
        }
        if (alreadyPlaced) return false

        if (MathUtil.dist3(bx.toFloat(), by.toFloat(), bz.toFloat(),
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) > range) return false

        if (antiSuicide.value) {
            val selfDist = MathUtil.dist3(bx + 0.5f, by + 1f, bz + 0.5f,
                EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ)
            if (selfDist < selfDmgLimit.value) return false
        }

        if (rotate.value) {
            val r = RotationUtil.toPoint(bx + 0.5f, by.toFloat(), bz + 0.5f)
            PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
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
            OverlayLogger.v(TAG, "Crystal yerleştirildi: ($bx,$by,$bz)")

        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Crystal yerleştirme hatası: ${e.message}")
            return false
        }

        return true
    }

    // ──── DO BREAK ──────────────────────────────────────────────────────────

    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < breakDelay.value) return
        lastBreakMs = now

        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "doBreak: session null")
            return
        }

        val sorted = activeCrystals.entries
            .filter { (_, p) ->
                MathUtil.dist3(p.x, p.y, p.z,
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) <= breakRange.value
            }
            .sortedBy { (_, p) ->
                MathUtil.dist3(p.x, p.y, p.z, target.x, target.y, target.z)
            }

        if (sorted.isEmpty()) {
            OverlayLogger.v(TAG, "doBreak: range içinde crystal yok (breakRange=${breakRange.value})")
            return
        }

        when (breakMode.value) {
            BreakMode.Instant -> {
                var b = 0
                for ((rid, pos) in sorted) {
                    if (b++ >= maxBreak.value) break
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
                OverlayLogger.v(TAG, "doBreak: ${b} crystal kırıldı (Instant)")
            }
            BreakMode.Sequential -> {
                seqIndex %= sorted.size
                val (rid, _) = sorted[seqIndex]
                attackCrystal(rid, session)
                activeCrystals.remove(rid)
                seqIndex++
                OverlayLogger.v(TAG, "doBreak: 1 crystal kırıldı (Sequential #$seqIndex)")
            }
            BreakMode.Closest -> {
                sorted.firstOrNull()?.let { (rid, _) ->
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                    OverlayLogger.v(TAG, "doBreak: 1 crystal kırıldı (Closest)")
                }
            }
        }
    }

    private fun attackCrystal(rid: Long, session: OxRelaySession) {
        if (rotate.value) {
            val pos = activeCrystals[rid] ?: return
            val r = RotationUtil.toPoint(pos.x, pos.y, pos.z)
            PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        }
        PacketUtil.sendSwingAndAttack(session, rid)
    }

    private fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}

