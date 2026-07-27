package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.module.social.isFriendEntity
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

class TPAuraPC : BaseModule(
    name        = "TPAuraPC",
    category    = ModuleCategory.COMBAT,
    description = "PC kalitesinde teleport + saldırı modülü"
), PacketEventBus.PacketListener {

    private val range          = float("Reach",           5f,   1f,  16f)
    private val fov            = int  ("FOV",             360,  30,  360)
    private val ignoreFriends  = bool ("Ignore Friends",  true)

    private val minCPS         = int  ("Min CPS",         8,    1,   25)
    private val maxCPS         = int  ("Max CPS",         14,   1,   25)

    private val tpDelay        = int  ("TP Delay (tick)", 0,    0,   20)
    private val attackDelay    = int  ("Attack Delay",    0,    0,   20)
    private val tpDistance     = float("TP Distance",     1.5f, 0.1f, 8f)
    private val tpMoveSpeed    = float("TP Move Speed",   1.5f, 0.1f, 8f)
    private val tpYOffset      = float("Y Offset",        0f,   -3f,  3f)

    enum class TPMode { CORNERS, CIRCLE, RANDOM, BEHIND }
    private val tpMode         = enum ("TP Mode",         TPMode.CIRCLE)

    private val upAndDown      = bool ("Up & Down",       false)
    private val upDownSpeed    = float("UpDown Speed",    0.15f, 0.01f, 2f)
    private val upDownRange    = float("UpDown Range",    1.2f,  0.1f,  4f)

    private val throughWalls   = bool ("Through Walls",   false)
    private val mobAura        = bool ("Mob Aura",        false)

    enum class PriorityMode { DISTANCE, HEALTH, LOWEST_HEALTH, DIRECTION }
    private val priorityMode   = enum ("Priority",        PriorityMode.DISTANCE)
    private val reversePri     = bool ("Reverse Priority", false)

    enum class RotationMode { NONE, SILENT, CLIENT }
    private val rotationMode   = enum ("Rotation",        RotationMode.SILENT)

    enum class TimerMode { STATIC, STUTTER, RAMP }
    private val timerMode      = enum ("Timer Mode",      TimerMode.STATIC)
    private val timerSpeed     = float("Timer Speed",     30f,   20f,  60f)
    private val timerMin       = float("Timer Min",       24f,   20f,  50f)
    private val timerMax       = float("Timer Max",       40f,   20f,  60f)
    private val timerStep      = float("Timer Step",      1f,    0.1f, 5f)
    private val stutterFreq    = float("Stutter Freq",    0.5f,  0f,   5f)
    private val stutterValue   = float("Stutter Value",   10f,   0f,   20f)

    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastTeleportMs = 0L
    @Volatile private var lastRotSendMs = 0L

    @Volatile private var headLockYaw = 0f
    @Volatile private var headLockPitch = 0f

    private var upDownOffset = 0f
    private var upDownGoingUp = true
    private var circleIndex = 0
    private var cornerIndex = 0

    @Volatile private var currentTimer = 20f
    @Volatile private var timerIncreasing = true

    private var tickJob: Job? = null
    private var timerJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        upDownOffset = 0f
        upDownGoingUp = true
        circleIndex = 0
        cornerIndex = 0
        currentTimer = timerMin.value
        timerIncreasing = true
        lastAttackMs = 0L
        lastTeleportMs = 0L
        lastRotSendMs = 0L

        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
        timerJob = scope.launch { timerLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        timerJob?.cancel()
        PacketEventBus.unregister(this)
        resetTimer()
        super.onDisable()
    }

    private suspend fun timerLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) applyTimer() else resetTimer()
            delay(16L)
        }
    }

    private fun applyTimer() {
        val session = PacketEventBus.currentSession ?: return
        when (timerMode.value) {
            TimerMode.STATIC -> setTimer(session, timerSpeed.value)
            TimerMode.STUTTER -> {
                val base = timerSpeed.value
                val stutter = if (stutterFreq.value > 0 && Random.nextDouble() < stutterFreq.value / 20f) {
                    (base - stutterValue.value).coerceIn(timerMin.value, timerMax.value)
                } else base
                setTimer(session, stutter)
            }
            TimerMode.RAMP -> {
                val step = timerStep.value
                if (timerIncreasing) {
                    currentTimer += step
                    if (currentTimer >= timerMax.value) {
                        currentTimer = timerMax.value
                        timerIncreasing = false
                    }
                } else {
                    currentTimer -= step
                    if (currentTimer <= timerMin.value) {
                        currentTimer = timerMin.value
                        timerIncreasing = true
                    }
                }
                setTimer(session, currentTimer.coerceIn(timerMin.value, timerMax.value))
            }
        }
    }

    private fun setTimer(session: OxRelaySession, speed: Float) {
        try {
            val client = session.clientSession.peer.channel.attr(
                org.cloudburstmc.protocol.bedrock.netty.BedrockChannelInitializer.CLIENT_ATTRIBUTE
            ).get()
        } catch (_: Exception) { }
    }

    private fun resetTimer() {
        val session = PacketEventBus.currentSession ?: return
        setTimer(session, 20f)
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        if (rotationMode.value == RotationMode.SILENT) {
            val pkt = event.packet as? PlayerAuthInputPacket ?: return
            val target = selectTargets().firstOrNull()
            if (target != null) {
                applySilentRotation(pkt, target)
            }
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(1L)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val session = PacketEventBus.currentSession ?: return

        val targets = selectTargets()
        if (targets.isEmpty()) return

        val mainTarget = targets.first()

        if (now - lastTeleportMs >= tpDelay.value * 50L) {
            teleportToTarget(session, mainTarget)
            lastTeleportMs = now
        }

        if (now - lastAttackMs >= attackDelay.value * 50L) {
            if (canAttack()) {
                if (mode == 1) {
                    targets.forEach { attack(session, it) }
                } else {
                    attack(session, mainTarget)
                }
                lastAttackMs = now
            }
        }

        if (rotationMode.value == RotationMode.CLIENT && targets.isNotEmpty()) {
            val rot = RotationUtil.toEntity(mainTarget)
            PacketUtil.sendMoveAtSelf(
                session,
                yaw = rot.yaw,
                pitch = rot.pitch,
                onGround = true
            )
        }
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val rangeSq = range.value * range.value

        return EntityTracker.getEntitiesInRange(range.value)
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { isValidTarget(it) }
            .filter { MathUtil.dist3sq(it.x, it.y, it.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) <= rangeSq }
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .filter { !mobAura.value || isMob(it) }
            .sortedWith(compareBy { getPriorityScore(it) })
            .toList()
    }

    private fun isValidTarget(entity: EntityTracker.TrackedEntity): Boolean {
        if (!mobAura.value && !entity.isPlayer) return false
        if (mobAura.value && !entity.isPlayer && !isMob(entity)) return false
        if (entity.health <= 0) return false
        return true
    }

    private fun isMob(entity: EntityTracker.TrackedEntity): Boolean {
        return entity.isHostile || entity.type == EntityTracker.EntityType.ANIMAL ||
               entity.type == EntityTracker.EntityType.PASSIVE
    }

    private fun getPriorityScore(entity: EntityTracker.TrackedEntity): Float {
        val score = when (priorityMode.value) {
            PriorityMode.DISTANCE -> EntityTracker.distanceTo(entity)
            PriorityMode.HEALTH -> entity.health
            PriorityMode.LOWEST_HEALTH -> -entity.health
            PriorityMode.DIRECTION -> EntityTracker.angleToEntity(entity)
        }
        return if (reversePri.value) -score else score
    }

    private fun teleportToTarget(session: OxRelaySession, target: EntityTracker.TrackedEntity) {
        val selfPos = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val targetPos = Vector3f.from(target.x, target.y + tpYOffset.value, target.z)

        var newPos = calculateTeleportPosition(targetPos)

        if (upAndDown.value) {
            if (upDownGoingUp) {
                upDownOffset += upDownSpeed.value
                if (upDownOffset >= upDownRange.value) upDownGoingUp = false
            } else {
                upDownOffset -= upDownSpeed.value
                if (upDownOffset <= -upDownRange.value) upDownGoingUp = true
            }
            newPos = Vector3f.from(newPos.x, targetPos.y + upDownOffset, newPos.z)
        }

        val delta = newPos.sub(selfPos)
        val dist = delta.length()
        if (dist > 0.001f) {
            val moveDist = min(tpMoveSpeed.value, dist)
            val scale = moveDist / dist
            newPos = selfPos.add(delta.mul(scale))
        }

        val movePacket = MovePlayerPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            position = newPos
            rotation = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode = MovePlayerPacket.Mode.NORMAL
            isOnGround = true
            ridingRuntimeEntityId = 0L
        }
        session.serverBound(movePacket)

        EntityTracker.selfX = newPos.x
        EntityTracker.selfY = newPos.y
        EntityTracker.selfZ = newPos.z
    }

    private fun calculateTeleportPosition(targetPos: Vector3f): Vector3f {
        val dist = tpDistance.value
        val randX = ((Random.nextInt(-1000, 1000) / 1000f) * 0.5f)
        val randZ = ((Random.nextInt(-1000, 1000) / 1000f) * 0.5f)

        return when (tpMode.value) {
            TPMode.CORNERS -> {
                cornerIndex = (cornerIndex + 1) % 4
                val offset = dist
                val xOff = if (cornerIndex % 2 == 0) offset else -offset
                val zOff = if (cornerIndex < 2) offset else -offset
                Vector3f.from(
                    targetPos.x + xOff + randX,
                    targetPos.y,
                    targetPos.z + zOff + randZ
                )
            }
            TPMode.CIRCLE -> {
                val angle = circleIndex++ * 45f
                if (circleIndex >= 8) circleIndex = 0
                val rad = Math.toRadians(angle.toDouble())
                Vector3f.from(
                    targetPos.x + (cos(rad) * dist).toFloat() + randX,
                    targetPos.y,
                    targetPos.z + (sin(rad) * dist).toFloat() + randZ
                )
            }
            TPMode.RANDOM -> {
                val angle = Random.nextDouble(0.0, 2 * PI)
                val r = dist * (0.7f + Random.nextFloat() * 0.3f)
                Vector3f.from(
                    targetPos.x + (cos(angle) * r).toFloat(),
                    targetPos.y,
                    targetPos.z + (sin(angle) * r).toFloat()
                )
            }
            TPMode.BEHIND -> {
                val yawRad = Math.toRadians(target.yaw.toDouble())
                Vector3f.from(
                    targetPos.x - (sin(yawRad) * dist).toFloat(),
                    targetPos.y,
                    targetPos.z + (cos(yawRad) * dist).toFloat()
                )
            }
        }
    }

    private fun canAttack(): Boolean {
        if (minCPS.value == 0 && maxCPS.value == 0) return true
        val cps = Random.nextInt(minCPS.value.coerceAtLeast(1), maxCPS.value.coerceAtLeast(1))
        val delayMs = 1000L / cps
        return System.currentTimeMillis() - lastAttackMs >= delayMs
    }

    private fun attack(session: OxRelaySession, target: EntityTracker.TrackedEntity) {
        val clickPos = Vector3f.from(target.x, target.y + 1.62f, target.z)
        val hotbarSlot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)

        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)
        repeat(2) {
            PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)
        }
    }

    private fun applySilentRotation(
        pkt: PlayerAuthInputPacket,
        target: EntityTracker.TrackedEntity
    ) {
        val now = System.currentTimeMillis()
        if (now - lastRotSendMs < 50L) return
        lastRotSendMs = now

        val rot = RotationUtil.toEntity(target)
        val smoothFactor = 0.7f
        val newYaw = smoothAngle(headLockYaw, rot.yaw, smoothFactor)
        val newPitch = smoothAngle(headLockPitch, rot.pitch, smoothFactor)

        headLockYaw = newYaw
        headLockPitch = newPitch

        pkt.rotation = Vector3f.from(newPitch, newYaw, newYaw)
        EntityTracker.selfYaw = newYaw
        EntityTracker.selfPitch = newPitch
    }

    private fun smoothAngle(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val result = current + (diff * factor)
        var normalized = result % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }

    fun getTargetCount(): Int = selectTargets().size
    fun isTargeting(): Boolean = selectTargets().isNotEmpty()
}