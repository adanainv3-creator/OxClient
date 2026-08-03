package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

class AutoTrap : BaseModule(
    name        = "AutoTrap",
    category    = ModuleCategory.COMBAT,
    description = "En yakın rakibi otomatik olarak bloklarla kapatır"
) {
    enum class Shape { Sides, SidesAndTop, Full }

    companion object {
        private const val TICK_MS          = 100L
        private const val PENDING_RETRY_MS = 200L

        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )
        private val SIDE_OFFSETS = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
        )
    }

    private val targetRange     = int("Target Range", 8, 2, 16)
    private val friendSkip      = bool("Friend Skip", true)
    private val shape           = enum("Shape", Shape.SidesAndTop)
    private val blockIdentifier = string("Block", "minecraft:obsidian")
    private val placeRange      = int("Place Range", 6, 2, 12)
    private val placePerSec     = int("Place/Sec", 20, 1, 60)
    private val noSwitch        = bool("No Switch", true)

    private val pending       = ConcurrentHashMap<Long, Long>()
    private val placeTokens   = AtomicInteger(0)
    @Volatile private var tokenWindowStart = 0L
    @Volatile private var currentWindowCap = 0
    @Volatile private var tickJob: kotlinx.coroutines.Job? = null

    override fun onEnable() {
        super.onEnable()
        pending.clear()
        placeTokens.set(0); tokenWindowStart = 0L
        tickJob?.cancel()
        tickJob = launchTickLoop(TICK_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        super.onDisable()
        pending.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val p = event.packet
        if (p is UpdateBlockPacket) {
            val id = runCatching { p.definition?.runtimeId }.getOrNull() ?: return
            pending.remove(PlacementUtil.posKey(p.blockPosition.x, p.blockPosition.y, p.blockPosition.z))
        }
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val target = nearestEnemy() ?: return
        doTrap(session, target)
    }

    private fun nearestEnemy(): EntityTracker.TrackedEntity? {
        if (EntityTracker.selfRuntimeId <= 0L) return null
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        return EntityTracker.getPlayers(targetRange.value.toFloat())
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { !friendSkip.value || !it.isFriendEntity }
            .minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
    }

    private fun doTrap(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val fx = floor(target.x).toInt()
        val fy = floor(target.y).toInt()
        val fz = floor(target.z).toInt()

        val targets = ArrayList<Vector3i>(6)
        for (off in SIDE_OFFSETS) targets.add(Vector3i.from(fx + off[0], fy, fz + off[1]))
        if (shape.value == Shape.SidesAndTop || shape.value == Shape.Full) {
            targets.add(Vector3i.from(fx, fy + 2, fz))
        }
        if (shape.value == Shape.Full) {
            targets.add(Vector3i.from(fx, fy - 1, fz))
        }

        val hasData = WorldBlockTracker.hasAnyTerrainData()
        var prepared: PlacementUtil.PreparedItem? = null
        try {
            for (pos in targets) {
                val existing = if (hasData) WorldBlockTracker.getBlockIdentifier(pos.x, pos.y, pos.z) else null
                if (existing != null && existing !in NON_SOLID) continue

                val cx = pos.x + 0.5f; val cy = pos.y + 0.5f; val cz = pos.z + 0.5f
                if (MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ) > placeRange.value) continue

                val now = System.currentTimeMillis()
                val key = PlacementUtil.posKey(pos.x, pos.y, pos.z)
                if (now - (pending[key] ?: 0L) < PENDING_RETRY_MS) continue
                if (!takePlaceToken()) break

                if (prepared == null) prepared = PlacementUtil.prepareItemForUse(session, blockIdentifier.value, noSwitch.value) ?: break

                if (PlacementUtil.sendPlacementUseRaw(session, prepared, pos, blockIdentifier.value)) pending[key] = now
            }
        } finally {
            prepared?.let { PlacementUtil.revert(session, it) }
        }
    }

    private fun takePlaceToken(): Boolean {
        val now = System.currentTimeMillis()
        val cap = placePerSec.value
        if (now - tokenWindowStart >= 1000L) {
            tokenWindowStart = now; currentWindowCap = cap; placeTokens.set(cap)
        } else if (cap > currentWindowCap) {
            placeTokens.addAndGet(cap - currentWindowCap); currentWindowCap = cap
        }
        return placeTokens.getAndUpdate { if (it > 0) it - 1 else it } > 0
    }
}
