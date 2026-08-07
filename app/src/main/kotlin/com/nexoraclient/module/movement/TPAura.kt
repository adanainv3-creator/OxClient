package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Rakip etrafında hareket eder"
), PacketEventBus.PacketListener {

    enum class MoveMode    { Random, Strafe, Behind, Aggressive, Quake }
    enum class RotationMode { Off, Instant, Smooth, Legit }

    private val moveMode        = enum("Mode",             MoveMode.Aggressive)
    private val detectRange     = float("Detect Range",     500f, 10f,  500f)
    private val range           = float("Range",            1.52f, 1f,   8f)
    private val horizontalSpeed = float("Horizontal Speed", 5f,   0.5f, 12f)
    private val verticalSpeed   = float("Vertical Speed",   2.4f, 0.1f, 10f)
    private val strafeSpeed     = float("Strafe Speed",     9f,   0.1f, 80f)
    private val yOffset         = float("Y Offset",         0.8f, -2f,  2f)
    private val rotationMode    = enum("Rotation Mode",    RotationMode.Instant)
    private val rotationSmooth  = float("Rotation Smooth",  0.28f, 0.02f, 1f)
    private val quakeHopTicks   = int("Quake Hop Ticks",  4, 1, 15)
    private val ignoreFriends   = bool("Ignore Friends",   true)
    private val shortcut        = bool("Shortcut",         false)

    private var strafeAngle      = 0.0
    private var quakeAngleOffset = 0.0
    private var quakeTicksLeft   = 0
    @Volatile private var curYaw   = 0f
    @Volatile private var curPitch = 0f

    @Volatile private var lastTargetId = 0L
    @Volatile private var lastFindMs   = 0L
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null

    private val TARGET_CACHE_MS = 100L

    override fun onEnable() {
        super.onEnable()
        strafeAngle       = Random.nextDouble(0.0, Math.PI * 2)
        quakeAngleOffset  = 0.0
        quakeTicksLeft    = 0
        lastTargetId      = 0L
        lastFindMs        = 0L
        cachedTarget      = null
        curYaw            = EntityTracker.selfYaw
        curPitch          = EntityTracker.selfPitch
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        cachedTarget = null
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return
        val target = getCachedTarget() ?: return

        event.cancel()

        moveAroundTarget(target)
    }

    private fun getCachedTarget(): EntityTracker.TrackedEntity? {
        val now = System.currentTimeMillis()
        if (now - lastFindMs >= TARGET_CACHE_MS) {
            cachedTarget = findTarget()
            lastFindMs   = now
        }
        return cachedTarget
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(detectRange.value)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }

        val target = candidates.minByOrNull { EntityTracker.distanceTo(it) }
        if (target != null && target.runtimeId != lastTargetId) {
            lastTargetId = target.runtimeId
        }
        return target
    }

    private fun moveAroundTarget(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        val targetPos = Vector3f.from(target.x, target.y + yOffset.value, target.z)
        val dist      = MathUtil.dist3(selfX, selfY, selfZ, target.x, target.y, target.z)

        val newPos = if (dist > range.value) {
            stepTowardTarget(selfX, selfY, selfZ, targetPos)
        } else {
            calculatePosition(selfX, selfZ, targetPos)
        }

        val rot = computeRotation(target)

        try {
            val movePacket = MovePlayerPacket().apply {
                runtimeEntityId       = EntityTracker.selfRuntimeId
                position              = newPos
                rotation              = if (rot != null)
                    Vector3f.from(rot.pitch, rot.yaw, rot.yaw)
                else
                    Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, 0f)
                mode                  = MovePlayerPacket.Mode.NORMAL
                isOnGround            = true
                ridingRuntimeEntityId = 0L
            }
            session.serverBound(movePacket)
            session.clientBound(movePacket)

            EntityTracker.selfX = newPos.x
            EntityTracker.selfY = newPos.y
            EntityTracker.selfZ = newPos.z
            if (rot != null) {
                EntityTracker.selfYaw   = rot.yaw
                EntityTracker.selfPitch = rot.pitch
            }
        } catch (_: Exception) {}
    }

    private fun computeRotation(target: EntityTracker.TrackedEntity): RotationUtil.Rotation? {
        return when (rotationMode.value) {
            RotationMode.Off -> null
            RotationMode.Instant -> RotationUtil.toEntity(target).also {
                curYaw = it.yaw; curPitch = it.pitch
            }
            RotationMode.Smooth -> {
                val targetRot = RotationUtil.toEntity(target)
                val factor = rotationSmooth.value.coerceIn(0.02f, 1f)
                var diff = targetRot.yaw - curYaw
                if (diff > 180f) diff -= 360f
                if (diff < -180f) diff += 360f
                curYaw   = RotationUtil.normalize(curYaw + diff * factor)
                curPitch = (curPitch + (targetRot.pitch - curPitch) * factor).coerceIn(-90f, 90f)
                RotationUtil.Rotation(curYaw, curPitch)
            }
            RotationMode.Legit -> {
                val targetRot = RotationUtil.toEntity(target)
                val result = RotationUtil.smoothTo(curYaw, curPitch, targetRot, baseFactor = rotationSmooth.value)
                curYaw = result.yaw; curPitch = result.pitch
                result
            }
        }
    }

    private fun stepTowardTarget(selfX: Float, selfY: Float, selfZ: Float, targetPos: Vector3f): Vector3f {
        val direction = atan2(
            (targetPos.z - selfZ).toDouble(),
            (targetPos.x - selfX).toDouble()
        ) - Math.toRadians(90.0)
        val newX = selfX - (sin(direction) * horizontalSpeed.value).toFloat()
        val newZ = selfZ + (cos(direction) * horizontalSpeed.value).toFloat()
        val newY = targetPos.y.coerceIn(selfY - verticalSpeed.value, selfY + verticalSpeed.value)
        return Vector3f.from(newX, newY, newZ)
    }

    private fun calculatePosition(selfX: Float, selfZ: Float, targetPos: Vector3f): Vector3f {
        val radius = range.value
        return when (moveMode.value) {
            MoveMode.Aggressive -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.05
                val verticalWave = sin(strafeAngle * 0.7f).toFloat() * 0.25f * verticalSpeed.value
                Vector3f.from(
                    targetPos.x + (cos(strafeAngle) * radius).toFloat(),
                    targetPos.y + verticalWave,
                    targetPos.z + (sin(strafeAngle) * radius).toFloat()
                )
            }
            MoveMode.Strafe -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.03
                val verticalWave = sin(strafeAngle * 0.5f).toFloat() * 0.3f * verticalSpeed.value
                Vector3f.from(
                    targetPos.x + (cos(strafeAngle) * radius * 1.3f).toFloat(),
                    targetPos.y + verticalWave,
                    targetPos.z + (sin(strafeAngle) * radius * 1.3f).toFloat()
                )
            }
            MoveMode.Behind -> {
                val dx = targetPos.x - selfX
                val dz = targetPos.z - selfZ
                val angle = atan2(dz.toDouble(), dx.toDouble()) + Math.PI
                val behindRadius = radius * 1.2f
                Vector3f.from(
                    targetPos.x + (cos(angle) * behindRadius).toFloat(),
                    targetPos.y,
                    targetPos.z + (sin(angle) * behindRadius).toFloat()
                )
            }
            MoveMode.Random -> {
                val angle            = Random.nextDouble(0.0, Math.PI * 2)
                val horizontalOffset = radius * (0.7f + Random.nextFloat() * 0.3f)
                val verticalOffset   = (Random.nextFloat() - 0.5f) * 0.4f * verticalSpeed.value
                Vector3f.from(
                    targetPos.x + (cos(angle) * horizontalOffset).toFloat(),
                    targetPos.y + verticalOffset,
                    targetPos.z + (sin(angle) * horizontalOffset).toFloat()
                )
            }
            MoveMode.Quake -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.05
                quakeTicksLeft--
                if (quakeTicksLeft <= 0) {
                    quakeAngleOffset = Random.nextDouble(-1.4, 1.4)
                    quakeTicksLeft   = quakeHopTicks.value
                }
                val hopAngle = strafeAngle + quakeAngleOffset
                val bounceRaw = sin(strafeAngle * 2.3f).toFloat()
                val bounce = (bounceRaw * bounceRaw) * verticalSpeed.value * (if (bounceRaw < 0) -1f else 1f)
                Vector3f.from(
                    targetPos.x + (cos(hopAngle) * radius).toFloat(),
                    targetPos.y + bounce,
                    targetPos.z + (sin(hopAngle) * radius).toFloat()
                )
            }
        }
    }
}