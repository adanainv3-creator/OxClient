package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.Definitions
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import com.oxclient.utils.WorldBlockTracker
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
import org.cloudburstmc.protocol.common.DefinitionRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "Rakip oyuncunun etrafına otomatik kristal yerleştirir ve patlatır"
) {
    enum class Mode { Single, Full5x5, Square5, Nuke }

    private val range           = float("Range",          5f,   3f,  10f)
    private val placeRange      = float("Place Range",    4.5f, 2f,   8f)
    private val breakRange      = float("Break Range",    6f,   3f,  10f)
    private val suicide         = bool ("Suicide",        false)
    private val minSelfDistance = float("Min Self Distance", 3f, 0f, 5f)
    private val place           = bool ("Place",          true)
    private val breakCrystals   = bool ("Break",          true)
    private val placeDelay      = int  ("Place Delay",    100,  20,  500)
    private val breakDelay      = int  ("Break Delay",    50,   20,  500)
    private val removeParticles = bool ("RemoveParticles",true)
    private val placeMode       = enum ("Place Mode",     Mode.Single)
    private val breakMode       = enum ("Break Mode",     Mode.Single)
    private val noSwitch        = bool ("No Switch",      false)
    private val autoObby        = bool ("Auto Obby",      false)
    private val autoObbyDelay   = int  ("Auto Obby Delay", 200, 50, 1000)
    private val multiTarget     = bool ("Multi Target",   false)
    private val smartBreakRange = bool ("Smart Break Range", false)
    private val smartBreakBoost = float("Smart Break Boost", 2f, 0f, 5f)
    private val autoReplace     = bool ("Auto Re-Place",  false)
    // Bir pozisyona koyma denemesi sunucudan onay (AddEntityPacket) almazsa
    // ne kadar süre sonra aynı yere tekrar denesin. Eskiden sabit 3000ms'ydi;
    // bu yüzden ilk deneme reddedilince 3sn boyunca "yerleşmiyormuş" gibi
    // görünüyordu. Artık ayarlanabilir, düşürerek gecikmeyi azaltabilirsin.
    private val placeRetryMs    = int  ("Place Retry Ms", 400,  100, 3000)

    private val activeCrystals  = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    // Biz koyduğumuz pozisyonlar → AddEntityPacket gelince temizlenir
    private val pendingPositions = ConcurrentHashMap<Long, Long>()
    // Bu pozisyona hangi block id ile kristal koyduğumuzu tutar (Auto Re-Place için)
    private val placedBlockIds = ConcurrentHashMap<Long, String>()

    // Multi Target'ta her hedefin kendi cooldown'u olsun diye runtimeId bazlı map
    private val lastPlaceMsMap = ConcurrentHashMap<Long, Long>()
    private val lastBreakMsMap = ConcurrentHashMap<Long, Long>()
    @Volatile private var lastObbyMs  = 0L
    private var tickJob: Job? = null

    private val blockDefCache = ConcurrentHashMap<String, BlockDefinition>()

    private val shortcut = bool("Shortcut", false)

    // Elde tutmadan koyabilmek için: hotbar'ı tarayıp end_crystal olan slotu bulur.
    // noSwitch kapalıysa sadece o an elde tutulan item'ı kontrol eder (eski davranış).
    private fun resolveCrystalSlot(): Pair<Int, ItemData>? {
        if (!noSwitch.value) {
            val held = EntityTracker.getHeldItem() ?: return null
            if (held.count <= 0) return null
            val id = runCatching { held.definition?.identifier }.getOrNull()
            if (id != "minecraft:end_crystal") return null
            return EntityTracker.selfHotbarSlot to held
        }

        // Önce mevcut elde tutulan slotu dene (gereksiz slot bilgisi göndermemek için)
        EntityTracker.getHeldItem()?.let { held ->
            val id = runCatching { held.definition?.identifier }.getOrNull()
            if (id == "minecraft:end_crystal" && held.count > 0) {
                return EntityTracker.selfHotbarSlot to held
            }
        }

        // Elde yoksa hotbar'ın tamamını tara (0..8)
        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull()
            if (id == "minecraft:end_crystal") return slot to item
        }
        return null
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); pendingPositions.clear()
        blockDefCache.clear(); placedBlockIds.clear()
        lastPlaceMsMap.clear(); lastBreakMsMap.clear()
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        activeCrystals.clear(); uniqueToRuntime.clear(); pendingPositions.clear()
        placedBlockIds.clear(); lastPlaceMsMap.clear(); lastBreakMsMap.clear()
    }

    // ── Packet handling ───────────────────────────────────────────────────────

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is AddEntityPacket -> {
                if (pkt.identifier.contains("crystal", ignoreCase = true)) {
                    activeCrystals[pkt.runtimeEntityId] = pkt.position
                    uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
                    // Sunucu kristali kabul etti → pending temizle
                    val key = posKey(
                        floor(pkt.position.x - 0.5f).toInt(),
                        floor(pkt.position.y - 1f).toInt(),
                        floor(pkt.position.z - 0.5f).toInt()
                    )
                    pendingPositions.remove(key)
                }
            }
            is RemoveEntityPacket -> {
                val rid = uniqueToRuntime.remove(pkt.uniqueEntityId)
                if (rid != null) {
                    val pos = activeCrystals.remove(rid)
                    if (autoReplace.value && place.value && pos != null) {
                        val bx = floor(pos.x - 0.5f).toInt()
                        val by = floor(pos.y - 1f).toInt()
                        val bz = floor(pos.z - 0.5f).toInt()
                        val key = posKey(bx, by, bz)
                        val blockId = placedBlockIds[key] ?: "minecraft:obsidian"
                        // Eski bookkeeping'i temizle, hemen aynı yere tekrar koymayı dene
                        pendingPositions.remove(key)
                        tryPlaceAt(event.session, PlacePos(bx, by, bz, blockId), ignorePending = true)
                    }
                }
            }
            is LevelEventPacket -> {
                if (removeParticles.value) {
                    if (pkt.type == LevelEvent.PARTICLE_EXPLOSION ||
                        pkt.type == LevelEvent.PARTICLE_BLOCK_EXPLOSION) {
                        event.cancel()
                    }
                }
            }
            else -> {}
        }
    }

    // ── Tick loop ─────────────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                if (autoObby.value) doAutoObby()

                val targets = if (multiTarget.value) selectTargets() else listOfNotNull(selectTarget())
                for (target in targets) {
                    if (placeMode.value == Mode.Nuke) {
                        doNuke(target)
                    } else {
                        if (breakCrystals.value) doBreak(target)
                        if (place.value)         doPlace(target)
                    }
                }
            }
            delay(1L)
        }
    }

    // ── Target selection ──────────────────────────────────────────────────────

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val rangeSq = range.value * range.value
        var closest: EntityTracker.TrackedEntity? = null
        var closestDistSq = Float.MAX_VALUE
        for (e in EntityTracker.getAll()) {
            if (!e.isPlayer) continue
            if (e.runtimeId == EntityTracker.selfRuntimeId) continue
            val dx = e.x - EntityTracker.selfX
            val dy = e.y - EntityTracker.selfY
            val dz = e.z - EntityTracker.selfZ
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < rangeSq && distSq < closestDistSq) {
                closestDistSq = distSq
                closest = e
            }
        }
        return closest
    }

    // Multi Target: menzildeki TÜM oyuncuları yakınlık sırasına göre döner
    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val rangeSq = range.value * range.value
        return EntityTracker.getAll()
            .asSequence()
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { e ->
                val dx = e.x - EntityTracker.selfX
                val dy = e.y - EntityTracker.selfY
                val dz = e.z - EntityTracker.selfZ
                dx*dx + dy*dy + dz*dz <= rangeSq
            }
            .sortedBy { e ->
                val dx = e.x - EntityTracker.selfX
                val dy = e.y - EntityTracker.selfY
                val dz = e.z - EntityTracker.selfZ
                dx*dx + dy*dy + dz*dz
            }
            .toList()
    }

    // ── Place ─────────────────────────────────────────────────────────────────

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        val last = lastPlaceMsMap[target.runtimeId] ?: 0L
        if (now - last < placeDelay.value) return

        val session  = PacketEventBus.currentSession ?: return
        if (resolveCrystalSlot() == null) return

        val positions = getPlacePositions(target)
        if (positions.isEmpty()) return

        lastPlaceMsMap[target.runtimeId] = now

        for (pos in positions) tryPlaceAt(session, pos)
    }

    // Tek bir pozisyona kristal koymayı dener. Auto Re-Place ve doPlace/doNuke
    // arasında ortak kullanılır.
    private fun tryPlaceAt(session: OxRelaySession, pos: PlacePos, ignorePending: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val key = posKey(pos.x, pos.y, pos.z)

        if (!ignorePending) {
            val pendingTime = pendingPositions[key]
            if (pendingTime != null) {
                if (now - pendingTime < placeRetryMs.value) return false
                else pendingPositions.remove(key)
            }
        }

        if (crystalExistsAt(pos.x, pos.y, pos.z)) return false

        val centerX = pos.x + 0.5f
        val centerY = pos.y + 1.0f
        val centerZ = pos.z + 0.5f

        val distSelf = MathUtil.dist3(
            centerX, centerY, centerZ,
            EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ
        )
        if (distSelf > placeRange.value) return false
        if (!suicide.value && distSelf < minSelfDistance.value) return false

        val (crystalSlot, heldItem) = resolveCrystalSlot() ?: return false
        val blockDef = getBlockDefinition(session, pos.blockId) ?: return false

        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                blockPosition            = Vector3i.from(pos.x, pos.y, pos.z)
                blockFace                = 1
                hotbarSlot               = crystalSlot
                itemInHand               = heldItem
                playerPosition           = Vector3f.from(
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                clickPosition            = Vector3f.from(0.5f, 1.0f, 0.5f)
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
            pendingPositions[key] = now
            placedBlockIds[key] = pos.blockId
            true
        } catch (_: Exception) { false }
    }

    // ── Nuke (delay'siz, koyabildiği her yer) ──────────────────────────────────

    private fun doNuke(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        if (place.value && resolveCrystalSlot() != null) {
            for (pos in getNukePositions(target)) tryPlaceAt(session, pos)
        }

        if (breakCrystals.value) {
            val bRangeSq = effectiveBreakRange(target).let { it * it }
            val inRange = activeCrystals.entries.filter { (_, pos) ->
                val dx = pos.x - target.x; val dy = pos.y - target.y; val dz = pos.z - target.z
                dx*dx + dy*dy + dz*dz <= bRangeSq
            }
            for ((rid, _) in inRange) {
                attackCrystal(rid, session)
                activeCrystals.remove(rid)
            }
        }
    }

    // Placement radius'u placeRange'e göre hesaplar — koyabileceği her sütunu tarar
    private fun getNukePositions(target: EntityTracker.TrackedEntity): List<PlacePos> {
        val result = mutableListOf<PlacePos>()
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()
        val radius = kotlin.math.ceil(placeRange.value).toInt().coerceAtLeast(1)

        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val bx = tx + dx
                val bz = tz + dz
                val hit = findSurface(bx, ty, bz) ?: continue
                result.add(PlacePos(bx, hit.y, bz, hit.blockId))
            }
        }
        return result
    }

    // Aktif kristal map'inde bu blok pozisyonuna karşılık gelen kristal var mı?
    private fun crystalExistsAt(bx: Int, by: Int, bz: Int): Boolean {
        val cx = bx + 0.5f
        val cy = by + 1.0f
        val cz = bz + 0.5f
        return activeCrystals.values.any { v ->
            Math.abs(v.x - cx) < 0.9f &&
            Math.abs(v.y - cy) < 1.3f &&
            Math.abs(v.z - cz) < 0.9f
        }
    }

    // ── Place position search ─────────────────────────────────────────────────

    private fun getPlacePositions(target: EntityTracker.TrackedEntity): List<PlacePos> {
        if (placeMode.value == Mode.Square5) return getSquare5Positions(target)

        val result = mutableListOf<PlacePos>()
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()
        val radius = if (placeMode.value == Mode.Full5x5) 2 else 1

        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val bx = tx + dx
                val bz = tz + dz

                val hit = findSurface(bx, ty, bz) ?: continue

                // Kristal hedefe ne kadar yakın?
                val distToTarget = MathUtil.dist3(
                    bx + 0.5f, hit.y + 1f, bz + 0.5f,
                    target.x, target.y + 1f, target.z
                )
                if (distToTarget > 5f) continue

                result.add(PlacePos(bx, hit.y, bz, hit.blockId))
            }
        }
        return result
    }

    // Square5 modu: rakibin önü/arkası/sağı/solu — 5 blok mesafede kare bir
    // çerçeve (perimeter) oluşturacak şekilde kristal koyar. Full5x5'in aksine
    // içi dolu değil, sadece |dx|==5 veya |dz|==5 olan dış hat.
    private fun getSquare5Positions(target: EntityTracker.TrackedEntity): List<PlacePos> {
        val result = mutableListOf<PlacePos>()
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()
        val r = 5

        for (dx in -r..r) {
            for (dz in -r..r) {
                // Sadece karenin dış çizgisi: en az bir eksende tam 5 blok uzakta olmalı
                if (kotlin.math.abs(dx) != r && kotlin.math.abs(dz) != r) continue

                val bx = tx + dx
                val bz = tz + dz
                val hit = findSurface(bx, ty, bz) ?: continue
                result.add(PlacePos(bx, hit.y, bz, hit.blockId))
            }
        }
        return result
    }

    private data class SurfaceHit(val y: Int, val blockId: String)
    private data class PlacePos(val x: Int, val y: Int, val z: Int, val blockId: String)

    /**
     * Verilen (bx, bz) sütununda kristal koyulabilecek yüzeyi bulur.
     * WorldBlockTracker veri döndürmüyorsa (chunk parse olmamış) sadece
     * rakibin Y'sinin altından yukarı doğru obsidian/bedrock arar.
     * Veri yoksa rakibin ayak Y'sinin bir altını fallback olarak döndürür —
     * böylece WorldBlockTracker hazır olmasa da çalışmaya devam eder.
     */
    private fun findSurface(bx: Int, ty: Int, bz: Int): SurfaceHit? {
        val hasData = WorldBlockTracker.hasAnyTerrainData()

        if (hasData) {
            // Chunk verisi var: obsidian/bedrock yüzey ara
            var sawAnyResolvedBlock = false
            for (by in (ty - 3)..(ty + 2)) {
                val here = WorldBlockTracker.getBlockIdentifier(bx, by, bz)
                if (here != null) sawAnyResolvedBlock = true
                if (here == null) continue
                if (here != "minecraft:obsidian" && here != "minecraft:bedrock") continue

                val above  = WorldBlockTracker.getBlockIdentifier(bx, by + 1, bz)
                val above2 = WorldBlockTracker.getBlockIdentifier(bx, by + 2, bz)
                // null = chunk verisi yok → air kabul et
                val clear1 = above  == null || above  == "minecraft:air"
                val clear2 = above2 == null || above2 == "minecraft:air"
                if (!clear1 || !clear2) continue

                return SurfaceHit(by, here)
            }

            // Güvenlik ağı: bu sütunda TEK bir blok bile resolve edilemediyse
            // (registry uyuşmazlığı / decode sorunu), gerçek veriye güvenme —
            // naive fallback'e düş. Böylece registry sorunları modülü
            // tamamen durdurmaz, sadece "yerleştirmesin" yerine "yine dener" olur.
            if (!sawAnyResolvedBlock) {
                return SurfaceHit(ty - 1, "minecraft:obsidian")
            }
            return null
        } else {
            // Chunk verisi hiç yok: rakibin ayak Y'sinin altını obsidian varsayarak fallback yap
            // Sunucu paketi reddederse pendingPositions timeout'u ile zaten geçiyoruz
            return SurfaceHit(ty - 1, "minecraft:obsidian")
        }
    }

    // ── Break ─────────────────────────────────────────────────────────────────

    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        val last = lastBreakMsMap[target.runtimeId] ?: 0L
        if (now - last < breakDelay.value) return

        val session = PacketEventBus.currentSession ?: return
        val bRangeSq = effectiveBreakRange(target).let { it * it }

        val inRange = activeCrystals.entries.filter { (_, pos) ->
            val dx = pos.x - target.x
            val dy = pos.y - target.y
            val dz = pos.z - target.z
            dx*dx + dy*dy + dz*dz <= bRangeSq
        }
        if (inRange.isEmpty()) return

        lastBreakMsMap[target.runtimeId] = now

        when (breakMode.value) {
            Mode.Single -> {
                val nearest = inRange.minByOrNull { (_, pos) ->
                    val dx = pos.x - target.x; val dy = pos.y - target.y; val dz = pos.z - target.z
                    dx*dx + dy*dy + dz*dz
                }
                nearest?.let { (rid, _) ->
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
            }
            Mode.Full5x5, Mode.Square5, Mode.Nuke -> {
                for ((rid, _) in inRange) {
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
            }
        }
    }

    // Smart Break Range: hedefin canı düştükçe kırma menzilini genişletir,
    // böylece kaçmaya çalışan düşük canlı bir rakibi bitirmek daha kolay olur.
    // Not: TrackedEntity'de maxHealth alanı yoksa vanilla varsayılan 20f kullanılıyor;
    // gerçek maxHealth alanın varsa haber ver, ona göre değiştiririm.
    private fun effectiveBreakRange(target: EntityTracker.TrackedEntity): Float {
        if (!smartBreakRange.value) return breakRange.value
        val assumedMaxHealth = 20f
        val healthFraction = (target.health / assumedMaxHealth).coerceIn(0f, 1f)
        return breakRange.value + (1f - healthFraction) * smartBreakBoost.value
    }

    private fun attackCrystal(rid: Long, session: OxRelaySession) {
        val pos = activeCrystals[rid] ?: return
        val r = RotationUtil.toPoint(pos.x, pos.y, pos.z)
        PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        PacketUtil.sendSwingAndAttack(session, rid)
    }

    // ── Auto Obby (savunma) ───────────────────────────────────────────────────

    private fun resolveObsidianSlot(): Pair<Int, ItemData>? {
        EntityTracker.getHeldItem()?.let { held ->
            val id = runCatching { held.definition?.identifier }.getOrNull()
            if (id == "minecraft:obsidian" && held.count > 0) {
                return EntityTracker.selfHotbarSlot to held
            }
        }
        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull()
            if (id == "minecraft:obsidian") return slot to item
        }
        return null
    }

    // Ayak seviyesinde kendi etrafına (4 yön: +x,-x,+z,-z) obsidian koyar.
    // Kristal patlamasının yatay knockback/hasarını kesmek için.
    private fun doAutoObby() {
        val now = System.currentTimeMillis()
        if (now - lastObbyMs < autoObbyDelay.value) return

        val session = PacketEventBus.currentSession ?: return
        val (slot, item) = resolveObsidianSlot() ?: return

        val fx = floor(EntityTracker.selfX).toInt()
        // Düzeltme: floor(selfY) burada gerçek ayak bloğundan 1 fazla çıkıyordu,
        // bu yüzden obsidian hep 1 blok yukarıya konuyordu. 1 çıkararak
        // gerçek ayak seviyesine hizalıyoruz.
        val fy = floor(EntityTracker.selfY).toInt() - 1
        val fz = floor(EntityTracker.selfZ).toInt()
        val hasData = WorldBlockTracker.hasAnyTerrainData()

        lastObbyMs = now

        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val tx = fx + dx
            val tz = fz + dz
            val ty = fy          // koyulacak boşluk: ayak seviyesi
            val supportY = ty - 1 // basacağı zemin

            // Hedef pozisyon zaten dolu mu?
            val existing = if (hasData) WorldBlockTracker.getBlockIdentifier(tx, ty, tz) else null
            if (existing != null && existing != "minecraft:air") continue

            // Destek (zemin) bloğu — veri yoksa kendi ayağının altındaki gibi zemin varsay
            val supportId = (if (hasData) WorldBlockTracker.getBlockIdentifier(tx, supportY, tz) else null)
                ?: "minecraft:obsidian"
            if (supportId == "minecraft:air") continue

            val blockDef = getBlockDefinition(session, supportId) ?: continue

            try {
                session.serverBound(InventoryTransactionPacket().apply {
                    transactionType          = InventoryTransactionType.ITEM_USE
                    actionType               = 0
                    blockPosition            = Vector3i.from(tx, supportY, tz)
                    blockFace                = 1
                    hotbarSlot               = slot
                    itemInHand               = item
                    playerPosition           = Vector3f.from(
                        EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                    clickPosition            = Vector3f.from(0.5f, 1.0f, 0.5f)
                    blockDefinition          = blockDef
                    triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                    clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                    clientCooldownState      = 0
                })
            } catch (_: Exception) {}
        }
    }

    // ── Block definition ──────────────────────────────────────────────────────

    private fun getBlockDefinition(session: OxRelaySession, targetId: String): BlockDefinition? {
        blockDefCache[targetId]?.let { return it }
        try {
            fun scanDefs(defs: DefinitionRegistry<BlockDefinition>?): BlockDefinition? {
                defs ?: return null
                for (i in 0..4096) {
                    val def = try { defs.getDefinition(i) } catch (_: Exception) { null } ?: continue
                    val id  = when (def) {
                        is SimpleBlockDefinition -> def.identifier
                        is Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
                        else -> null
                    }
                    if (id == targetId) return def
                }
                return null
            }
            scanDefs(session.clientSession.peer.codecHelper.blockDefinitions)?.let {
                blockDefCache[targetId] = it; return it
            }
            scanDefs(Definitions.getClosestDefinitions(session.activeCodec.protocolVersion).blockDefinitions)?.let {
                blockDefCache[targetId] = it; return it
            }
        } catch (_: Exception) {}

        // Fallback: registry'de bulunamazsa bilinen legacy numeric ID'lerle dene
        val fallbackRuntimeId = when (targetId) {
            "minecraft:obsidian" -> 49
            "minecraft:bedrock"  -> 7
            else -> return null
        }
        val fallback = SimpleBlockDefinition(
            targetId, fallbackRuntimeId,
            org.cloudburstmc.nbt.NbtMap.builder()
                .putString("name", targetId)
                .putCompound("states", org.cloudburstmc.nbt.NbtMap.builder().build())
                .build()
        )
        blockDefCache[targetId] = fallback
        return fallback
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun posKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
