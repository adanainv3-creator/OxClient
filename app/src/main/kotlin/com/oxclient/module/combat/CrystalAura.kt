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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * CrystalAura
 *
 * Nasıl çalışır:
 *  1. Hedef entity'yi EntityTracker'dan alır.
 *  2. Hedefin etrafındaki obsidyen/bedrock bloklarını yerleştirme pozisyonu olarak hesaplar.
 *  3. UseItem (BlockPlace) paketi ile her pozisyona kristal yerleştirir.
 *  4. AddEntity paketini dinler → yeni kristallerin runtimeId'sini kaydet.
 *  5. Anında (Instant) veya sırayla (Sequential) kristalleri patlatır:
 *       → InventoryTransaction USE_ITEM_ON_ENTITY (attack) paketi gönder
 *  6. AntiSuicide: self-damage hesabı yaparak kendini öldürecekse atla.
 *  7. RemoveParticles: LevelEvent 2001 (explosion) paketini iptal et.
 */
class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "End kristallerini otomatik yerleştirir ve patlatır"
), PacketListener {

    override val priority = 90

    enum class BreakMode { Instant, Sequential }
    enum class Priority  { Distance, Health }

    // ── Ayarlar ───────────────────────────────────────────────────────────
    private val autoPlace       = BoolSetting("AutoPlace",       true)
    private val autoBreak       = BoolSetting("AutoBreak",       true)
    private val breakMode       = EnumSetting("BreakMode",       BreakMode.Instant,   BreakMode.entries)
    private val priority        = EnumSetting("Priority",        Priority.Distance,   Priority.entries)
    private val placeRange      = FloatSetting("PlaceRange",     6f,  1f, 12f)
    private val breakRange      = FloatSetting("BreakRange",     6f,  1f, 12f)
    private val wallsRange      = FloatSetting("WallsRange",     5f,  1f, 10f)
    private val antiSuicide     = BoolSetting("AntiSuicide",     true)
    private val throughWalls    = BoolSetting("ThroughWalls",    true)
    private val throughBlocks   = BoolSetting("ThroughBlocks",   true)
    private val placeDelay      = IntSetting("PlaceDelay",       0,   0, 500)
    private val breakDelay      = IntSetting("BreakDelay",       0,   0, 500)
    private val maxPlace        = IntSetting("MaxPlace",         25,  1,  50)
    private val maxBreak        = IntSetting("MaxBreak",         25,  1,  50)
    private val removeParticles = BoolSetting("RemoveParticles", true)
    private val shortcut        = BoolSetting("Shortcut",        true)

    override fun registerSettings() = listOf(
        autoPlace, autoBreak, breakMode, priority,
        placeRange, breakRange, wallsRange,
        antiSuicide, throughWalls, throughBlocks,
        placeDelay, breakDelay, maxPlace, maxBreak,
        removeParticles, shortcut
    )

    // ── İç durum ──────────────────────────────────────────────────────────
    // Aktif kristaller: runtimeId → Pozisyon
    private val activeCrystals  = ConcurrentHashMap<Long, Triple<Float, Float, Float>>()
    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var seqBreakIndex   = 0

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob : Job? = null

    companion object {
        private const val TAG            = "CrystalAura"
        private const val END_CRYSTAL_ID = 71    // Bedrock AddEntity typeId — End Crystal
        // Kristal patlaması self hasar mesafesi (yaklaşık)
        private const val CRYSTAL_DAMAGE_RADIUS = 6f
        // Bedrock'ta kristal yerleştirilen blok yüzleri
        private val PLACE_FACES = listOf(0, 1, 2, 3, 4, 5)
    }

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

            // End Crystal entity type string
            if (typeStr == "minecraft:ender_crystal" || typeStr.contains("crystal", ignoreCase = true)) {
                activeCrystals[rid] = Triple(x, y, z)
                Log.v(TAG, "Kristal eklendi: $rid @ $x,$y,$z (toplam: ${activeCrystals.size})")
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
        // LevelEvent 2001 = block break particles, 3001 = crystal explosion
        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(event.data, pos); pos = p1
            val (evtId, _) = PacketHelper.readVarInt(event.data, pos)
            if (evtId == 3001 || evtId == 2001) {
                event.isCancelled = true  // partikülleri istemciye iletme
            }
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
            delay(10L)  // Crystal daha hızlı tick ister
        }
    }

    // ── Hedef seçimi ──────────────────────────────────────────────────────

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val range = if (throughWalls.value) wallsRange.value else placeRange.value
        val candidates = EntityTracker.getEntitiesInRange(range)

        return when (priority.value) {
            Priority.Distance -> candidates.minByOrNull { EntityTracker.distanceTo(it) }
            Priority.Health   -> candidates.minByOrNull { it.health }
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

            val distToSelf = dist3(
                bx.toFloat(), by.toFloat(), bz.toFloat(),
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ
            )
            val effectiveRange = if (throughWalls.value) wallsRange.value else placeRange.value
            if (distToSelf > effectiveRange) continue

            // AntiSuicide: bu pozisyona koyulan kristal beni öldürür mü?
            if (antiSuicide.value) {
                val dmgToSelf = estimateDamage(
                    bx.toFloat() + 0.5f, by.toFloat() + 1f, bz.toFloat() + 0.5f,
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ
                )
                if (dmgToSelf >= EntityTracker.run {
                    // selfHealth — EntityTracker'da health takibi eklenebilir
                    // Şimdilik sabit 6 hasar sınırı kullanıyoruz
                    6f
                }) continue
            }

            PacketHelper.injectToServer(
                PacketHelper.buildUseItem(
                    actionType = 0,  // clickBlock
                    blockX = bx, blockY = by, blockZ = bz,
                    face = 1,        // üst yüz
                    x = EntityTracker.selfX,
                    y = EntityTracker.selfY,
                    z = EntityTracker.selfZ
                )
            )
            placed++
            Log.v(TAG, "Kristal yerleştirildi: $bx,$by,$bz")
        }
    }

    /**
     * Hederin etrafındaki yerleştirme pozisyonlarını hesaplar.
     * Obsidyen/bedrock yüzeyleri — burada basit düzlem araması yapıyoruz.
     * (Gerçek blok verisi olmadan en yakın taban pozisyonlarını kullanıyoruz.)
     */
    private fun calcPlacePositions(target: EntityTracker.TrackedEntity): List<Triple<Int, Int, Int>> {
        val positions = mutableListOf<Triple<Int, Int, Int>>()
        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()

        // Hederin etrafında 5x5x3 küpünde taban pozisyonları
        for (dx in -2..2) for (dz in -2..2) for (dy in -1..1) {
            val bx = tx + dx; val by = ty + dy; val bz = tz + dz
            // Aynı pozisyona zaten kristal var mı?
            val alreadyPlaced = activeCrystals.values.any { (cx, cy, cz) ->
                Math.abs(cx - bx - 0.5f) < 0.5f &&
                Math.abs(cy - by - 1f)   < 0.5f &&
                Math.abs(cz - bz - 0.5f) < 0.5f
            }
            if (!alreadyPlaced) positions.add(Triple(bx, by, bz))
        }
        // En yakın pozisyondan sırala
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
                dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) <= breakRange.value
            }
            .sortedBy { (_, pos) ->
                val (cx, cy, cz) = pos
                // Hedere en yakın kristali önce patlat
                dist3(cx, cy, cz, target.x, target.y, target.z)
            }

        when (breakMode.value) {
            BreakMode.Instant    -> breakInstant(crystalsInRange, target)
            BreakMode.Sequential -> breakSequential(crystalsInRange, target)
        }
    }

    private fun breakInstant(
        crystals: List<Map.Entry<Long, Triple<Float, Float, Float>>>,
        target: EntityTracker.TrackedEntity
    ) {
        var broken = 0
        for ((rid, pos) in crystals) {
            if (broken >= maxBreak.value) break
            val (cx, cy, cz) = pos

            if (antiSuicide.value) {
                val dmg = estimateDamage(cx, cy, cz,
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                if (dmg >= 6f) continue
            }
            // Animate + Attack
            PacketHelper.injectToServer(PacketHelper.buildAnimate(EntityTracker.selfRuntimeId))
            PacketHelper.injectToServer(PacketHelper.buildAttack(rid, EntityTracker.selfRuntimeId))
            activeCrystals.remove(rid)
            broken++
            Log.v(TAG, "Kristal patlatıldı: $rid")
        }
    }

    private fun breakSequential(
        crystals: List<Map.Entry<Long, Triple<Float, Float, Float>>>,
        target: EntityTracker.TrackedEntity
    ) {
        if (crystals.isEmpty()) { seqBreakIndex = 0; return }
        seqBreakIndex = seqBreakIndex % crystals.size
        val (rid, pos) = crystals[seqBreakIndex]
        val (cx, cy, cz) = pos

        if (antiSuicide.value) {
            val dmg = estimateDamage(cx, cy, cz,
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            if (dmg < 6f) {
                PacketHelper.injectToServer(PacketHelper.buildAnimate(EntityTracker.selfRuntimeId))
                PacketHelper.injectToServer(PacketHelper.buildAttack(rid, EntityTracker.selfRuntimeId))
                activeCrystals.remove(rid)
                Log.v(TAG, "Sequential kristal: $rid (idx=$seqBreakIndex)")
            }
        } else {
            PacketHelper.injectToServer(PacketHelper.buildAnimate(EntityTracker.selfRuntimeId))
            PacketHelper.injectToServer(PacketHelper.buildAttack(rid, EntityTracker.selfRuntimeId))
            activeCrystals.remove(rid)
        }
        seqBreakIndex++
    }

    // ── Hasar tahmini ─────────────────────────────────────────────────────

    /**
     * Basit kübik hasar düşüşü formülü (Bedrock yaklaşımı).
     * Gerçek formül blast protection, armo'ru hesaba katar —
     * bu versiyon proxy'de blok verisi olmadan maksimum hasarı tahmin eder.
     */
    private fun estimateDamage(
        expX: Float, expY: Float, expZ: Float,
        victimX: Float, victimY: Float, victimZ: Float
    ): Float {
        val dist = dist3(expX, expY, expZ, victimX, victimY, victimZ)
        if (dist > CRYSTAL_DAMAGE_RADIUS) return 0f
        val exposure = 1f - (dist / CRYSTAL_DAMAGE_RADIUS)
        // Bedrock End Crystal güç = 6 (yaklaşık max ~96 raw, armor ile ~25-30)
        return exposure * exposure * 97f * 0.85f  // ~%85 coverage varsayımı
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────

    private fun dist3(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1-x2; val dy = y1-y2; val dz = z1-z2
        return Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
    }
}