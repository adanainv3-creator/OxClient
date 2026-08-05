package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Attığın (dev.sora.relay tabanlı) ModuleCrystalAura'nın birebir mantığının
 * RubidiumClient'e portu. O kod `session.level.simulateExplosionDamage(...)`
 * diye hazır bir fizik/patlama simülasyon kütüphanesine dayanıyordu — bende
 * öyle bir şey yok, o yüzden aynı vanilla patlama hasarı formülünü
 * (impact = (1 - mesafe/çap) * exposure; damage = (impact²+impact)/2*7*çap+1)
 * ve basitleştirilmiş bir line-of-sight exposure hesabını (WorldBlockTracker
 * üzerinden 5 örnek nokta) burada yeniden kurdum. Mantık — "en çok hasar
 * veren, kendine en az zarar veren noktayı bul" — birebir aynı.
 *
 * Önceki (çok daha büyük) CrystalAura.kt'deki rate-limit/token-bucket,
 * obsidian auto-support, self-surround, burst-on-totem, crystal slot
 * override gibi ekstra ayarlar kaldırıldı — attığın orijinal koddaki
 * sadeliğe göre sadece 5 ayar kaldı.
 */
class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "Hasar simülasyonuna göre en iyi noktaya kristal yerleştirir/kırar"
) {
    companion object {
        private const val EXPLOSION_SIZE   = 6f
        private const val TICK_INTERVAL_MS = 50L

        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )
    }

    private val range           = float("Range",           5f, 3f, 10f)
    private val suicide         = bool ("Suicide",          false)
    private val place           = bool ("Place",            true)
    // FIX: Eskiden 400ms idi ve tek kristal koyuyordu -> saniyede en fazla
    // 2.5 kristal. Artık her pencerede 4 kristal birden koyulduğu için
    // (maxPlacePerTick) delay'i düşürmek gerçek hızı ciddi artırıyor;
    // alt sınır da 100ms -> 40ms'ye indi (25 pencere/sn'ye kadar açılabiliyor).
    private val delayMs         = int  ("Delay",            150, 40, 1000)
    // Eskiden sadece EN İYİ 1 pozisyon bulunup tek kristal koyuluyordu.
    // Artık en iyi N pozisyon sıralanıp AYNI pencerede art arda koyuluyor.
    private val maxPlacePerTick = int  ("Max Place/Tick",   4, 1, 8)
    // FIX (tutarsızlık kaynağı): searchPlaceBase() tüm range kadar Y'de de
    // taranıyordu (range=10 -> ~9000 hücre + her aday için ray-cast simülasyonu).
    // Bu telefonda bazı tick'lerin 50ms penceresini aşıp o turu tamamen
    // kaçırmasına sebep oluyordu ("bazen hiç çalışmıyor"). Kristal tabanları
    // pratikte hep oyuncuya yakın Y seviyesinde olduğu için dikey taramayı
    // ayrı ve küçük bir bantla sınırladık — yatay range aynı kaldı.
    private val searchYRange     = int  ("Search Y Range",   4, 2, 10)
    private val removeParticles = bool ("RemoveParticles",  true)
    private val shortcut        = bool ("Shortcut",         false)

    @Volatile private var lastExplodeMs = 0L
    @Volatile private var lastPlaceMs   = 0L
    private var tickJob: Job? = null

    private val blockDefCache = ConcurrentHashMap<String, BlockDefinition>()

    private data class ExplosionResult(val mostDamage: Float, val selfDamage: Float)
    private data class PreparedItem(val slot: Int, val item: ItemData, val revertTo: Int?)

    override fun onEnable() {
        super.onEnable()
        lastExplodeMs = 0L
        lastPlaceMs   = 0L
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            // handleEntitySpawn karşılığı: yeni doğan kristal kârlıysa anında patlat.
            is AddEntityPacket -> {
                if (!pkt.identifier.contains("crystal", ignoreCase = true)) return
                val now = System.currentTimeMillis()
                if (now - lastExplodeMs < delayMs.value) return
                val cx = pkt.position.x; val cy = pkt.position.y; val cz = pkt.position.z
                if (MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) > range.value) return
                val dmg = simulateExplosionDamage(cx, cy, cz)
                if (dmg.selfDamage <= dmg.mostDamage || suicide.value) {
                    val session = PacketEventBus.currentSession ?: return
                    explodeCrystal(session, pkt.runtimeEntityId)
                    lastExplodeMs = now
                }
            }
            is LevelEventPacket -> {
                if (removeParticles.value && pkt.type == LevelEvent.PARTICLE_EXPLOSION) event.cancel()
            }
            else -> {}
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val now = System.currentTimeMillis()

        if (now - lastExplodeMs >= delayMs.value) tryExplodeBest(session, now)
        if (place.value && now - lastPlaceMs >= delayMs.value) tryPlaceBest(session, now)
    }

    // onTickExplode karşılığı: menzildeki tüm kristalleri (kendinki de dahil,
    // rakibinki de dahil) tara, en çok net hasar vereni patlat.
    private fun tryExplodeBest(session: RubidiumRelaySession, now: Long) {
        val crystals = EntityTracker.getCrystals(range.value)
        if (crystals.isEmpty()) return

        var bestId: Long? = null
        var bestDamage = -1f

        for (c in crystals) {
            val dmg = simulateExplosionDamage(c.x, c.y, c.z)
            val effective = if (dmg.selfDamage > dmg.mostDamage && !suicide.value) -1f else dmg.mostDamage
            if (effective > bestDamage) { bestDamage = effective; bestId = c.runtimeId }
        }

        if (bestId != null && bestDamage != -1f) {
            explodeCrystal(session, bestId)
            lastExplodeMs = now
        }
    }

    // onTickPlace karşılığı: menzildeki obsidian/bedrock tabanları tara,
    // en çok net hasar verecek noktaya kristal koy. Obsidian bulunmazsa
    // rakip etrafında fallback pozisyonlara (ayakları altı) kristal koy.
    private fun tryPlaceBest(session: RubidiumRelaySession, now: Long) {
        val bases = searchPlaceBase().toMutableList()

        // Obsidian bulunamazsa rakip etrafında fallback pozisyonları ekle
        if (bases.isEmpty()) {
            val targets = EntityTracker.getPlayers(range.value)
            for (target in targets) {
                if (target.runtimeId == EntityTracker.selfRuntimeId) continue
                val tx = floor(target.x).toInt()
                val ty = floor(target.y).toInt() - 1
                val tz = floor(target.z).toInt()
                for ((dx, dz) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
                    bases.add(Triple(tx + dx, ty, tz + dz))
                }
            }
        }

        if (bases.isEmpty()) return

        data class Scored(val pos: Triple<Int, Int, Int>, val damage: Float)
        val scored = ArrayList<Scored>(bases.size)
        for (pos in bases) {
            val cx = pos.first + 0.5f; val cy = pos.second + 2f; val cz = pos.third + 0.5f
            val dmg = simulateExplosionDamage(cx, cy, cz)
            val effective = if (dmg.selfDamage > dmg.mostDamage && !suicide.value) -1f else dmg.mostDamage
            if (effective > 0f) scored.add(Scored(pos, effective))
        }
        if (scored.isEmpty()) return

        // FIX: eskiden sadece EN İYİ 1 pozisyon bulunup tek kristal koyuluyordu.
        // Artık en iyi N pozisyon (Max Place/Tick) alınıp AYNI pencerede art
        // arda koyuluyor -> gerçek anlamda daha fazla ve daha hızlı kristal.
        val top = scored.sortedByDescending { it.damage }.take(maxPlacePerTick.value)

        // FIX (asıl "bazen çalışmıyor" kaynağı): eskiden her placeCrystalAt()
        // çağrısı KENDİ İÇİNDE ayrı ayrı prepareItemForUse()+revert yapıyordu.
        // Art arda hızlı hotbar slot değişimi sunucu tarafında çoğu
        // yerleştirmeyi sessizce geçersiz kılıyor (server "spam slot switch"
        // olarak görüp reddediyor) — bu yüzden bazen 1 kristal bile
        // koyulamıyordu. Artık slot SADECE BİR KEZ hazırlanıyor, N kristal
        // o hazırlanmış item ile art arda yerleştiriliyor, sonda TEK sefer
        // revert ediliyor.
        val prepared = prepareItemForUse(session) ?: return
        var placedAny = false
        for (s in top) {
            if (sendPlacementUseRaw(session, prepared, Vector3i.from(s.pos.first, s.pos.second, s.pos.third))) {
                placedAny = true
            }
        }
        prepared.revertTo?.let { InventoryUtil.sendHotbarSelect(session, it); EntityTracker.selfHotbarSlot = it }

        if (placedAny) lastPlaceMs = now
    }

    // ---------- Vanilla patlama hasarı simülasyonu ----------

    private fun simulateExplosionDamage(cx: Float, cy: Float, cz: Float): ExplosionResult {
        val diameter = EXPLOSION_SIZE * 2f
        var selfDamage = 0f
        var mostDamage = 0f

        val selfDist = MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY + 0.9f, EntityTracker.selfZ)
        if (selfDist <= diameter) {
            val exposure = exposureTo(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            selfDamage = explosionDamage(selfDist, diameter, exposure)
        }

        for (p in EntityTracker.getPlayers(diameter)) {
            if (p.runtimeId == EntityTracker.selfRuntimeId) continue
            val dist = MathUtil.dist3(cx, cy, cz, p.x, p.y + 0.9f, p.z)
            if (dist > diameter) continue
            val exposure = exposureTo(cx, cy, cz, p.x, p.y, p.z)
            val dmg = explosionDamage(dist, diameter, exposure)
            if (dmg > mostDamage) mostDamage = dmg
        }

        return ExplosionResult(mostDamage, selfDamage)
    }

    // Vanilla formülü: impact = (1 - mesafe/çap) * exposure
    // damage = (impact² + impact) / 2 * 7 * çap + 1
    private fun explosionDamage(distance: Float, diameter: Float, exposure: Float): Float {
        if (distance > diameter) return 0f
        val impact = (1f - distance / diameter) * exposure
        return (impact * impact + impact) / 2f * 7f * diameter + 1f
    }

    // Vanilla'nın 1287 ray'lik tam "getSeenPercent" hesabının basitleştirilmiş
    // hâli: hedefin gövdesinde 5 örnek noktaya (ayak/orta/kafa + iki yan) ray
    // atıp kaçının blok tarafından engellenmediğine bakıyoruz.
    private fun exposureTo(cx: Float, cy: Float, cz: Float, tx: Float, ty: Float, tz: Float): Float {
        if (!WorldBlockTracker.hasAnyTerrainData()) return 1f
        val samples = arrayOf(
            Triple(tx, ty + 0.1f, tz),
            Triple(tx, ty + 0.9f, tz),
            Triple(tx, ty + 1.6f, tz),
            Triple(tx + 0.3f, ty + 0.9f, tz),
            Triple(tx - 0.3f, ty + 0.9f, tz)
        )
        var clear = 0
        for ((sx, sy, sz) in samples) {
            if (!isRayBlocked(cx, cy, cz, sx, sy, sz)) clear++
        }
        return clear.toFloat() / samples.size
    }

    private fun isRayBlocked(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        val dist = MathUtil.dist3(x0, y0, z0, x1, y1, z1)
        if (dist < 0.01f) return false
        val steps = (dist * 2f).toInt().coerceIn(1, 40)
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val bx = floor(x0 + (x1 - x0) * t).toInt()
            val by = floor(y0 + (y1 - y0) * t).toInt()
            val bz = floor(z0 + (z1 - z0) * t).toInt()
            val id = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (id !in NON_SOLID) return true
        }
        return false
    }

    // ---------- Yerleştirme tabanı arama (searchPlaceBase portu) ----------
    // Not: orijinal kod gibi bu da menzil küpünü (2r+1)³ olarak tarıyor —
    // range=10'da ~9000 hücre. Aynı yaklaşım, aynı maliyet.
    private fun searchPlaceBase(): List<Triple<Int, Int, Int>> {
        if (!WorldBlockTracker.hasAnyTerrainData()) return emptyList()
        val r  = floor(range.value).toInt()
        val yr = searchYRange.value
        val cx = floor(EntityTracker.selfX).toInt()
        val cy = floor(EntityTracker.selfY).toInt()
        val cz = floor(EntityTracker.selfZ).toInt()
        val bases = ArrayList<Triple<Int, Int, Int>>()
        for (x in cx - r..cx + r) {
            for (y in cy - yr..cy + yr) {
                for (z in cz - r..cz + r) {
                    val id = WorldBlockTracker.getBlockIdentifier(x, y, z) ?: continue
                    if (id != "minecraft:obsidian" && id != "minecraft:bedrock") continue
                    val above = WorldBlockTracker.getBlockIdentifier(x, y + 1, z)
                    if (above != null && above !in NON_SOLID) continue
                    bases.add(Triple(x, y, z))
                }
            }
        }
        return bases
    }

    // ---------- Kristal yerleştirme / kırma paketleri ----------

    private fun explodeCrystal(session: RubidiumRelaySession, runtimeId: Long) {
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, runtimeId)
    }

    private fun prepareItemForUse(session: RubidiumRelaySession): PreparedItem? {
        // 1. Elinde kristal varsa onu kullan
        EntityTracker.getHeldItem()?.let { held ->
            if (InventoryUtil.resolveIdentifier(held) == "minecraft:end_crystal" && held.count > 0) {
                return PreparedItem(EntityTracker.selfHotbarSlot, held, null)
            }
        }
        
        // 2. Hotbar (0-8) tara
        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0 || InventoryUtil.resolveIdentifier(item) != "minecraft:end_crystal") continue
            if (slot == EntityTracker.selfHotbarSlot) {
                return PreparedItem(slot, item, null)
            }
            val original = EntityTracker.selfHotbarSlot
            InventoryUtil.sendHotbarSelect(session, slot)
            EntityTracker.selfHotbarSlot = slot
            return PreparedItem(slot, item, original)
        }
        return null
    }

    private fun sendPlacementUseRaw(session: RubidiumRelaySession, prepared: PreparedItem, blockPos: Vector3i): Boolean {
        // Fallback pozisyonlar için blockDef'i önce WorldTracker'dan al, yoksa obsidian kullan
        val targetId = if (WorldBlockTracker.hasAnyTerrainData()) {
            WorldBlockTracker.getBlockIdentifier(blockPos.x, blockPos.y, blockPos.z) ?: "minecraft:obsidian"
        } else {
            "minecraft:obsidian"
        }
        
        val blockDef = getBlockDefinition(session, targetId) ?: return false
        val playerPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                this.blockPosition       = blockPos
                blockFace                = 1
                hotbarSlot               = prepared.slot
                itemInHand               = prepared.item
                playerPosition           = playerPos
                clickPosition            = Vector3f.from(0.5f, 1.0f, 0.5f)
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    // Yerleştirilecek zeminin blockDefinition'ı — server'a ITEM_USE paketinde
    // gönderilmesi gerekiyor. Önce cache'i kontrol et, yoksa server'ın block
    // definitions'ını tara. Bulunamazsa targetId'ye göre fallback oluştur.
    private fun getBlockDefinition(session: RubidiumRelaySession, targetId: String = "minecraft:obsidian"): BlockDefinition? {
        blockDefCache[targetId]?.let { return it }
        try {
            val blockDefs = session.clientSession.peer.codecHelper.blockDefinitions
            if (blockDefs != null) {
                var i = 0; var misses = 0
                while (i < 20000 && misses < 64) {
                    val def = try { blockDefs.getDefinition(i) } catch (_: Exception) { null }
                    if (def == null) { misses++; i++; continue }
                    misses = 0
                    val id = (def as? SimpleBlockDefinition)?.identifier
                    if (id == targetId) { blockDefCache[targetId] = def; return def }
                    i++
                }
            }
        } catch (_: Exception) {}
        
        val runtimeId = when (targetId) {
            "minecraft:obsidian"    -> 49
            "minecraft:bedrock"     -> 7
            "minecraft:air"         -> 0
            else                    -> 49
        }
        
        val fallback = SimpleBlockDefinition(
            targetId, runtimeId,
            org.cloudburstmc.nbt.NbtMap.builder()
                .putString("name", targetId)
                .putCompound("states", org.cloudburstmc.nbt.NbtMap.builder().build())
                .build()
        )
        blockDefCache[targetId] = fallback
        return fallback
    }
}
