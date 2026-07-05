package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
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

    private companion object { const val TAG = "CrystalAura" }

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); placedPositions.clear()
        seqIndex = 0
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
                    OverlayLogger.v(TAG, "Crystal AddEntity yakalandı: runtimeId=${pkt.runtimeEntityId} pos=${pkt.position} (toplam=${activeCrystals.size})")
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
                val typeStr = pkt.type?.toString()?.uppercase() ?: ""
                if (typeStr.contains("PARTICLE") || typeStr.contains("EXPLOSION")) {
                    event.cancel()
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
                    OverlayLogger.v(TAG, "tickLoop: hedef bulunamadı (placeRange=${placeRange.value} breakRange=${breakRange.value} throughWalls=${throughWalls.value})")
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

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return
        lastPlaceMs = now
        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "doPlace: session null — relay bağlı değil")
            return
        }
        val tx = target.x.toInt(); val ty = target.y.toInt(); val tz = target.z.toInt()
        var placed = 0
        val range = if (throughWalls.value) wallsRange.value else placeRange.value

        outer@ for (dx in -2..2) for (dz in -2..2) for (dy in -1..1) {
            if (placed >= maxPlace.value) break@outer
            val bx = tx + dx; val by = ty + dy; val bz = tz + dz
            val bKey = packKey(bx, by, bz)
            if (placedPositions.containsKey(bKey)) continue
            val alreadyPlaced = activeCrystals.values.any { c ->
                Math.abs(c.x - bx - 0.5f) < 0.5f &&
                Math.abs(c.y - by - 1f)   < 0.5f &&
                Math.abs(c.z - bz - 0.5f) < 0.5f
            }
            if (alreadyPlaced) continue
            if (MathUtil.dist3(bx.toFloat(), by.toFloat(), bz.toFloat(),
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) > range) continue
            if (antiSuicide.value) {
                val selfDist = MathUtil.dist3(bx + 0.5f, by + 1f, bz + 0.5f,
                    EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ)
                if (selfDist < selfDmgLimit.value) continue
            }
            if (rotate.value) {
                val r = RotationUtil.toPoint(bx + 0.5f, by.toFloat(), bz + 0.5f)
                PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
            }
            // ✅ FIX2: itemInHand hâlâ null'du (encode'da NPE riski) + v712/v944'te
            // zorunlu olan triggerType/clientInteractPrediction/clientCooldownState
            // hiç set edilmiyordu. blockDefinition için gerçek bir dünya/chunk blok
            // sorgu sistemi şu an codebase'de yok (BlockTracker sadece sandık/spawner
            // gibi özel blokları takip ediyor) — bu yüzden burada obsidian'a sabit bir
            // fallback kullanıyoruz. Bu satır GERÇEK blockDefinition sorgusuyla
            // değiştirilmeli, aksi halde bazı sunucular yine reddedebilir.
            val heldItem = EntityTracker.getInventoryItem(0) ?: ItemData.AIR
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType      = 0 // 0 = CLICK_BLOCK (place)
                triggerType     = ItemUseTransaction.TriggerType.PLAYER_INPUT
                blockPosition   = org.cloudburstmc.math.vector.Vector3i.from(bx, by, bz)
                blockFace       = 1 // yukarı yüz
                hotbarSlot      = 0
                itemInHand      = heldItem
                clickPosition   = Vector3f.from(0.5f, 1f, 0.5f)
                playerPosition  = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                // TODO: gerçek chunk/blok sorgusu eklenmeli — Definitions.kt/BlockDefinition
                // API'sini görmeden buraya güvenilir bir değer yazamam. Şimdilik boş
                // bırakıyorum: eğer sunucu bunu null kabul etmiyorsa (NPE riski hâlâ var),
                // Definitions.kt'yi atarsan tam çözeriz.
                // blockDefinition = ???
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
            placedPositions[bKey] = now
            placed++
        }
        if (placed > 0) OverlayLogger.v(TAG, "doPlace: $placed kristal yerleştirme paketi gönderildi (target=${target.runtimeId})")
        else OverlayLogger.v(TAG, "doPlace: uygun blok pozisyonu bulunamadı (range=$range antiSuicide=${antiSuicide.value})")
    }

    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < breakDelay.value) return
        lastBreakMs = now
        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "doBreak: session null — relay bağlı değil")
            return
        }
        val sorted = activeCrystals.entries
            .filter { (_, p) -> MathUtil.dist3(p.x, p.y, p.z,
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) <= breakRange.value }
            .sortedBy { (_, p) -> MathUtil.dist3(p.x, p.y, p.z, target.x, target.y, target.z) }

        if (sorted.isEmpty()) {
            OverlayLogger.v(TAG, "doBreak: range içinde kırılacak crystal yok (breakRange=${breakRange.value}, activeCrystals=${activeCrystals.size})")
        }

        when (breakMode.value) {
            BreakMode.Instant -> {
                var b = 0
                for ((rid, _) in sorted) {
                    if (b++ >= maxBreak.value) break
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
            }
            BreakMode.Sequential -> {
                if (sorted.isEmpty()) { seqIndex = 0; return }
                seqIndex %= sorted.size
                val (rid, _) = sorted[seqIndex]
                attackCrystal(rid, session); activeCrystals.remove(rid); seqIndex++
            }
            BreakMode.Closest -> {
                sorted.firstOrNull()?.let { (rid, _) ->
                    attackCrystal(rid, session); activeCrystals.remove(rid)
                }
            }
        }
    }

    private fun attackCrystal(rid: Long, session: com.oxclient.core.relay.OxRelaySession) {
        if (rotate.value) {
            val pos = activeCrystals[rid] ?: return
            val r   = RotationUtil.toPoint(pos.x, pos.y, pos.z)
            PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        }
        PacketUtil.sendSwingAndAttack(session, rid)
    }

    private fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
