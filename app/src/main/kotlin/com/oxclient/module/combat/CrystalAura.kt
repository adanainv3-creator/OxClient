package com.oxclient.module.combat

import android.util.Log
import com.oxclient.core.proxy.BedrockPacketIds
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.module.*
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "End kristallerini otomatik yerleştirir ve patlatır"
), PacketListener {

    // FIX: PacketListener.priority ile çakışmayı önlemek için override
    override val priority: Int = 90

    enum class BreakMode    { Instant, Sequential }
    enum class TargetPriority { Distance, Health }  // FIX: Priority → TargetPriority

    // ── Ayarlar ───────────────────────────────────────────────────────────
    private val autoPlace       = BoolSetting("AutoPlace",       true)
    private val autoBreak       = BoolSetting("AutoBreak",       true)
    private val breakMode       = EnumSetting("BreakMode",       BreakMode.Instant,         BreakMode.entries)
    // FIX: EnumSetting adı "Priority" kalıyor ama tipi TargetPriority
    private val targetPriority  = EnumSetting("Priority",        TargetPriority.Distance,   TargetPriority.entries)
    private val placeRange      = FloatSetting("PlaceRange",     6f,  1f, 12f)
    private val breakRange      = FloatSetting("BreakRange",     6f,  1f, 12f)
    private val wallsRange      = FloatSetting("WallsRange",     5f,  1f, 10f)
    private val throughWalls    = BoolSetting("ThroughWalls",    true)
    private val throughBlocks   = BoolSetting("ThroughBlocks",   true)
    private val placeDelay      = IntSetting("PlaceDelay",       0,   0, 500)
    private val breakDelay      = IntSetting("BreakDelay",       0,   0, 500)
    private val maxPlace        = IntSetting("MaxPlace",         25,  1,  50)
    private val maxBreak        = IntSetting("MaxBreak",         25,  1,  50)
    private val removeParticles = BoolSetting("RemoveParticles", true)
    private val shortcut        = BoolSetting("Shortcut",        true)

    override fun registerSettings() = listOf(
        autoPlace, autoBreak, breakMode, targetPriority,
        placeRange, breakRange, wallsRange,
        throughWalls, throughBlocks,
        placeDelay, breakDelay, maxPlace, maxBreak,
        removeParticles, shortcut
    )

    // ── İç durum ──────────────────────────────────────────────────────────
    private val activeCrystals  = ConcurrentHashMap<Long, Triple<Float, Float, Float>>()
    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var seqBreakIndex   = 0

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob : Job? = null

    companion object {
        private const val TAG = "CrystalAura"
    }

    // ── Yaşam döngüsü ─────────────────────────────────────────────────────

    override fun onEnable() {
        activeCrystals.clear()
        seqBreakIndex = 0
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
        Log.d(TAG, "Etkinleştirildi")
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        activeCrystals.clear()
    }

    // ── Paket dinleyici ───────────────────────────────────────────────────

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (event.packetId) {
            BedrockPacketIds.ADD_ENTITY    -> parseAddEntity(event)
            BedrockPacketIds.REMOVE_ENTITY -> parseRemoveEntity(event.data)
            BedrockPacketIds.LEVEL_EVENT   -> {
                if (removeParticles.value) suppressCrystalParticles(event)
            }
        }
    }

    private fun parseAddEntity(event: PacketEvent) {
        val d = event.data
        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(d, pos); pos = p1
            val (_, p2) = PacketHelper.readVarLong(d, pos); pos = p2   // uniqueId
            val (rid, p3) = PacketHelper.readVarLong(d, pos); pos = p3
            val (typeStr, p4) = PacketHelper.readString(d, pos); pos = p4
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)

            if (typeStr.contains("crystal", ignoreCase = true)) {
                activeCrystals[rid] = Triple(x, y, z)
                Log.v(TAG, "Kristal eklendi: $rid @ $x,$y,$z")
            }
        } catch (_: Exception) {}
    }

    private fun parseRemoveEntity(data: ByteArray) {
        try {
            val (_, p1) = PacketHelper.readVarInt(data, 0)
            val (rid, _) = PacketHelper.readVarLong(data, p1)
            activeCrystals.remove(rid)
        } catch (_: Exception) {}
    }

    private fun suppressCrystalParticles(event: PacketEvent) {
        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(event.data, pos); pos = p1
            val (evtId, _) = PacketHelper.readVarInt(event.data, pos)
            if (evtId == 3001 || evtId == 2001) event.isCancelled = true
        } catch (_: Exception) {}
    }

    // ── Tick ──────────────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (coroutineContext.isActive) {
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

    // ── Hedef seçimi ──────────────────────────────────────────────────────

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val r = if (throughWalls.value) wallsRange.value else placeRange.value
        val candidates = EntityTracker.getEntitiesInRange(r)
        // FIX: targetPriority.value kullanılıyor, when exhaustive
        return when (targetPriority.value) {
            TargetPriority.Distance -> candidates.minByOrNull { EntityTracker.distanceTo(it) }
            TargetPriority.Health   -> candidates.minByOrNull { it.health }
        }
    }

    // ── Kristal Yerleştirme ───────────────────────────────────────────────

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return
        lastPlaceMs = now

        val positions = calcPlacePositions(target)
        var placed = 0

        for ((bx, by, bz) in positions) {
            if (placed >= maxPlace.value) break
            val effectiveRange = if (throughWalls.value) wallsRange.value else placeRange.value
            val distToSelf = dist3(
                bx.toFloat(), by.toFloat(), bz.toFloat(),
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ
            )
            if (distToSelf > effectiveRange) continue

            PacketHelper.injectToServer(
                PacketHelper.buildUseItem(
                    actionType = 0,
                    blockX = bx, blockY = by, blockZ = bz,
                    face = 1,
                    x = EntityTracker.selfX,
                    y = EntityTracker.selfY,
                    z = EntityTracker.selfZ
                )
            )
            placed++
            Log.v(TAG, "Kristal yerleştirildi: $bx,$by,$bz")
        }
    }

    private fun calcPlacePositions(target: EntityTracker.TrackedEntity): List<Triple<Int, Int, Int>> {
        val positions = mutableListOf<Triple<Int, Int, Int>>()
        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()

        for (dx in -2..2) for (dz in -2..2) for (dy in -1..1) {
            val bx = tx + dx; val by = ty + dy; val bz = tz + dz
            val alreadyPlaced = activeCrystals.values.any { (cx, cy, cz) ->
                Math.abs(cx - bx - 0.5f) < 0.5f &&
                Math.abs(cy - by - 1f)   < 0.5f &&
                Math.abs(cz - bz - 0.5f) < 0.5f
            }
            if (!alreadyPlaced) positions.add(Triple(bx, by, bz))
        }
        return positions.sortedBy { (bx, by, bz) ->
            dist3(bx.toFloat(), by.toFloat(), bz.toFloat(), target.x, target.y, target.z)
        }
    }

    // ── Kristal Patlatma ──────────────────────────────────────────────────

    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < breakDelay.value) return
        lastBreakMs = now

        val crystalsInRange = activeCrystals.entries
            .filter { (_, pos) ->
                val (cx, cy, cz) = pos
                dist3(cx, cy, cz,
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ
                ) <= breakRange.value
            }
            .sortedBy { (_, pos) ->
                val (cx, cy, cz) = pos
                dist3(cx, cy, cz, target.x, target.y, target.z)
            }

        when (breakMode.value) {
            BreakMode.Instant    -> breakInstant(crystalsInRange)
            BreakMode.Sequential -> breakSequential(crystalsInRange)
        }
    }

    private fun breakInstant(crystals: List<Map.Entry<Long, Triple<Float, Float, Float>>>) {
        var broken = 0
        for ((rid, _) in crystals) {
            if (broken >= maxBreak.value) break
            PacketHelper.injectToServer(PacketHelper.buildAnimate(EntityTracker.selfRuntimeId))
            PacketHelper.injectToServer(PacketHelper.buildAttack(rid, EntityTracker.selfRuntimeId))
            activeCrystals.remove(rid)
            broken++
            Log.v(TAG, "Kristal patlatıldı: $rid")
        }
    }

    private fun breakSequential(crystals: List<Map.Entry<Long, Triple<Float, Float, Float>>>) {
        if (crystals.isEmpty()) { seqBreakIndex = 0; return }
        seqBreakIndex = seqBreakIndex % crystals.size
        val (rid, _) = crystals[seqBreakIndex]
        PacketHelper.injectToServer(PacketHelper.buildAnimate(EntityTracker.selfRuntimeId))
        PacketHelper.injectToServer(PacketHelper.buildAttack(rid, EntityTracker.selfRuntimeId))
        activeCrystals.remove(rid)
        Log.v(TAG, "Sequential kristal: $rid (idx=$seqBreakIndex)")
        seqBreakIndex++
    }

    // ── Yardımcı ──────────────────────────────────────────────────────────

    private fun dist3(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1-x2; val dy = y1-y2; val dz = z1-z2
        return Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
    }
}