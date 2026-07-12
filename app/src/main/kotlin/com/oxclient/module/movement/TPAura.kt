package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.MathUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Hedef etrafında dolaş (KillAura ile uyumlu)"
), PacketEventBus.PacketListener {

    enum class MoveMode { Strafe, Aggressive, Behind, Random, PVP }

    private val moveMode         = enum ("Mode",              MoveMode.Aggressive)
    private val detectRange      = float("Detect Range",      500f, 10f,  500f)
    private val range            = float("Range",             2.5f, 1f,   8f)
    private val horizontalSpeed  = float("Horizontal Speed",  4f,   0.5f, 10f)
    private val verticalSpeed    = float("Vertical Speed",    1.5f, 0.1f, 5f)
    private val strafeSpeed      = float("Strafe Speed",      2.5f, 0.1f, 20f)
    private val yOffset          = float("Y Offset",          0.8f, -2f,   2f)
    private val pvpDepth         = float("PVP Depth",         2.5f, 2f,   3f)
    private val pvpRadius        = float("PVP Radius",        1.2f, 0.5f, 2.5f)
    private val shortcut         = bool ("Shortcut",          false)

    private var strafeAngle = 0.0
    private var moveAttempts = 0L
    private var tickJob: Job? = null
    @Volatile private var desiredPos: Vector3f? = null

    override fun onEnable() {
        super.onEnable()
        strafeAngle = Random.nextDouble(0.0, Math.PI * 2)
        moveAttempts = 0L
        desiredPos = null
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        desiredPos = null
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(50L)
        }
    }

    private fun tick() {
        val target = findTarget()
        if (target == null) {
            desiredPos = null
            return
        }
        desiredPos = computeDesiredPos(target)
        moveAttempts++
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (!event.isClientToServer) return
        val packet = event.packet
        if (packet !is PlayerAuthInputPacket) return
        val pos = desiredPos ?: return
        packet.position = pos
        event.cancelAndReplace(packet)
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(detectRange.value)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
        return candidates.minByOrNull { EntityTracker.distanceTo(it) }
    }

    private fun computeDesiredPos(target: EntityTracker.TrackedEntity): Vector3f {
        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        val targetPos = Vector3f.from(target.x, target.y + yOffset.value, target.z)
        val dist = MathUtil.dist3(selfX, selfY, selfZ, target.x, target.y, target.z)

        return if (dist > range.value) {
            stepTowardTarget(selfX, selfY, selfZ, targetPos)
        } else {
            calculatePosition(selfX, selfZ, targetPos, target)
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

    private fun calculatePosition(selfX: Float, selfZ: Float, targetPos: Vector3f, target: EntityTracker.TrackedEntity): Vector3f {
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

            MoveMode.PVP -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.045

                Vector3f.from(
                    target.x + (cos(strafeAngle) * pvpRadius.value).toFloat(),
                    target.y - pvpDepth.value,
                    target.z + (sin(strafeAngle) * pvpRadius.value).toFloat()
                )
            }
        }
    }
}
