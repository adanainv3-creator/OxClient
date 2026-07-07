
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
import org.cloudburstmc.protocol.common.DefinitionRegistry
import java.util.concurrent.ConcurrentHashMap

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "End kristallerini otomatik yerleştirir ve patlatır"
) {
    enum class BreakMode     { Instant, Sequential, Closest }
    enum class PlaceMode     { Safe, Aggressive, Smart, Full5x5 }
    enum class TargetPriority{ Distance, Health, DamageRatio }

    private val autoPlace      = bool ("Auto Place",    true)
    private val autoBreak      = bool ("Auto Break",    true)
    private val breakMode      = enum ("Break Mode",    BreakMode.Instant)
    private val placeMode      = enum ("Place Mode",    PlaceMode.Full5x5)
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
    // ✅ FIX: Varsayılan artık KAPALI. AutoTotem ile birlikte kullanıldığında (klasik
    // combo) öz-hasar zaten totemle tank ediliyor. Açıkken (6 blok limit) yakın dövüşte
    // (rakibe 1-3 blok mesafede) hedefin etrafındaki HER pozisyon senin kendine de
    // 6 blok içinde kalıyor — yani antiSuicide sessizce HER yerleştirmeyi iptal
    // ediyordu (log'da hedef+obsidian bulunuyor ama hiç "yerleştirildi" satırı yoktu).
    // Totemin yoksa bu ayarı elle açabilirsin.
    private val antiSuicide    = bool ("Anti Suicide",  false)
    private val rotate         = bool ("Rotate",        true)
    private val shortcut       = bool ("Shortcut",      true)
    // ✅ YENİ: elde obsidian olması zorunluluğu artık AÇIK/KAPALI seçilebilir bir config.
    // Varsayılan false — elde ne olursa olsun (kılıç, boş el, vb.) yerleştirme paketi
    // gönderilir. Sunucu tarafında held-item validasyonu YOKSA (2b2tpe gibi) bu sorun
    // çıkarmaz; eğer ileride "yerleştirmiyor" şikayeti geri gelirse true yapıp obsidian'ı
    // elde tutarak test et — o zaman gerçekten held-item kontrolü olduğu kanıtlanmış olur.
    private val requireHeldItem = bool ("Require Obsidian In Hand", false)

    private val activeCrystals  = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    private val placedPositions = ConcurrentHashMap<Long, Long>()

    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var seqIndex = 0
    private var tickJob: Job? = null

    private var cachedObsidianDef: BlockDefinition? = null

    private companion object { const val TAG = "CrystalAura"; const val MAX_SCAN_RUNTIME_ID = 2000 }

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); placedPositions.clear()
        seqIndex = 0
        cachedObsidianDef = null

        // ✅ FIX: Modül kapalıyken zaten var olan (sunucudan önceden gelmiş)
        // kristaller de senkronize ediliyor. Eskiden sadece AddEntityPacket ile
        // dolan activeCrystals, enable ÖNCESİ var olan kristalleri hiç görmüyordu.
        EntityTracker.getAll().filter { it.isCrystal }.forEach { c ->
            activeCrystals[c.runtimeId] = Vector3f.from(c.x, c.y, c.z)
            uniqueToRuntime[c.uniqueId] = c.runtimeId
        }

        OverlayLogger.d(TAG, "Enabled: autoPlace=${autoPlace.value} autoBreak=${autoBreak.value} placeMode=${placeMode.value} breakMode=${breakMode.value} (mevcut kristal=${activeCrystals.size})")
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
                OverlayLogger.d(TAG, "AddEntity geldi: identifier=${pkt.identifier} runtimeId=${pkt.runtimeEntityId}")
                if (pkt.identifier.contains("crystal", ignoreCase = true)) {
                    activeCrystals[pkt.runtimeEntityId] = pkt.position
                    uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
                    OverlayLogger.d(TAG, "Crystal AddEntity: runtimeId=${pkt.runtimeEntityId} pos=${pkt.position} (toplam=${activeCrystals.size})")
                }
            }
            is RemoveEntityPacket -> {
                val rid = uniqueToRuntime.remove(pkt.uniqueEntityId)
                if (rid != null) {
                    activeCrystals.remove(rid)
                    OverlayLogger.d(TAG, "Crystal RemoveEntity: runtimeId=$rid (kalan=${activeCrystals.size})")
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
                if (System.currentTimeMillis() % 2000L < 10L) {
                    val nearest = EntityTracker.getEntitiesInRange(Float.MAX_VALUE).minByOrNull { EntityTracker.distanceTo(it) }
                    OverlayLogger.d(TAG, "self=(${EntityTracker.selfX},${EntityTracker.selfY},${EntityTracker.selfZ}) enYakın=${nearest?.identifier} dist=${nearest?.let { EntityTracker.distanceTo(it) }} activeCrystals=${activeCrystals.size}")
                }
                if (autoBreak.value) doBreak()

                val target = selectTarget()
                if (target != null) {
                    if (autoPlace.value) doPlace(target)
                } else if (System.currentTimeMillis() % 3000L < 10L) {
                    OverlayLogger.d(TAG, "tickLoop: place hedefi bulunamadı")
                }
            }
            delay(10L)
        }
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val r = if (throughWalls.value) wallsRange.value else placeRange.value
        // ✅ FIX: Eskiden EntityTracker.getEntitiesInRange(r) TÜM entity'leri
        // (zombi, item drop, kristalin kendisi dahil) hedef sayıyordu — rakip
        // yokken de kristal yerleştirmesine sebep oluyordu. Artık sadece gerçek
        // combat hedefleri (oyuncu/hostile mob) hedef olarak seçiliyor.
        val candidates = EntityTracker.getEntitiesInRange(r)
            .filter { (it.isPlayer || it.isHostile) && it.runtimeId != EntityTracker.selfRuntimeId }
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

    // ✅ FIX: Modern Bedrock'ta blok runtimeId'leri SABİT/legacy numaralar değil —
    // her sunucu kendi block_palette'ini StartGamePacket ile gönderir ve runtimeId
    // o paletteki SIRAYA göre atanır (bkz. BlockTracker.kt'deki aynı tespit).
    // Eski "obsidian=49" gibi tahminler modern per-session dinamik palette'te
    // hemen hemen HİÇBİR ZAMAN tutmaz — bu yüzden fallback hep devreye giriyor
    // ve gerçek yerleştirme sunucu tarafından reddediliyordu.
    //
    // Çözüm: tahmin etmek yerine, registry'yi identifier'a göre TARIYORUZ.
    // Önce (varsa) registry'nin kendi map'ini reflection ile doğrudan okumayı
    // deniyoruz (hızlı); olmazsa 0..MAX_SCAN_RUNTIME_ID aralığında brute-force
    // getDefinition() çağrısıyla arıyoruz (yavaş ama bir kere çalışıp cache'leniyor).
    private fun findByIdentifier(
        registry: DefinitionRegistry<BlockDefinition>,
        identifier: String
    ): BlockDefinition? {
        // Hızlı yol: bizim kendi NbtBlockDefinitionRegistry'mizse (Definitions.kt'den
        // gelen fallback ise) internal map'e reflection ile eriş, tüm girişleri tara.
        try {
            val mapField = registry.javaClass.getDeclaredField("map")
            mapField.isAccessible = true
            val map = mapField.get(registry)
            if (map is Map<*, *>) {
                for (value in map.values) {
                    val def = value as? BlockDefinition ?: continue
                    if (identifierOf(def) == identifier) return def
                }
                return null // map bulundu ama içinde yok — brute-force'a gerek yok
            }
        } catch (_: Exception) { /* reflection tutmadı, brute-force'a düş */ }

        // Yavaş yol: server'ın kendi (CloudburstMC) registry implementasyonu için
        // internal yapısını bilmiyoruz — runtimeId aralığında doğrudan sorgula.
        for (id in 0 until MAX_SCAN_RUNTIME_ID) {
            val def = registry.getDefinition(id) ?: continue
            if (identifierOf(def) == identifier) return def
        }
        return null
    }

    private fun getObsidianDefinition(session: OxRelaySession): BlockDefinition? {
        cachedObsidianDef?.let { return it }

        try {
            val clientDefs = session.clientSession.peer.codecHelper.blockDefinitions
            if (clientDefs != null) {
                findByIdentifier(clientDefs, "minecraft:obsidian")?.let {
                    cachedObsidianDef = it
                    OverlayLogger.d(TAG, "Obsidian definition bulundu (client registry): runtimeId=${it.runtimeId}")
                    return it
                }
            }

            val localDefs = Definitions.getClosestDefinitions(session.activeCodec.protocolVersion).blockDefinitions
            findByIdentifier(localDefs, "minecraft:obsidian")?.let {
                cachedObsidianDef = it
                OverlayLogger.d(TAG, "Obsidian definition bulundu (local fallback registry): runtimeId=${it.runtimeId}")
                return it
            }

            OverlayLogger.w(TAG, "Obsidian definition HİÇBİR registry'de bulunamadı (client ve local ikisi de tarandı) — fallback oluşturuluyor, PLACEMENT ÇALIŞMAYABİLİR")
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

        // ✅ FIX: Artık varsayılan olarak KAPALI — elde obsidian olsun ya da olmasın
        // yerleştirme paketi gönderilir. Sadece kullanıcı "Require Obsidian In Hand"ı
        // AÇARSA bu kontrol devreye girer.
        if (requireHeldItem.value && !isHoldingObsidian()) {
            OverlayLogger.w(TAG, "Hotbar 0'da obsidian yok — place atlanıyor (Require Obsidian In Hand açık)")
            return
        }

        when (placeMode.value) {
            PlaceMode.Safe -> doPlaceSafe(target, session, obsidianDef)
            PlaceMode.Aggressive -> doPlaceAggressive(target, session, obsidianDef)
            PlaceMode.Smart -> doPlaceSmart(target, session, obsidianDef)
            PlaceMode.Full5x5 -> doPlaceFull5x5(target, session, obsidianDef)
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

    /**
     * ✅ YENİ: Hedefin 5x5'lik (dx,dz: -2..2) alanındaki TÜM yerleştirilebilir
     * konumları dener — sadece köşe/kenar gibi sabit bir örüntü değil, tüm alan.
     * En yakın pozisyonlar önce denenir (hedefe en yakın crystal en çok hasar verir).
     */
    private fun doPlaceFull5x5(target: EntityTracker.TrackedEntity, session: OxRelaySession, obsidianDef: BlockDefinition) {
        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()
        var placed = 0
        val range = if (throughWalls.value) wallsRange.value else placeRange.value

        val positions = mutableListOf<Triple<Int, Int, Int>>()
        for (dx in -2..2) for (dz in -2..2) {
            positions.add(Triple(tx + dx, ty + 1, tz + dz))
        }
        // Hedefe en yakın pozisyonlar önce denensin.
        positions.sortBy { (bx, _, bz) -> (bx - tx) * (bx - tx) + (bz - tz) * (bz - tz) }

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

    /** Hotbar 0'da GERÇEKTEN obsidian tutulup tutulmadığını item registry üzerinden doğrular. */
    private fun isHoldingObsidian(): Boolean {
        val held = EntityTracker.getInventoryItem(0) ?: return false
        val identifier = runCatching { held.definition?.identifier }.getOrNull()
        return identifier == "minecraft:obsidian"
    }

    private fun tryPlaceCrystal(
        bx: Int, by: Int, bz: Int,
        session: OxRelaySession,
        obsidianDef: BlockDefinition,
        range: Float
    ): Boolean {
        val bKey = packKey(bx, by, bz)
        if (placedPositions.containsKey(bKey)) return false

        // ✅ FIX: activeCrystals yerine doğrudan EntityTracker'a bakılıyor — bu sayede
        // modül enable edilmeden önce zaten var olan kristaller de "zaten dolu" olarak
        // doğru tespit ediliyor (activeCrystals'ın kapsamadığı durum).
        val alreadyPlaced = EntityTracker.getAll().any { c ->
            c.isCrystal &&
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
            OverlayLogger.d(TAG, "Crystal yerleştirildi: ($bx,$by,$bz)")

        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Crystal yerleştirme hatası: ${e.message}")
            return false
        }

        return true
    }

    // ──── DO BREAK ──────────────────────────────────────────────────────────

    private fun doBreak() {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < breakDelay.value) return
        lastBreakMs = now

        val session = PacketEventBus.currentSession ?: run {
            if (System.currentTimeMillis() % 5000L < 10L) OverlayLogger.w(TAG, "doBreak: session null")
            return
        }

        // ✅ FIX (asıl istenen davranış): activeCrystals yerine doğrudan EntityTracker
        // kullanılıyor. EntityTracker, CrystalAura'nın enable/disable durumundan bağımsız
        // olarak TÜM ender_crystal entity'lerini (ne zaman spawn olursa olsun — modül
        // açılmadan önce yerleştirilmiş, başkası tarafından konulmuş, vs.) takip ediyor.
        // Eski kod sadece kendi activeCrystals map'ine bakıyordu ve bu map SADECE modül
        // açıkken gelen AddEntityPacket ile doluyordu — önceden var olan kristaller hiç
        // görünmüyordu (log'daki "activeCrystals=0" tekrarlarının sebebi buydu).
        val sorted = EntityTracker.getCrystals(breakRange.value)
            .sortedBy { EntityTracker.distanceTo(it) }

        if (sorted.isEmpty()) return

        when (breakMode.value) {
            BreakMode.Instant -> {
                var b = 0
                for (entity in sorted) {
                    if (b++ >= maxBreak.value) break
                    attackCrystalEntity(entity, session)
                }
                OverlayLogger.d(TAG, "doBreak: ${b} crystal kırıldı (Instant)")
            }
            BreakMode.Sequential -> {
                seqIndex %= sorted.size
                val entity = sorted[seqIndex]
                attackCrystalEntity(entity, session)
                seqIndex++
                OverlayLogger.d(TAG, "doBreak: 1 crystal kırıldı (Sequential #$seqIndex)")
            }
            BreakMode.Closest -> {
                sorted.firstOrNull()?.let { entity ->
                    attackCrystalEntity(entity, session)
                    OverlayLogger.d(TAG, "doBreak: 1 crystal kırıldı (Closest)")
                }
            }
        }
    }

    private fun attackCrystalEntity(entity: EntityTracker.TrackedEntity, session: OxRelaySession) {
        if (rotate.value) {
            val r = RotationUtil.toPoint(entity.x, entity.y, entity.z)
            PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        }
        PacketUtil.sendSwingAndAttack(session, entity.runtimeId)
    }

    private fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}

