package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.*
import kotlin.random.Random

class AirFight : BaseModule(
    name = "AirFight",
    category = ModuleCategory.COMBAT,
    description = "."
), PacketEventBus.PacketListener {

    private val orbitRadius      = float("Orbit Radius",      2.8f,  1.0f,  5.0f)
    private val orbitSpeed       = float("Orbit Speed",       8.0f,  2.0f,  20.0f)
    private val verticalAmplitude = float("Vertical Amplitude", 0.8f, 0.0f,  2.5f)
    private val verticalSpeed    = float("Vertical Speed",    3.0f,  0.5f,  8.0f)
    private val approachSpeed    = float("Approach Speed",    8.0f,  2.0f,  15.0f)
    private val attackRange      = float("Attack Range",      4.2f,  1.0f,  6.0f)
    private val cpsMin           = int("CPS Min",             24,    1,     30)
    private val cpsMax           = int("CPS Max",             28,    1,     30)
    private val doubleAttack     = bool("Double Attack",      true)
    private val antiStuck        = bool("Anti Stuck",         true)

    private var orbitAngle = 0.0
    private var targetId = 0L
    @Volatile private var lastAttackMs = 0L
    @Volatile private var attackCount = 0L
    private var moveAttempts = 0L
    private var stuckCounter = 0
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object { const val TAG = "AirFight" }

    override fun onEnable() {
        super.onEnable()
        orbitAngle = Random.nextDouble(0.0, 2.0 * PI)
        targetId = 0L
        attackCount = 0L
        moveAttempts = 0L
        stuckCounter = 0
        PacketEventBus.register(this)
        startMovementLoop()
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        job?.cancel()
        job = null
        super.onDisable()
    }

    private fun startMovementLoop() {
        job?.cancel()
        job = scope.launch {
            while (isEnabled && job?.isActive == true) {
                try {
                    val session = PacketEventBus.currentSession
                    if (session != null) {
                        val target = findTarget()
                        if (target != null) {
                            executeAirFight(session, target)
                        } else {
                            resetToNormal(session)
                        }
                    }
                    delay(20L)
                } catch (_: CancellationException) {
                    throw _
                } catch (_: Exception) {
                    delay(50L)
                }
            }
        }
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(100f)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
        if (candidates.isEmpty()) return null
        if (targetId != 0L) {
            val existing = candidates.find { it.runtimeId == targetId }
            if (existing != null) return existing
        }
        val target = candidates.minByOrNull { EntityTracker.distanceTo(it) }
        if (target != null) targetId = target.runtimeId
        return target
    }

    private fun executeAirFight(session: PacketEventBus.Session, target: EntityTracker.TrackedEntity) {
        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ
        val distance = MathUtil.dist3(selfX, selfY, selfZ, target.x, target.y, target.z)

        val newPos = calculateOrbitPosition(target, distance)
        val rotation = RotationUtil.toEntity(target)

        var finalPos = newPos
        if (antiStuck.value && isStuck(selfX, selfY, selfZ, newPos)) {
            stuckCounter++
            if (stuckCounter > 5) {
                finalPos = Vector3f.from(newPos.x, newPos.y + 1.5f, newPos.z)
                stuckCounter = 0
            }
        } else {
            stuckCounter = 0
        }

        try {
            session.clientBound(MovePlayerPacket().apply {
                runtimeEntityId = EntityTracker.selfRuntimeId
                position = finalPos
                rotation = Vector3f.from(rotation.pitch, rotation.yaw, rotation.yaw)
                mode = MovePlayerPacket.Mode.NORMAL
                isOnGround = false
                ridingRuntimeEntityId = 0L
            })
            moveAttempts++
        } catch (_: Exception) {}

        if (distance <= attackRange.value) {
            tryAttack(session, target)
        }
    }

    private fun calculateOrbitPosition(target: EntityTracker.TrackedEntity, distance: Float): Vector3f {
        val dt = 0.02f
        orbitAngle += orbitSpeed.value * dt
        if (orbitAngle > 2.0 * PI) orbitAngle -= 2.0 * PI

        val targetRadius = when {
            distance > orbitRadius.value * 1.5f -> orbitRadius.value * 0.8f
            distance < orbitRadius.value * 0.7f -> orbitRadius.value * 1.2f
            else -> orbitRadius.value
        }

        val xOffset = cos(orbitAngle) * targetRadius.toDouble()
        val zOffset = sin(orbitAngle) * targetRadius.toDouble()
        val verticalWave = sin(orbitAngle * verticalSpeed.value / 2.0) * verticalAmplitude.value.toDouble()

        val approachFactor = if (distance > orbitRadius.value * 2.0) {
            (approachSpeed.value * dt).coerceAtMost(1.0f)
        } else 0f

        val targetX = target.x + (xOffset.toFloat() * (1f - approachFactor)) +
                     (target.x - EntityTracker.selfX) * approachFactor
        val targetZ = target.z + (zOffset.toFloat() * (1f - approachFactor)) +
                     (target.z - EntityTracker.selfZ) * approachFactor
        val targetY = target.y + verticalWave.toFloat() + 0.2f

        return Vector3f.from(targetX, targetY, targetZ)
    }

    private fun tryAttack(session: PacketEventBus.Session, target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return
        lastAttackMs = now
        attackCount++

        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, target.runtimeId)
        if (doubleAttack.value) {
            session.clientBound(MovePlayerPacket().apply {
                runtimeEntityId = EntityTracker.selfRuntimeId
                position = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY + 0.1f, EntityTracker.selfZ)
                rotation = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
                mode = MovePlayerPacket.Mode.NORMAL
                isOnGround = false
                ridingRuntimeEntityId = 0L
            })
            PacketUtil.sendAttack(session, target.runtimeId)
        }
    }

    private fun isStuck(oldX: Float, oldY: Float, oldZ: Float, newPos: Vector3f): Boolean {
        val dx = newPos.x - oldX
        val dy = newPos.y - oldY
        val dz = newPos.z - oldZ
        return (dx * dx + dy * dy + dz * dz) < 0.01f
    }

    private fun resetToNormal(session: PacketEventBus.Session) {
        try {
            PacketUtil.sendMoveAtSelf(session, EntityTracker.selfYaw, EntityTracker.selfPitch, onGround = true)
        } catch (_: Exception) {}
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return
        if (targetId != 0L) {
            val target = EntityTracker.getById(targetId)
            if (target == null) targetId = 0L
        }
    }
}