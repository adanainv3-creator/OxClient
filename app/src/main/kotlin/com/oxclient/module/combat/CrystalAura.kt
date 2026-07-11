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
    enum class Mode { Single, Full5x5 }

    private val range           = float("Range",          5f,   3f,  10f)
    private val placeRange      = float("Place Range",    4.5f, 2f,   8f)
    private val breakRange      = float("Break Range",    6f,   3f,  10f)
    private val suicide         = bool ("Suicide",        false)
    private val place           = bool ("Place",          true)
    private val breakCrystals   = bool ("Break",          true)
    private val placeDelay      = int  ("Place Delay",    100,  20,  500)
    private val breakDelay      = int  ("Break Delay",    50,   20,  500)
    private val removeParticles = bool ("RemoveParticles",true)
    private val placeMode       = enum ("Place Mode",     Mode.Single)
    private val breakMode       = enum ("Break Mode",     Mode.Single)

    private val activeCrystals  = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    // Biz koyduğumuz pozisyonlar → AddEntityPacket gelince temizlenir
    private val pendingPositions = ConcurrentHashMap<Long, Long>()

    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var tickJob: Job? = null

    private var cachedObsidianDef: BlockDefinition? = null
    private var cachedBedrockDef:  BlockDefinition? = null

    private val shortcut = bool("Shortcut", false)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); pendingPositions.clear()
        cachedObsidianDef = null; cachedBedrockDef = null
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        activeCrystals.clear(); uniqueToRuntime.clear(); pendingPositions.clear()
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
                if (rid != null) activeCrystals.remove(rid)
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
                val target = selectTarget()
                if (target != null) {
                    if (breakCrystals.value) doBreak(target)
                    if (place.value)         doPlace(target)
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

    // ── Place ─────────────────────────────────────────────────────────────────

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < placeDelay.value) return

        val session  = PacketEventBus.currentSession ?: return
        val heldItem = EntityTracker.getHeldItem() ?: return
        if (heldItem.count <= 0) return
        val itemId   = runCatching { heldItem.definition?.identifier }.getOrNull() ?: return
        if (itemId != "minecraft:end_crystal") return

        val blockDef = getBlockDefinition(session) ?: return
        val positions = getPlacePositions(target)
        if (positions.isEmpty()) return

        lastPlaceMs = now

        for ((bx, by, bz) in positions) {
            val key = posKey(bx, by, bz)

            // Daha önce koyduğumuz pending var mı?
            val pendingTime = pendingPositions[key]
            if (pendingTime != null) {
                // 3 saniye geçtiyse sunucu reddetti, tekrar dene
                if (now - pendingTime < 3000L) continue
                else pendingPositions.remove(key)
            }

            // Zaten aktif kristal var mı?
            if (crystalExistsAt(bx, by, bz)) continue

            val centerX = bx + 0.5f
            val centerY = by + 1.0f
            val centerZ = bz + 0.5f

            // Bize uzaklık kontrolü
            val distSelf = MathUtil.dist3(
                centerX, centerY, centerZ,
                EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ
            )
            if (distSelf > placeRange.value) continue

            // Suicide kontrolü
            if (!suicide.value && distSelf < 3f) continue

            try {
                session.serverBound(InventoryTransactionPacket().apply {
                    transactionType          = InventoryTransactionType.ITEM_USE
                    actionType               = 0
                    blockPosition            = Vector3i.from(bx, by, bz)
                    blockFace                = 1
                    hotbarSlot               = EntityTracker.selfHotbarSlot
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
            } catch (_: Exception) {}
        }
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

    private fun getPlacePositions(target: EntityTracker.TrackedEntity): List<Triple<Int, Int, Int>> {
        val result = mutableListOf<Triple<Int, Int, Int>>()
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()
        val radius = if (placeMode.value == Mode.Full5x5) 2 else 1

        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val bx = tx + dx
                val bz = tz + dz

                val by = findSurface(bx, ty, bz) ?: continue

                // Kristal hedefe ne kadar yakın?
                val distToTarget = MathUtil.dist3(
                    bx + 0.5f, by + 1f, bz + 0.5f,
                    target.x, target.y + 1f, target.z
                )
                if (distToTarget > 5f) continue

                result.add(Triple(bx, by, bz))
            }
        }
        return result
    }

    /**
     * Verilen (bx, bz) sütununda kristal koyulabilecek yüzeyi bulur.
     * WorldBlockTracker veri döndürmüyorsa (chunk parse olmamış) sadece
     * rakibin Y'sinin altından yukarı doğru obsidian/bedrock arar.
     * Veri yoksa rakibin ayak Y'sinin bir altını fallback olarak döndürür —
     * böylece WorldBlockTracker hazır olmasa da çalışmaya devam eder.
     */
    private fun findSurface(bx: Int, ty: Int, bz: Int): Int? {
        val hasData = WorldBlockTracker.hasAnyTerrainData()

        if (hasData) {
            // Chunk verisi var: obsidian/bedrock yüzey ara
            for (by in (ty - 3)..(ty + 2)) {
                val here = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
                if (here != "minecraft:obsidian" && here != "minecraft:bedrock") continue

                val above  = WorldBlockTracker.getBlockIdentifier(bx, by + 1, bz)
                val above2 = WorldBlockTracker.getBlockIdentifier(bx, by + 2, bz)
                // null = chunk verisi yok → air kabul et
                val clear1 = above  == null || above  == "minecraft:air"
                val clear2 = above2 == null || above2 == "minecraft:air"
                if (!clear1 || !clear2) continue

                return by
            }
            return null
        } else {
            // Chunk verisi hiç yok: rakibin ayak Y'sinin altını kullan (fallback)
            // Sunucu paketi reddederse pendingPositions timeout'u ile zaten geçiyoruz
            return ty - 1
        }
    }

    // ── Break ─────────────────────────────────────────────────────────────────

    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < breakDelay.value) return

        val session = PacketEventBus.currentSession ?: return
        val bRangeSq = breakRange.value * breakRange.value

        val inRange = activeCrystals.entries.filter { (_, pos) ->
            val dx = pos.x - target.x
            val dy = pos.y - target.y
            val dz = pos.z - target.z
            dx*dx + dy*dy + dz*dz <= bRangeSq
        }
        if (inRange.isEmpty()) return

        lastBreakMs = now

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
            Mode.Full5x5 -> {
                for ((rid, _) in inRange) {
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
            }
        }
    }

    private fun attackCrystal(rid: Long, session: OxRelaySession) {
        val pos = activeCrystals[rid] ?: return
        val r = RotationUtil.toPoint(pos.x, pos.y, pos.z)
        PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        PacketUtil.sendSwingAndAttack(session, rid)
    }

    // ── Block definition ──────────────────────────────────────────────────────

    private fun getBlockDefinition(session: OxRelaySession): BlockDefinition? {
        cachedObsidianDef?.let { return it }
        val targetId = "minecraft:obsidian"
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
                cachedObsidianDef = it; return it
            }
            scanDefs(Definitions.getClosestDefinitions(session.activeCodec.protocolVersion).blockDefinitions)?.let {
                cachedObsidianDef = it; return it
            }
        } catch (_: Exception) {}

        // Fallback: hardcoded runtime id 49
        val fallback = SimpleBlockDefinition(
            targetId, 49,
            org.cloudburstmc.nbt.NbtMap.builder()
                .putString("name", targetId)
                .putCompound("states", org.cloudburstmc.nbt.NbtMap.builder().build())
                .build()
        )
        cachedObsidianDef = fallback
        return fallback
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun posKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
