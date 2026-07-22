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
    description = "Rakip oyuncunun etrafına akıllı kristal yerleştirir ve patlatır"
) {
    companion object {
        private const val TICK_INTERVAL_MS = 10L
        private const val CRYSTAL_POWER    = 6f
        private const val CRYSTAL_RADIUS   = 15f
        private const val MIN_DAMAGE        = 0.5f
        private const val SINGLE_SEARCH_RADIUS = 2
    }

    private val range           = 10f
    private val placeRange      = 8f
    private val breakRange      = 10f
    private val suicide         = true
    private val place           = true
    private val breakCrystals   = true
    private val breakAnywhere   = true
    private val placeDelay      = 20
    private val breakDelay      = 20
    private val removeParticles = true
    private val noSwitch        = bool ("No Switch",      false)
    private val autoObby        = true
    private val autoObbyDelay   = 50
    private val shortcut        = bool ("Shortcut",       true)
    private val smartBreakRange = true
    private val smartBreakBoost = 5f
    private val autoReplace     = true
    private val placeRetryMs    = int  ("Place Retry Ms", 400,  100, 3000)

    private val activeCrystals   = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime  = ConcurrentHashMap<Long, Long>()
    private val pendingPositions = ConcurrentHashMap<Long, Long>()
    private val placedBlockIds   = ConcurrentHashMap<Long, String>()

    private val lastPlaceMsMap = ConcurrentHashMap<Long, Long>()
    private val lastBreakMsMap = ConcurrentHashMap<Long, Long>()
    @Volatile private var lastObbyMs  = 0L
    private var tickJob: Job? = null

    private val blockDefCache = ConcurrentHashMap<String, BlockDefinition>()

    private fun resolveCrystalSlot(): Pair<Int, ItemData>? {
        if (noSwitch.value) {
            val held = EntityTracker.getHeldItem() ?: return null
            if (held.count <= 0) return null
            val id = runCatching { held.definition?.identifier }.getOrNull()
            if (id != "minecraft:end_crystal") return null
            return EntityTracker.selfHotbarSlot to held
        }

        EntityTracker.getHeldItem()?.let { held ->
            val id = runCatching { held.definition?.identifier }.getOrNull()
            if (id == "minecraft:end_crystal" && held.count > 0) {
                return EntityTracker.selfHotbarSlot to held
            }
        }

        for (slot in 0..8) {
            val item = EntityTracker.getInventoryItem(slot) ?: continue
            if (item.count <= 0) continue
            val id = runCatching { item.definition?.identifier }.getOrNull()
            if (id == "minecraft:end_crystal") return slot to item
        }
        return null
    }

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); pendingPositions.clear()
        blockDefCache.clear(); placedBlockIds.clear()
        lastPlaceMsMap.clear(); lastBreakMsMap.clear()
        tickJob?.cancel()
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        activeCrystals.clear(); uniqueToRuntime.clear(); pendingPositions.clear()
        placedBlockIds.clear(); lastPlaceMsMap.clear(); lastBreakMsMap.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is AddEntityPacket -> {
                if (pkt.identifier.contains("crystal", ignoreCase = true)) {
                    activeCrystals[pkt.runtimeEntityId] = pkt.position
                    uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
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
                    if (autoReplace && place && pos != null) {
                        val bx = floor(pos.x - 0.5f).toInt()
                        val by = floor(pos.y - 1f).toInt()
                        val bz = floor(pos.z - 0.5f).toInt()
                        val key = posKey(bx, by, bz)
                        val blockId = placedBlockIds[key] ?: "minecraft:obsidian"
                        pendingPositions.remove(key)
                        tryPlaceAt(event.session, PlacePos(bx, by, bz, blockId), ignorePending = true)
                    }
                }
            }
            is LevelEventPacket -> {
                if (removeParticles) {
                    if (pkt.type == LevelEvent.PARTICLE_EXPLOSION ||
                        pkt.type == LevelEvent.PARTICLE_BLOCK_EXPLOSION) {
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
                if (autoObby) doAutoObby()

                if (breakCrystals && breakAnywhere) doBreakAnywhere()

                selectTarget()?.let { target ->
                    if (breakCrystals && !breakAnywhere) doBreak(target)
                    if (place) doPlace(target)
                }
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun calculateCrystalDamage(crystalX: Float, crystalY: Float, crystalZ: Float, targetX: Float, targetY: Float, targetZ: Float): Float {
        val dx = crystalX - targetX
        val dy = crystalY - targetY
        val dz = crystalZ - targetZ
        val distance = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)
        
        if (distance >= CRYSTAL_RADIUS) return 0f
        return ((1f - (distance / CRYSTAL_RADIUS)) * CRYSTAL_POWER).coerceAtLeast(0f)
    }

    private fun doBreakAnywhere() {
        val now = System.currentTimeMillis()
        val last = lastBreakMsMap[-1L] ?: 0L
        if (now - last < breakDelay) return

        val session = PacketEventBus.currentSession ?: return
        val bRangeSq = breakRange * breakRange
        
        val selfHealth = EntityTracker.getSelfEntity()?.health ?: 20f

        val inRange = activeCrystals.entries.filter { (_, pos) ->
            val dx = pos.x - EntityTracker.selfX
            val dy = pos.y - EntityTracker.selfY
            val dz = pos.z - EntityTracker.selfZ
            dx*dx + dy*dy + dz*dz <= bRangeSq
        }
        if (inRange.isEmpty()) return

        lastBreakMsMap[-1L] = now

        // Filter high-damage crystals only
        val highDamage = inRange.mapNotNull { (rid, pos) ->
            val damage = calculateCrystalDamage(pos.x + 0.5f, pos.y + 1f, pos.z + 0.5f, 
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            if (damage >= MIN_DAMAGE || (suicide && selfHealth < 5f)) {
                rid
            } else null
        }

        // Break all high-damage crystals
        for (rid in highDamage) {
            attackCrystal(rid, session)
        }
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val rangeSq = range * range
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

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val rangeSq = range * range
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

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        val last = lastPlaceMsMap[target.runtimeId] ?: 0L
        if (now - last < placeDelay) return

        val session  = PacketEventBus.currentSession ?: return
        if (resolveCrystalSlot() == null) return

        val positions = getPlacePositions(target)
        if (positions.isEmpty()) return

        lastPlaceMsMap[target.runtimeId] = now

        // Filter positions: only place crystals that deal damage to target
        for (pos in positions) {
            val crystalX = pos.x + 0.5f
            val crystalY = pos.y + 1f
            val crystalZ = pos.z + 0.5f
            
            val damage = calculateCrystalDamage(crystalX, crystalY, crystalZ, target.x, target.y, target.z)
            
            // Place only if damage >= MIN_DAMAGE or suicide mode and target low health
            if (damage >= MIN_DAMAGE || (suicide && target.health < 5f)) {
                tryPlaceAt(session, pos)
            }
        }
    }

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
        if (distSelf > placeRange) return false

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

    private fun getPlacePositions(target: EntityTracker.TrackedEntity): List<PlacePos> {
        return getBestSinglePosition(target)
    }

    private fun getBestSinglePosition(target: EntityTracker.TrackedEntity): List<PlacePos> {
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()

        var best: PlacePos? = null
        var bestDamage = -1f

        for (dx in -SINGLE_SEARCH_RADIUS..SINGLE_SEARCH_RADIUS) {
            for (dz in -SINGLE_SEARCH_RADIUS..SINGLE_SEARCH_RADIUS) {
                val bx = tx + dx
                val bz = tz + dz

                for (hit in findSurfaceCandidates(bx, ty, bz)) {
                    val cx = bx + 0.5f
                    val cy = hit.y + 1f
                    val cz = bz + 0.5f

                    val distSelf = MathUtil.dist3(
                        cx, cy, cz,
                        EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ
                    )
                    if (distSelf > placeRange) continue

                    val damage = estimateDamage(cx, cy, cz, target)
                    if (damage <= 0f) continue

                    if (damage > bestDamage) {
                        bestDamage = damage
                        best = PlacePos(bx, hit.y, bz, hit.blockId)
                    }
                }
            }
        }
        return listOfNotNull(best)
    }

    private fun findSurfaceCandidates(bx: Int, ty: Int, bz: Int): List<SurfaceHit> {
        if (!WorldBlockTracker.hasAnyTerrainData()) {
            return listOf(SurfaceHit(ty - 1, "minecraft:obsidian"))
        }

        val hits = mutableListOf<SurfaceHit>()
        for (by in (ty - 3)..(ty + 2)) {
            val here = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (here != "minecraft:obsidian" && here != "minecraft:bedrock") continue

            val above  = WorldBlockTracker.getBlockIdentifier(bx, by + 1, bz)
            val above2 = WorldBlockTracker.getBlockIdentifier(bx, by + 2, bz)
            val clear1 = above  == null || above  == "minecraft:air"
            val clear2 = above2 == null || above2 == "minecraft:air"
            if (!clear1 || !clear2) continue

            hits.add(SurfaceHit(by, here))
        }
        return hits
    }

    private fun estimateDamage(cx: Float, cy: Float, cz: Float, target: EntityTracker.TrackedEntity): Float {
        val diameter = CRYSTAL_POWER * 2f
        val dist = MathUtil.dist3(cx, cy, cz, target.x, target.y + 0.9f, target.z)
        if (dist >= diameter) return 0f

        val exposure = estimateExposure(cx, cy, cz, target)
        if (exposure <= 0f) return 0f

        val normalizedDist = dist / diameter
        val impact = (1f - normalizedDist) * exposure
        return (impact * impact + impact) / 2f * 7f * diameter + 1f
    }

    private val exposureOffsets = arrayOf(
        floatArrayOf(0f, 0.1f, 0f),
        floatArrayOf(0f, 0.9f, 0f),
        floatArrayOf(0f, 1.6f, 0f),
        floatArrayOf(0.3f, 0.9f, 0.3f),
        floatArrayOf(-0.3f, 0.9f, -0.3f)
    )

    private fun estimateExposure(cx: Float, cy: Float, cz: Float, target: EntityTracker.TrackedEntity): Float {
        if (!WorldBlockTracker.hasAnyTerrainData()) return 1f

        var clear = 0
        for (off in exposureOffsets) {
            val tx = target.x + off[0]
            val ty = target.y + off[1]
            val tz = target.z + off[2]
            if (hasLineOfSight(cx, cy, cz, tx, ty, tz)) clear++
        }
        return clear.toFloat() / exposureOffsets.size
    }

    private fun hasLineOfSight(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val dist = MathUtil.dist3(x0, y0, z0, x1, y1, z1)
        if (dist < 0.1f) return true

        val steps = (dist / 0.5f).toInt().coerceAtLeast(1)
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val bx = floor(x0 + dx * t).toInt()
            val by = floor(y0 + dy * t).toInt()
            val bz = floor(z0 + dz * t).toInt()
            val id = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (id != "minecraft:air") return false
        }
        return true
    }

    private fun getSquare5Positions(target: EntityTracker.TrackedEntity): List<PlacePos> {
        val result = mutableListOf<PlacePos>()
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()
        val r = 5

        for (dx in -r..r) {
            for (dz in -r..r) {
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

    private fun findSurface(bx: Int, ty: Int, bz: Int): SurfaceHit? {
        val hasData = WorldBlockTracker.hasAnyTerrainData()

        if (hasData) {
            var sawAnyResolvedBlock = false
            for (by in (ty - 3)..(ty + 2)) {
                val here = WorldBlockTracker.getBlockIdentifier(bx, by, bz)
                if (here != null) sawAnyResolvedBlock = true
                if (here == null) continue
                if (here != "minecraft:obsidian" && here != "minecraft:bedrock") continue

                val above  = WorldBlockTracker.getBlockIdentifier(bx, by + 1, bz)
                val above2 = WorldBlockTracker.getBlockIdentifier(bx, by + 2, bz)
                val clear1 = above  == null || above  == "minecraft:air"
                val clear2 = above2 == null || above2 == "minecraft:air"
                if (!clear1 || !clear2) continue

                return SurfaceHit(by, here)
            }

            if (!sawAnyResolvedBlock) {
                return SurfaceHit(ty - 1, "minecraft:obsidian")
            }
            return null
        } else {
            return SurfaceHit(ty - 1, "minecraft:obsidian")
        }
    }

    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        val last = lastBreakMsMap[target.runtimeId] ?: 0L
        if (now - last < breakDelay) return

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

        // Filter: only break crystals that deal significant damage to target
        val highDamage = inRange.mapNotNull { (rid, pos) ->
            val damage = calculateCrystalDamage(pos.x + 0.5f, pos.y + 1f, pos.z + 0.5f, target.x, target.y, target.z)
            if (damage >= MIN_DAMAGE || (suicide && target.health < 5f)) {
                rid to damage
            } else null
        }
        
        if (highDamage.isEmpty()) return

        // Break all high-damage crystals (sorted by damage for optimal burst order)
        highDamage.sortByDescending { (_, dmg) -> dmg }.forEach { (rid, _) ->
            attackCrystal(rid, session)
        }
    }

    private fun effectiveBreakRange(target: EntityTracker.TrackedEntity): Float {
        if (!smartBreakRange) return breakRange
        val assumedMaxHealth = 20f
        val healthFraction = (target.health / assumedMaxHealth).coerceIn(0f, 1f)
        return breakRange + (1f - healthFraction) * smartBreakBoost
    }

    private fun attackCrystal(rid: Long, session: OxRelaySession) {
        val pos = activeCrystals[rid] ?: return
        val r = RotationUtil.toPoint(pos.x, pos.y, pos.z)
        PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        PacketUtil.sendSwingAndAttack(session, rid)
    }

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

    private fun doAutoObby() {
        val now = System.currentTimeMillis()
        if (now - lastObbyMs < autoObbyDelay) return

        val session = PacketEventBus.currentSession ?: return
        val (slot, item) = resolveObsidianSlot() ?: return

        val fx = floor(EntityTracker.selfX).toInt()
        val fy = floor(EntityTracker.selfY).toInt() - 1
        val fz = floor(EntityTracker.selfZ).toInt()
        val hasData = WorldBlockTracker.hasAnyTerrainData()

        lastObbyMs = now

        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val tx = fx + dx
            val tz = fz + dz
            val ty = fy
            val supportY = ty - 1

            val existing = if (hasData) WorldBlockTracker.getBlockIdentifier(tx, ty, tz) else null
            if (existing != null && existing != "minecraft:air") continue

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
