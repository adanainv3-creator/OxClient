package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.action.ItemUseOnEntityInventoryAction
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "End kristallerini otomatik yerleştirir ve patlatır"
) {
    enum class BreakMode      { Instant, Sequential }
    enum class TargetPriority { Distance, Health }

    private val autoPlace      = bool ("AutoPlace",       true)
    private val autoBreak      = bool ("AutoBreak",       true)
    private val breakMode      = enum ("BreakMode",       BreakMode.Instant)
    private val targetPriority = enum ("Priority",        TargetPriority.Distance)
    private val placeRange     = float("PlaceRange",      6f,  1f, 12f)
    private val breakRange     = float("BreakRange",      6f,  1f, 12f)
    private val wallsRange     = float("WallsRange",      5f,  1f, 10f)
    private val throughWalls   = bool ("ThroughWalls",    true)
    private val placeDelay     = int  ("PlaceDelay",      0,   0,  500)
    private val breakDelay     = int  ("BreakDelay",      0,   0,  500)
    private val maxPlace       = int  ("MaxPlace",        25,  1,  50)
    private val maxBreak       = int  ("MaxBreak",        25,  1,  50)
    private val removeParticles= bool ("RemoveParticles", true)
    private val shortcut       = bool ("Shortcut",        true)

    private val activeCrystals  = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var seqIndex = 0
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); seqIndex = 0
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        activeCrystals.clear(); uniqueToRuntime.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is AddEntityPacket    -> {
                if (pkt.identifier.contains("crystal", ignoreCase = true)) {
                    activeCrystals[pkt.runtimeEntityId] = pkt.position
                    uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
                }
            }
            is RemoveEntityPacket -> {
                val rid = uniqueToRuntime.remove(pkt.uniquEntityId)
                if (rid != null) activeCrystals.remove(rid)
            }
            is LevelEventPacket   -> {
                if (removeParticles.value && (pkt.type == 3001 || pkt.type == 2001))
                    event.isCancelled = true
            }
            else -> {}
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                val target = selectTarget()
                if (target != null) {
                    if (autoPlace.value) doPlace(target)
                    if (autoBreak.value) doBreak(target)
                }
            }
            delay(10L)
        }
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val r = if (throughWalls.value) wallsRange.value else placeRange.value
        return when (targetPriority.value) {
            TargetPriority.Distance -> EntityTracker.getEntitiesInRange(r).minByOrNull { EntityTracker.distanceTo(it) }
            TargetPriority.Health   -> EntityTracker.getEntitiesInRange(r).minByOrNull { it.health }
        }
    }

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return
        lastPlaceMs = now
        val session = PacketEventBus.currentSession ?: return
        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()
        var placed = 0
        outer@ for (dx in -2..2) for (dz in -2..2) for (dy in -1..1) {
            if (placed >= maxPlace.value) break@outer
            val bx = tx+dx; val by = ty+dy; val bz = tz+dz
            val alreadyPlaced = activeCrystals.values.any { c ->
                Math.abs(c.x - bx - 0.5f) < 0.5f &&
                Math.abs(c.y - by - 1f)   < 0.5f &&
                Math.abs(c.z - bz - 0.5f) < 0.5f
            }
            if (alreadyPlaced) continue
            val dist = dist3(bx.toFloat(), by.toFloat(), bz.toFloat(),
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            if (dist > (if (throughWalls.value) wallsRange.value else placeRange.value)) continue
            val use = InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
            }
            session.serverBound(use)
            placed++
        }
    }

    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < breakDelay.value) return
        lastBreakMs = now
        val session = PacketEventBus.currentSession ?: return
        val sorted = activeCrystals.entries
            .filter { (_, p) -> dist3(p.x, p.y, p.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) <= breakRange.value }
            .sortedBy  { (_, p) -> dist3(p.x, p.y, p.z, target.x, target.y, target.z) }
        when (breakMode.value) {
            BreakMode.Instant    -> { var b = 0; for ((rid, _) in sorted) { if (b++ >= maxBreak.value) break; attack(rid, session); activeCrystals.remove(rid) } }
            BreakMode.Sequential -> { if (sorted.isEmpty()) { seqIndex=0; return }; seqIndex %= sorted.size; val (rid,_) = sorted[seqIndex]; attack(rid, session); activeCrystals.remove(rid); seqIndex++ }
        }
    }

    private fun attack(rid: Long, session: com.oxclient.core.relay.OxRelaySession) {
        session.serverBound(AnimatePacket().apply { action = AnimatePacket.Action.SWING_ARM; runtimeEntityId = EntityTracker.selfRuntimeId })
        session.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
            val a = ItemUseOnEntityInventoryAction()
            a.runtimeEntityId = rid; a.actionType = ItemUseOnEntityInventoryAction.TYPE_ATTACK; a.hotbarSlot = 0
            actions.add(a)
        })
    }

    private fun dist3(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx=x1-x2; val dy=y1-y2; val dz=z1-z2
        return Math.sqrt((dx*dx+dy*dy+dz*dz).toDouble()).toFloat()
    }
}
