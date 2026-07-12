package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.MathUtil
import com.oxclient.utils.RotationUtil
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
    description = "Rakip etrafında hareket eder (attack removed)"
), PacketEventBus.PacketListener {

    enum class MoveMode { Random, Strafe, Behind }

    private val moveMode         = enum ("Mode",              MoveMode.Strafe)
    private val detectRange      = float("Detect Range",      60f,  10f,  100f)
    private val range            = float("Range",             2.5f, 1f,   8f)
    private val horizontalSpeed  = float("Horizontal Speed",  0.4f, 0.5f, 10f)
    private val verticalSpeed    = float("Vertical Speed",    0.3f, 0.1f, 5f)
    private val strafeSpeed      = float("Strafe Speed",      0.8f, 0.1f, 5f)
    private val yOffset          = float("Y Offset",          0.5f, -2f,  2f)
    private val rotateToTarget   = bool ("Rotate To Target",  true)
    private val shortcut         = bool ("Shortcut",          false)

    private var strafeAngle = 0.0
    private var moveAttempts = 0L
    @Volatile private var lastTargetId = 0L

    private companion object { const val TAG = "TPAura" }

    override fun onEnable() {
        super.onEnable()
        strafeAngle = Random.nextDouble(0.0, Math.PI * 2)
        moveAttempts = 0L
        lastTargetId = 0L
        PacketEventBus.register(this)
        OverlayLogger.d(TAG, "Enabled: mode=${moveMode.value} range=${range.value}")
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled (moveAttempts=$moveAttempts)")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val target = findTarget() ?: return
        moveAroundTarget(target)
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(detectRange.value)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
        
        return candidates.minByOrNull { EntityTracker.distanceTo(it) }.also { target ->
            if (target != null && target.runtimeId != lastTargetId) {
                lastTargetId = target.runtimeId
                OverlayLogger.d(TAG, "Target: ${target.name.ifEmpty { target.runtimeId.toString() }}")
            }
        }
    }

    private fun moveAroundTarget(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        val selfX   = EntityTracker.selfX
        val selfY   = EntityTracker.selfY
        val selfZ   = EntityTracker.selfZ

        val targetPos = Vector3f.from(target.x, target.y + yOffset.value, target.z)
        val dist      = MathUtil.dist3(selfX, selfY, selfZ, target.x, target.y, target.z)

        val newPos = if (dist > range.value) {
            stepTowardTarget(selfX, selfY, selfZ, targetPos)
        } else {
            calculatePosition(selfX, selfZ, targetPos)
        }

        val rot = if (rotateToTarget.value) RotationUtil.toEntity(target) else null

        try {
            session.serverBound(MovePlayerPacket().apply {
                runtimeEntityId       = EntityTracker.selfRuntimeId
                position              = newPos
                rotation              = if (rot != null) Vector3f.from(rot.pitch, rot.yaw, rot.yaw) 
                                        else Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, 0f)
                mode                  = MovePlayerPacket.Mode.NORMAL
                isOnGround            = true
                ridingRuntimeEntityId = 0L
            })
            moveAttempts++
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Packet error: ${e.message}", e)
        }
    }

    private fun stepTowardTarget(selfX: Float, selfY: Float, selfZ: Float, targetPos: Vector3f): Vector3f {
        val direction = atan2(
            (targetPos.z - selfZ).toDouble(),
            (targetPos.x - selfX).toDouble()
        ) - Math.toRadians(90.0)

        val newX = selfX - (sin(direction) * horizontalSpeed.value).toFloat()
        val newZ = selfZ + (cos(direction) * horizontalSpeed.value).toFloat()
        val newY = targetPos.y.coerceIn(
            selfY - verticalSpeed.value,
            selfY + verticalSpeed.value
        )
        return Vector3f.from(newX, newY, newZ)
    }

    private fun calculatePosition(selfX: Float, selfZ: Float, targetPos: Vector3f): Vector3f {
        val radius = range.value

        return when (moveMode.value) {
            MoveMode.Random -> {
                val angle            = Random.nextDouble(0.0, Math.PI * 2)
                val horizontalOffset = radius * (0.5f + Random.nextFloat() * 0.5f)
                val verticalOffset   = (Random.nextFloat() - 0.5f) * 0.5f * verticalSpeed.value

                Vector3f.from(
                    targetPos.x + (cos(angle) * horizontalOffset).toFloat(),
                    targetPos.y + verticalOffset,
                    targetPos.z + (sin(angle) * horizontalOffset).toFloat()
                )
            }

            MoveMode.Strafe -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.03
                val verticalWave = sin(strafeAngle * 0.5f).toFloat() * 0.3f * verticalSpeed.value

                Vector3f.from(
                    targetPos.x + (cos(strafeAngle) * radius).toFloat(),
                    targetPos.y + verticalWave,
                    targetPos.z + (sin(strafeAngle) * radius).toFloat()
                )
            }

            MoveMode.Behind -> {
                val dx = targetPos.x - selfX
                val dz = targetPos.z - selfZ
                val angle = atan2(dz.toDouble(), dx.toDouble()) + Math.PI
                val behindRadius = radius * 0.8f

                Vector3f.from(
                    targetPos.x + (cos(angle) * behindRadius).toFloat(),
                    targetPos.y,
                    targetPos.z + (sin(angle) * behindRadius).toFloat()
                )
            }
        }
    }
}
