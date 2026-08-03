package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.InventoryUtil
import com.rubidiumclient.utils.MiningUtil
import com.rubidiumclient.utils.OreTracker
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.Job
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class AutoMine : BaseModule(
    name        = "AutoMine",
    category    = ModuleCategory.MISC,
    description = "Cevher / tünel / çukur kazma otomasyonu"
) {
    enum class Mode { MineOres, MineTunnel, MineHole }

    companion object {
        private const val TICK_MS = 50L
        private const val STEP_SIZE = 0.22f
        private const val ARRIVE_DIST = 0.35f
    }

    private val mode              = enum("Mode", Mode.MineOres)
    private val range             = float("Range", 4f, 2f, 6f)
    private val tunnelWidth       = int("Tunnel Width", 1, 1, 3)
    private val tunnelHeight      = int("Tunnel Height", 2, 1, 3)
    private val holeDepth         = int("Hole Depth", 5, 1, 32)
    private val stopOnFluidBelow  = bool("Stop On Fluid Below", true)
    private val breakTimeoutTicks = int("Break Timeout (ticks)", 100, 20, 400)
    private val autoWalk          = bool("Auto Walk", true)

    private var tickJob: Job? = null

    @Volatile private var breakingPos: Vector3i? = null
    @Volatile private var elapsedTicks = 0
    @Volatile private var holeDugCount = 0

    override fun onEnable() {
        super.onEnable()
        breakingPos = null
        elapsedTicks = 0
        holeDugCount = 0
        tickJob = launchTickLoop(TICK_MS) { tick() }
    }

    override fun onDisable() {
        super.onDisable()
        tickJob?.cancel()
        tickJob = null
        breakingPos?.let { sendAbortBreak(it) }
        breakingPos = null
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

        val p = event.packet
        if (p is UpdateBlockPacket) {
            val target = breakingPos ?: return
            val pos = p.blockPosition ?: return
            if (pos.x == target.x && pos.y == target.y && pos.z == target.z) {
                breakingPos = null
                elapsedTicks = 0
            }
        }
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return

        val current = breakingPos
        if (current != null) {
            elapsedTicks++
            if (elapsedTicks > breakTimeoutTicks.value) {
                sendAbortBreak(current)
                breakingPos = null
                elapsedTicks = 0
            }
            return
        }

        when (mode.value) {
            Mode.MineOres   -> tickMineOres(session)
            Mode.MineTunnel -> tickMineTunnel(session)
            Mode.MineHole   -> tickMineHole(session)
        }
    }

    private fun tickMineOres(session: RubidiumRelaySession) {
        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ

        val nearest = OreTracker.getAllInRange(cx, cy, cz, range.value)
            .minByOrNull {
                dist2(it.pos.x + 0.5f, it.pos.y + 0.5f, it.pos.z + 0.5f, cx, cy, cz)
            }

        if (nearest == null) return

        val distToOre = sqrt(dist2(nearest.pos.x + 0.5f, nearest.pos.y + 0.5f, nearest.pos.z + 0.5f, cx, cy, cz))
        if (distToOre > range.value) {
            if (autoWalk.value) walkToward(session, nearest.pos.x + 0.5f, nearest.pos.z + 0.5f)
            return
        }

        faceBlock(session, nearest.pos)
        startBreak(session, nearest.pos)
    }

    private fun tickMineTunnel(session: RubidiumRelaySession) {
        val target = frontTunnelTargets(tunnelWidth.value, tunnelHeight.value).firstOrNull { isSolid(it) }
        if (target != null) {
            startBreak(session, target)
            return
        }
        if (autoWalk.value) stepForward(session)
    }

    private fun tickMineHole(session: RubidiumRelaySession) {
        if (holeDugCount >= holeDepth.value) return

        val feet = feetPos()
        val below = Vector3i.from(feet.x, feet.y - 1, feet.z)

        if (stopOnFluidBelow.value && isFluid(Vector3i.from(below.x, below.y - 1, below.z))) return

        if (isSolid(below)) {
            startBreak(session, below)
            holeDugCount++
        }
    }

    private fun startBreak(session: RubidiumRelaySession, pos: Vector3i) {
        if (breakingPos != null) return

        val blockId = WorldBlockTracker.getBlockIdentifier(pos.x, pos.y, pos.z) ?: return
        val heldId  = heldItemIdentifier()
        val ticks   = MiningUtil.breakTimeTicks(blockId, heldId)
        if (ticks < 0) return

        breakingPos = pos
        elapsedTicks = 0

        session.serverBound(PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.START_BREAK
            blockPosition   = pos
            face            = 1
        })
    }

    private fun sendAbortBreak(pos: Vector3i) {
        val session = PacketEventBus.currentSession ?: return
        session.serverBound(PlayerActionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            action          = PlayerActionType.ABORT_BREAK
            blockPosition   = pos
            face            = 1
        })
    }

    private fun heldItemIdentifier(): String? {
        val item = EntityTracker.getInventoryItem(EntityTracker.selfHotbarSlot) ?: return null
        return InventoryUtil.resolveIdentifier(item)
    }

    private fun feetPos(): Vector3i = Vector3i.from(
        floor(EntityTracker.selfX).toInt(),
        floor(EntityTracker.selfY).toInt(),
        floor(EntityTracker.selfZ).toInt()
    )

    private fun frontTunnelTargets(width: Int, height: Int): List<Vector3i> {
        val feet = feetPos()
        val yawRad = Math.toRadians(EntityTracker.selfYaw.toDouble())
        val fx = Math.round(-sin(yawRad)).toInt()
        val fz = Math.round(cos(yawRad)).toInt()

        val result = ArrayList<Vector3i>(width * height)
        val half = width / 2
        for (h in 0 until height) {
            for (w in -half..half) {
                val px: Int
                val pz: Int
                if (fx != 0) {
                    px = feet.x + fx; pz = feet.z + w
                } else {
                    px = feet.x + w; pz = feet.z + fz
                }
                result.add(Vector3i.from(px, feet.y + h, pz))
            }
        }
        return result
    }

    private fun faceBlock(session: RubidiumRelaySession, pos: Vector3i) {
        val dx = (pos.x + 0.5f) - EntityTracker.selfX
        val dz = (pos.z + 0.5f) - EntityTracker.selfZ
        val yaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        EntityTracker.selfYaw = yaw
    }

    private fun walkToward(session: RubidiumRelaySession, tx: Float, tz: Float) {
        val curX = EntityTracker.selfX
        val curZ = EntityTracker.selfZ
        val dxRaw = tx - curX
        val dzRaw = tz - curZ
        val dist = sqrt(dxRaw * dxRaw + dzRaw * dzRaw)
        if (dist <= ARRIVE_DIST) return

        val step = STEP_SIZE.coerceAtMost(dist)
        val nx = curX + (dxRaw / dist) * step
        val nz = curZ + (dzRaw / dist) * step
        val yaw = Math.toDegrees(atan2(-dxRaw.toDouble(), dzRaw.toDouble())).toFloat()

        val pos = Vector3f.from(nx, EntityTracker.selfY, nz)

        session.serverBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = pos
            rotation              = Vector3f.from(EntityTracker.selfPitch, yaw, yaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        })
        session.clientBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = pos
            rotation              = Vector3f.from(EntityTracker.selfPitch, yaw, yaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        })

        EntityTracker.selfX = nx
        EntityTracker.selfZ = nz
        EntityTracker.selfYaw = yaw
    }

    private fun stepForward(session: RubidiumRelaySession) {
        val yawRad = Math.toRadians(EntityTracker.selfYaw.toDouble())
        val dx = -sin(yawRad).toFloat() * STEP_SIZE
        val dz = cos(yawRad).toFloat() * STEP_SIZE
        val pos = Vector3f.from(EntityTracker.selfX + dx, EntityTracker.selfY, EntityTracker.selfZ + dz)

        session.serverBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = pos
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        })
        session.clientBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = pos
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        })

        EntityTracker.selfX = pos.x
        EntityTracker.selfZ = pos.z
    }

    private fun isSolid(pos: Vector3i): Boolean {
        val id = WorldBlockTracker.getBlockIdentifier(pos.x, pos.y, pos.z) ?: return false
        if (id == "minecraft:air") return false
        return !isFluid(pos)
    }

    private fun isFluid(pos: Vector3i): Boolean = WorldBlockTracker.isBlock(
        pos.x, pos.y, pos.z,
        "minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava"
    )

    private fun dist2(x: Float, y: Float, z: Float, cx: Float, cy: Float, cz: Float): Float {
        val dx = x - cx; val dy = y - cy; val dz = z - cz
        return dx * dx + dy * dy + dz * dz
    }
}
