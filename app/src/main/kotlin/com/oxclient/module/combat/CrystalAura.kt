package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.Definitions
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.module.social.isFriendEntity
import com.oxclient.utils.CritLock
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import com.oxclient.utils.WorldBlockTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.max

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "Çok hedefli otomatik kristal yerleştirme/kırma — self-koruma, kritik ve LOS kontrolüyle"
) {
    enum class CritMode      { None, MovePacket, Vanilla }
    enum class PlaceStrategy { Closest, HighestDamage, MultiTarget }

    companion object {
        private const val CRYSTAL_POWER   = 6f
        private const val MIN_DAMAGE      = 0.5f
        private const val SEARCH_RADIUS   = 2
        private const val SELF_EYE_HEIGHT = 1.62f
        private const val MAX_BREAKS_PER_TICK = 3
        private const val MAX_PLACES_PER_TICK = 4
    }

    // --- Genel ---
    private val place            = bool("Place",              true)
    private val breakCrystals    = bool("Break",               true)
    private val strategy         = enum("Strategy",            PlaceStrategy.MultiTarget)
    private val shortcut         = bool("Shortcut",            true)
    private val tickIntervalMs   = int ("Tick Interval",       40, 20, 200)

    // --- Hedefleme ---
    private val targetRange      = int ("Target Range",   14, 4, 30)
    private val friendSkip       = bool("Friend Skip",           true)
    private val predictMovement  = bool("Predict Movement",  true)
    private val predictAheadMs   = int ("Predict Ahead Ms", 150, 0, 500)

    // --- Yerleştirme ---
    private val placeRange       = int ("Place Range",      8, 2, 16)
    private val placeDelayMs     = int ("Place Delay",     120, 40, 2000)
    private val placeRetryMs     = int ("Place Retry Ms",  400, 100, 3000)
    private val maxPlacePerSec   = int ("Max Place/Sec",     8, 1, 30)
    private val minDamageX10     = int ("Min Damage x10",    5, 0, 200)
    private val minDamage: Float get() = minDamageX10.value / 10f
    private val searchYBelow     = int ("Search Y Below",     3, 1, 8)
    private val searchYAbove     = int ("Search Y Above",     2, 1, 8)
    private val requireLOS       = bool("Require LOS",         false)

    // --- Self koruma ---
    private val maxSelfDamageX10 = int ("Max Self Damage x10", 90, 0, 200)
    private val maxSelfDamage: Float get() = maxSelfDamageX10.value / 10f
    private val suicide          = bool("Suicide",              false)

    // --- Burst (düşük can / totem sonrası) ---
    private val burstOnLowHpTotem   = bool("Burst LowHp/Totem",    true)
    private val burstHealth         = int ("Burst Health Threshold", 8, 1, 20)
    private val burstMaxPlacePerSec = int ("Burst Max Place/Sec",   15, 1, 40)
    private val burstDurationMs     = int ("Burst Duration Ms",   1500, 200, 5000)

    // --- Obsidian ---
    private val autoObsidian        = bool("Auto Obsidian",        true)
    private val autoObsidianDelayMs = int ("Auto Obsidian Delay", 180, 50, 1000)
    private val selfSurround        = bool("Self Surround",        true)
    private val selfSurroundDelayMs = int ("Self Surround Delay", 250, 50, 2000)

    // --- Kırma ---
    private val breakRange       = int ("Break Range",     10, 2, 20)
    private val breakDelayMs     = int ("Break Delay",      60, 30, 300)
    private val breakAll         = bool("Break All",          true)
    private val breakCrit        = enum("Break Crit",         CritMode.MovePacket)

    // --- Görünmezlik / legit davranış ---
    private val noSwitch         = bool("No Switch",           true)
    private val noParticles      = bool("No Particle",         true)
    private val silentRotation   = bool("Silent Rotation",     true)

    private val pendingPositions = ConcurrentHashMap<Long, Long>()
    private val pendingObsidian  = ConcurrentHashMap<Long, Long>()
    private val totemBurstUntil  = ConcurrentHashMap<Long, Long>()
    private val lastPlaceMsMap   = ConcurrentHashMap<Long, Long>()

    @Volatile private var lastBreakMs        = 0L
    @Volatile private var lastSelfSurroundMs = 0L

    private val blockDefCache = ConcurrentHashMap<String, BlockDefinition>()

    private val placeTokens        = AtomicInteger(0)
    @Volatile private var tokenWindowStart = 0L
    @Volatile private var currentWindowCap = 0
    @Volatile private var tickJob: kotlinx.coroutines.Job? = null

    private fun takePlaceToken(burst: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val cap = if (burst) burstMaxPlacePerSec.value else maxPlacePerSec.value
        if (now - tokenWindowStart >= 1000L) {
            tokenWindowStart = now
            currentWindowCap = cap
            placeTokens.set(cap)
        } else if (cap > currentWindowCap) {
            placeTokens.addAndGet(cap - currentWindowCap)
            currentWindowCap = cap
        }
        return placeTokens.getAndUpdate { if (it > 0) it - 1 else it } > 0
    }

    private fun isBurstTarget(target: EntityTracker.TrackedEntity): Boolean {
        if (!burstOnLowHpTotem.value) return false
        if (target.health <= burstHealth.value) return true
        val until = totemBurstUntil[target.runtimeId] ?: return false
        return System.currentTimeMillis() < until
    }

    override fun onEnable() {
        super.onEnable()
        pendingPositions.clear(); pendingObsidian.clear()
        blockDefCache.clear(); lastPlaceMsMap.clear(); totemBurstUntil.clear()
        placeTokens.set(0); tokenWindowStart = 0L
        tickJob?.cancel()
        tickJob = launchTickLoop(tickIntervalMs.value.toLong()) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        super.onDisable()
        pendingPositions.clear(); pendingObsidian.clear(); lastPlaceMsMap.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is AddEntityPacket -> {
                if (pkt.identifier.contains("crystal", ignoreCase = true)) {
                    val key = posKey(
                        floor(pkt.position.x - 0.5f).toInt(),
                        floor(pkt.position.y - 1f).toInt(),
                        floor(pkt.position.z - 0.5f).toInt()
                    )
                    pendingPositions.remove(key)
                }
            }
            is UpdateBlockPacket -> {
                val id = runCatching { pkt.definition?.runtimeId }.getOrNull()
                if (id != null) {
                    val pos = pkt.blockPosition
                    pendingObsidian.remove(posKey(pos.x, pos.y, pos.z))
                }
            }
            is LevelEventPacket -> {
                if (noParticles.value &&
                    (pkt.type == LevelEvent.PARTICLE_EXPLOSION || pkt.type == LevelEvent.PARTICLE_BLOCK_EXPLOSION)
                ) event.cancel()
            }
            is LevelSoundEventPacket -> {
                if (noParticles.value) {
                    val name = runCatching { pkt.sound?.name }.getOrNull() ?: ""
                    if (name.contains("EXPLODE", ignoreCase = true)) event.cancel()
                }
            }
            is CameraShakePacket -> {
                if (noParticles.value) event.cancel()
            }
            is EntityEventPacket -> {
                if (burstOnLowHpTotem.value) {
                    val typeName = runCatching { pkt.type?.toString()?.uppercase() }.getOrNull() ?: ""
                    if (typeName.contains("TOTEM")) {
                        totemBurstUntil[pkt.runtimeEntityId] = System.currentTimeMillis() + burstDurationMs.value
                    }
                }
            }
            else -> {}
        }
    }

    private suspend fun tick() {
        if (selfSurround.value) doSelfSurround()
        if (breakCrystals.value) doBreakInRange()

        val targets = selectTargets()
        if (targets.isEmpty()) return

        if (place.value) {
            when (strategy.value) {
                PlaceStrategy.Closest -> doPlace(targets.first())
                PlaceStrategy.HighestDamage -> {
                    val best = targets.maxByOrNull { estimatedBestDamage(it) } ?: targets.first()
                    doPlace(best)
                }
                PlaceStrategy.MultiTarget -> for (t in targets) doPlace(t)
            }
        }
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        if (EntityTracker.selfRuntimeId <= 0L) return emptyList()
        return EntityTracker.getPlayers(targetRange.value.toFloat())
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { !friendSkip.value || !it.isFriendEntity }
            .filter { MathUtil.dist3(it.x, it.y, it.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) > 0.15f }
            .sortedBy { EntityTracker.distanceTo(it) }
            .toList()
    }

    private fun predictedPos(target: EntityTracker.TrackedEntity): Triple<Float, Float, Float> {
        if (!predictMovement.value || predictAheadMs.value <= 0) return Triple(target.x, target.y, target.z)
        val t = predictAheadMs.value / 1000f
        val vx = target.x - target.prevX
        val vz = target.z - target.prevZ
        return Triple(target.x + vx * (t * 20f), target.y, target.z + vz * (t * 20f))
    }

    private fun estimatedBestDamage(target: EntityTracker.TrackedEntity): Float {
        val (px, py, pz) = predictedPos(target)
        val tx = floor(px).toInt(); val ty = floor(py).toInt(); val tz = floor(pz).toInt()
        var best = 0f
        for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) for (dz in -SEARCH_RADIUS..SEARCH_RADIUS) {
            val hit = findSurface(tx + dx, ty, tz + dz) ?: continue
            val cx = tx + dx + 0.5f; val cy = hit.y + 1f; val cz = tz + dz + 0.5f
            val dmg = estimateDamage(cx, cy, cz, target)
            if (dmg > best) best = dmg
        }
        return best
    }

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        val last = lastPlaceMsMap[target.runtimeId] ?: 0L
        if (now - last < placeDelayMs.value) return

        val session = PacketEventBus.currentSession ?: return

        val (px, py, pz) = predictedPos(target)
        val tx = floor(px).toInt(); val ty = floor(py).toInt(); val tz = floor(pz).toInt()

        var placedAny = false
        var placedThisTick = 0
        val burst = isBurstTarget(target)

        // Yerleşim adaylarını hasara göre sırala — en çok hasar veren pozisyona önce dene
        data class Candidate(val bx: Int, val bz: Int, val hitY: Int, val blockId: String, val dmg: Float, val selfDmg: Float)
        val candidates = mutableListOf<Candidate>()

        for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dz in -SEARCH_RADIUS..SEARCH_RADIUS) {
                val bx = tx + dx; val bz = tz + dz
                val hit = findSurface(bx, ty, bz) ?: run {
                    if (autoObsidian.value) tryPlaceObsidianSupportAt(session, bx, ty, bz)
                    null
                } ?: continue

                val cx = bx + 0.5f; val cy = hit.y + 1f; val cz = bz + 0.5f
                val damage = estimateDamage(cx, cy, cz, target)
                if (damage < minDamage && !(suicide.value && target.health < 5f)) continue

                val selfDamage = estimateDamage(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                if (!suicide.value && selfDamage > maxSelfDamage) continue

                if (requireLOS.value && !hasLineOfSight(cx, cy, cz)) continue

                candidates.add(Candidate(bx, bz, hit.y, hit.blockId, damage, selfDamage))
            }
        }

        for (c in candidates.sortedByDescending { it.dmg }) {
            if (placedThisTick >= MAX_PLACES_PER_TICK) break
            if (!takePlaceToken(burst)) break
            if (tryPlaceCrystalAt(session, PlacePos(c.bx, c.hitY, c.bz, c.blockId))) {
                lastPlaceMsMap[target.runtimeId] = now
                placedAny = true
                placedThisTick++
            }
        }
    }

    private fun tryPlaceCrystalAt(session: OxRelaySession, pos: PlacePos): Boolean {
        val now = System.currentTimeMillis()
        val key = posKey(pos.x, pos.y, pos.z)

        val pendingTime = pendingPositions[key]
        if (pendingTime != null) {
            if (now - pendingTime < placeRetryMs.value) return false
            pendingPositions.remove(key)
        }
        if (crystalExistsNear(pos.x + 0.5f, pos.y + 1f, pos.z + 0.5f)) return false

        val centerX = pos.x + 0.5f; val centerY = pos.y + 1.0f; val centerZ = pos.z + 0.5f
        val distSelf = MathUtil.dist3(centerX, centerY, centerZ, EntityTracker.selfX, EntityTracker.selfY + SELF_EYE_HEIGHT, EntityTracker.selfZ)
        if (distSelf > placeRange.value) return false

        val (slot, item) = resolveItemSlot("minecraft:end_crystal") ?: return false
        val blockDef = getBlockDefinition(session, pos.blockId) ?: return false

        return try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                blockPosition            = Vector3i.from(pos.x, pos.y, pos.z)
                blockFace                = 1
                hotbarSlot               = slot
                itemInHand               = item
                playerPosition           = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                clickPosition            = Vector3f.from(0.5f, 1.0f, 0.5f)
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
            pendingPositions[key] = now
            true
        } catch (_: Exception) { false }
    }

    private fun tryPlaceObsidianSupportAt(session: OxRelaySession, bx: Int, ty: Int, bz: Int) {
        val now = System.currentTimeMillis()
        val key = posKey(bx, ty, bz)
        val pendingTime = pendingObsidian[key]
        if (pendingTime != null && now - pendingTime < autoObsidianDelayMs.value) return
        if (!WorldBlockTracker.hasAnyTerrainData()) return

        var supportY: Int? = null
        for (by in (ty - 3)..(ty + 1)) {
            val here = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (here == "minecraft:air") continue
            val above = WorldBlockTracker.getBlockIdentifier(bx, by + 1, bz)
            if (above != null && above != "minecraft:air") continue
            supportY = by
        }
        val sy = supportY ?: return

        val centerX = bx + 0.5f; val centerY = sy + 1.5f; val centerZ = bz + 0.5f
        val distSelf = MathUtil.dist3(centerX, centerY, centerZ, EntityTracker.selfX, EntityTracker.selfY + SELF_EYE_HEIGHT, EntityTracker.selfZ)
        if (distSelf > placeRange.value) return

        val (slot, item) = resolveItemSlot("minecraft:obsidian") ?: return
        val blockDef = getBlockDefinition(session, "minecraft:obsidian") ?: return

        try {
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType          = InventoryTransactionType.ITEM_USE
                actionType               = 0
                blockPosition            = Vector3i.from(bx, sy, bz)
                blockFace                = 1
                hotbarSlot               = slot
                itemInHand               = item
                playerPosition           = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                clickPosition            = Vector3f.from(0.5f, 1.0f, 0.5f)
                blockDefinition          = blockDef
                triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                clientCooldownState      = 0
            })
            pendingObsidian[key] = now
        } catch (_: Exception) {}
    }

    private fun doSelfSurround() {
        val now = System.currentTimeMillis()
        if (now - lastSelfSurroundMs < selfSurroundDelayMs.value) return

        val session = PacketEventBus.currentSession ?: return
        val (slot, item) = resolveItemSlot("minecraft:obsidian") ?: return

        val fx = floor(EntityTracker.selfX).toInt()
        val fy = floor(EntityTracker.selfY).toInt() - 1
        val fz = floor(EntityTracker.selfZ).toInt()
        val hasData = WorldBlockTracker.hasAnyTerrainData()

        lastSelfSurroundMs = now

        for ((dx, dz) in listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
            val tx = fx + dx; val tz = fz + dz; val ty = fy
            val existing = if (hasData) WorldBlockTracker.getBlockIdentifier(tx, ty, tz) else null
            if (existing != null && existing != "minecraft:air") continue

            val supportId = (if (hasData) WorldBlockTracker.getBlockIdentifier(tx, ty - 1, tz) else null)
                ?: "minecraft:obsidian"
            if (supportId == "minecraft:air") continue

            val blockDef = getBlockDefinition(session, "minecraft:obsidian") ?: continue

            try {
                session.serverBound(InventoryTransactionPacket().apply {
                    transactionType          = InventoryTransactionType.ITEM_USE
                    actionType               = 0
                    blockPosition            = Vector3i.from(tx, ty - 1, tz)
                    blockFace                = 1
                    hotbarSlot               = slot
                    itemInHand               = item
                    playerPosition           = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                    clickPosition            = Vector3f.from(0.5f, 1.0f, 0.5f)
                    blockDefinition          = blockDef
                    triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                    clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                    clientCooldownState      = 0
                })
            } catch (_: Exception) {}
        }
    }

    private fun doBreakInRange() {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < breakDelayMs.value) return

        val session = PacketEventBus.currentSession ?: return
        val crystals = EntityTracker.getCrystals(breakRange.value.toFloat())
        if (crystals.isEmpty()) return
        lastBreakMs = now

        val toBreak = if (breakAll.value) {
            crystals
        } else {
            crystals.filter { c ->
                val dmg = calculateCrystalDamage(c.x, c.y, c.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                dmg >= MIN_DAMAGE || suicide.value
            }
        }.sortedBy { EntityTracker.distanceTo(it) }.take(MAX_BREAKS_PER_TICK)

        for (c in toBreak) scope.launch { attackCrystal(c.runtimeId, session) }
    }

    private suspend fun attackCrystal(rid: Long, session: OxRelaySession) {
        val target = EntityTracker.getById(rid) ?: return
        val r = RotationUtil.toPoint(target.x, target.y + 0.5f, target.z)

        CritLock.tryRun(name) { injectCrit(session) }

        if (silentRotation.value) PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch, onGround = true)

        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, rid)
    }

    private suspend fun injectCrit(s: OxRelaySession) {
        try {
            when (breakCrit.value) {
                CritMode.None -> {}
                CritMode.MovePacket -> {
                    PacketUtil.sendMoveAtSelf(s, dyOffset = 0.11f, onGround = false)
                    delay(15L)
                    PacketUtil.sendMoveAtSelf(s, dyOffset = 0f, onGround = true)
                }
                CritMode.Vanilla -> {
                    listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f).forEach { dy ->
                        PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = false)
                        delay(25L)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun calculateCrystalDamage(crystalX: Float, crystalY: Float, crystalZ: Float, targetX: Float, targetY: Float, targetZ: Float): Float {
        val dist = MathUtil.dist3(crystalX, crystalY, crystalZ, targetX, targetY, targetZ)
        val radius = 15f
        if (dist >= radius) return 0f
        return ((1f - (dist / radius)) * CRYSTAL_POWER).coerceAtLeast(0f)
    }

    private data class SurfaceHit(val y: Int, val blockId: String)
    private data class PlacePos(val x: Int, val y: Int, val z: Int, val blockId: String)

    private fun findSurface(bx: Int, ty: Int, bz: Int): SurfaceHit? {
        if (!WorldBlockTracker.hasAnyTerrainData()) return SurfaceHit(ty - 1, "minecraft:obsidian")

        for (by in (ty - searchYBelow.value)..(ty + searchYAbove.value)) {
            val here = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (here != "minecraft:obsidian" && here != "minecraft:bedrock") continue

            val above  = WorldBlockTracker.getBlockIdentifier(bx, by + 1, bz)
            val above2 = WorldBlockTracker.getBlockIdentifier(bx, by + 2, bz)
            val clear1 = above  == null || above  == "minecraft:air"
            val clear2 = above2 == null || above2 == "minecraft:air"
            if (!clear1 || !clear2) continue

            return SurfaceHit(by, here)
        }
        return null
    }

    private fun estimateDamage(cx: Float, cy: Float, cz: Float, target: EntityTracker.TrackedEntity): Float =
        estimateDamage(cx, cy, cz, target.x, target.y + 0.9f, target.z)

    private fun estimateDamage(cx: Float, cy: Float, cz: Float, tx: Float, ty: Float, tz: Float): Float {
        val diameter = CRYSTAL_POWER * 2f
        val dist = MathUtil.dist3(cx, cy, cz, tx, ty + 0.9f, tz)
        if (dist >= diameter) return 0f
        val normalizedDist = dist / diameter
        val impact = 1f - normalizedDist
        return (impact * impact + impact) / 2f * 7f * diameter + 1f
    }

    private fun crystalExistsNear(cx: Float, cy: Float, cz: Float): Boolean =
        EntityTracker.getCrystals(3f).any { c ->
            kotlin.math.abs(c.x - cx) < 0.9f && kotlin.math.abs(c.y - cy) < 1.3f && kotlin.math.abs(c.z - cz) < 0.9f
        }

    // Basit örneklenmiş raycast — self göz hizasından hedef noktaya kadar aradaki
    // bloklardan biri katıysa (hava değilse) görüş hattı kapalı sayılır.
    private fun hasLineOfSight(tx: Float, ty: Float, tz: Float): Boolean {
        if (!WorldBlockTracker.hasAnyTerrainData()) return true
        val ex = EntityTracker.selfX; val ey = EntityTracker.selfY + SELF_EYE_HEIGHT; val ez = EntityTracker.selfZ
        val dist = MathUtil.dist3(ex, ey, ez, tx, ty, tz)
        val steps = max(4, (dist * 2f).toInt())
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val sx = floor(ex + (tx - ex) * t).toInt()
            val sy = floor(ey + (ty - ey) * t).toInt()
            val sz = floor(ez + (tz - ez) * t).toInt()
            val id = WorldBlockTracker.getBlockIdentifier(sx, sy, sz) ?: continue
            if (id != "minecraft:air") return false
        }
        return true
    }

    private fun resolveItemSlot(identifier: String): Pair<Int, ItemData>? {
        EntityTracker.getHeldItem()?.let { held ->
            val id = runCatching { held.definition?.identifier }.getOrNull()
            if (id == identifier && held.count > 0) return EntityTracker.selfHotbarSlot to held
        }
        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull()
            if (id == identifier) return (if (noSwitch.value) EntityTracker.selfHotbarSlot else slot) to item
        }
        for (slot in 9..35) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull()
            if (id == identifier) return EntityTracker.selfHotbarSlot to item
        }
        return null
    }

    private fun getBlockDefinition(session: OxRelaySession, targetId: String): BlockDefinition? {
        blockDefCache[targetId]?.let { return it }
        try {
            fun scanDefs(defs: DefinitionRegistry<BlockDefinition>?): BlockDefinition? {
                defs ?: return null
                for (i in 0..4096) {
                    val def = try { defs.getDefinition(i) } catch (_: Exception) { null } ?: continue
                    val id = when (def) {
                        is SimpleBlockDefinition -> def.identifier
                        is Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
                        else -> null
                    }
                    if (id == targetId) return def
                }
                return null
            }
            scanDefs(session.clientSession.peer.codecHelper.blockDefinitions)?.let { blockDefCache[targetId] = it; return it }
            scanDefs(Definitions.getClosestDefinitions(session.activeCodec.protocolVersion).blockDefinitions)?.let { blockDefCache[targetId] = it; return it }
        } catch (_: Exception) {}

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

    private fun posKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
