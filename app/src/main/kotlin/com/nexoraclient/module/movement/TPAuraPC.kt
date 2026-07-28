package com.nexoraclient.module.movement

import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.core.relay.NexoraRelaySession
import com.nexoraclient.events.PacketEvent
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.*
import com.nexoraclient.module.social.isFriendEntity
import com.nexoraclient.utils.MathUtil
import com.nexoraclient.utils.PacketUtil
import com.nexoraclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

class TPAuraPC : BaseModule(
    name        = "NexoraAura",
    category    = ModuleCategory.COMBAT,
    description = "PC kalitesinde teleport + saldırı modülü"
), PacketEventBus.PacketListener {

    private val range          = float("Reach",           16f,  1f,  16f)
    private val fov            = int  ("FOV",             360,  30,  360)
    private val ignoreFriends  = bool ("Ignore Friends",  true)

    private val minCPS         = int  ("Min CPS",         20,   1,   25)
    private val maxCPS         = int  ("Max CPS",         25,   1,   25)

    private val tpDelay        = int  ("TP Delay (tick)", 0,    0,   20)
    private val attackDelay    = int  ("Attack Delay",    0,    0,   20)
    private val tpDistance     = float("TP Distance",     0.5f, 0.1f, 8f)
    private val tpMoveSpeed    = float("TP Move Speed",   8f,   0.1f, 8f)
    private val tpYOffset      = float("Y Offset",        0f,   -3f,  3f)

    enum class TPMode { CORNERS, CIRCLE, RANDOM, BEHIND }
    private val tpMode         = enum ("TP Mode",         TPMode.BEHIND)

    private val upAndDown      = bool ("Up & Down",       false)
    private val upDownSpeed    = float("UpDown Speed",    0.15f, 0.01f, 2f)
    private val upDownRange    = float("UpDown Range",    1.2f,  0.1f,  4f)

    private val throughWalls   = bool ("Through Walls",   true)
    private val mobAura        = bool ("Mob Aura",        false)

    enum class PriorityMode { DISTANCE, HEALTH, LOWEST_HEALTH, DIRECTION }
    private val priorityMode   = enum ("Priority",        PriorityMode.LOWEST_HEALTH)
    private val reversePri     = bool ("Reverse Priority", false)

    enum class RotationMode { NONE, SILENT, CLIENT }
    private val rotationMode   = enum ("Rotation",        RotationMode.SILENT)
    private val shortcut       = bool ("Shortcut",        false)

    @Volatile private var lastAttackMs    = 0L
    @Volatile private var lastTeleportMs  = 0L
    @Volatile private var lastRotSendMs   = 0L

    @Volatile private var headLockYaw   = 0f
    @Volatile private var headLockPitch = 0f

    private var upDownOffset   = 0f
    private var upDownGoingUp  = true
    private var circleIndex    = 0
    private var cornerIndex    = 0

    // selectTargets cache — hem onPacket hem tick() aynı listeyi kullanır
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var lastSelectMs  = 0L
    private val SELECT_CACHE_MS = 50L   // tick hızıyla aynı pencere

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        headLockYaw   = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        upDownOffset  = 0f
        upDownGoingUp = true
        circleIndex   = 0
        cornerIndex   = 0
        lastAttackMs  = 0L
        lastTeleportMs = 0L
        lastRotSendMs  = 0L
        lastSelectMs   = 0L
        cachedTargets  = emptyList()

        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        cachedTargets = emptyList()
        super.onDisable()
    }

    // Hedef listesini en fazla SELECT_CACHE_MS aralıkla bir kez hesaplar.
    private fun getCachedTargets(): List<EntityTracker.TrackedEntity> {
        val now = System.currentTimeMillis()
        if (now - lastSelectMs >= SELECT_CACHE_MS) {
            cachedTargets = selectTargets()
            lastSelectMs  = now
        }
        return cachedTargets
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        if (rotationMode.value == RotationMode.SILENT) {
            val target = getCachedTargets().firstOrNull() ?: return
            applySilentRotation(pkt, target)
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                try {
                    tick()
                } catch (e: Exception) {
                    android.util.Log.e("TPAuraPC", "tick() failed: ${e.message}", e)
                }
            }
            delay(50L)
        }
    }

    private fun tick() {
        val now     = System.currentTimeMillis()
        val session = PacketEventBus.currentSession ?: return

        val targets = getCachedTargets()
        if (targets.isEmpty()) return

        val mainTarget = targets.first()

        if (now - lastTeleportMs >= tpDelay.value * 50L) {
            teleportToTarget(session, mainTarget)
            lastTeleportMs = now
        }

        if (now - lastAttackMs >= attackDelay.value * 50L) {
            if (canAttack()) {
                attack(session, mainTarget)
                lastAttackMs = now
            }
        }

        if (rotationMode.value == RotationMode.CLIENT) {
            val rot = RotationUtil.toEntity(mainTarget)
            PacketUtil.sendMoveAtSelf(
                session,
                yaw     = rot.yaw,
                pitch   = rot.pitch,
                onGround = true
            )
        }
    }

    // getEntitiesInRange zaten range'e göre kırpar; ikinci mesafe filtresi kaldırıldı.
    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        return EntityTracker.getEntitiesInRange(range.value)
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { isValidTarget(it) }
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
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
            PriorityMode.DISTANCE      -> EntityTracker.distanceTo(entity)
            PriorityMode.HEALTH        -> entity.health
            PriorityMode.LOWEST_HEALTH -> -entity.health
            PriorityMode.DIRECTION     -> EntityTracker.angleToEntity(entity)
        }
        return if (reversePri.value) -score else score
    }

    private fun teleportToTarget(session: NexoraRelaySession, target: EntityTracker.TrackedEntity) {
        val selfPos   = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
        val targetPos = Vector3f.from(target.x, target.y + tpYOffset.value, target.z)

        var newPos = calculateTeleportPosition(targetPos, target)

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
        val dist  = delta.length()
        if (dist > 0.001f) {
            val moveDist = min(tpMoveSpeed.value, dist)
            val scale    = moveDist / dist
            newPos = selfPos.add(delta.mul(scale))
        }

        val movePacket = MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = newPos
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        }
        session.serverBound(movePacket)
        session.clientBound(movePacket)

        EntityTracker.selfX = newPos.x
        EntityTracker.selfY = newPos.y
        EntityTracker.selfZ = newPos.z
    }

    private fun calculateTeleportPosition(targetPos: Vector3f, target: EntityTracker.TrackedEntity): Vector3f {
        val dist  = tpDistance.value
        val randX = (Random.nextInt(-1000, 1000) / 1000f) * 0.5f
        val randZ = (Random.nextInt(-1000, 1000) / 1000f) * 0.5f

        return when (tpMode.value) {
            TPMode.CORNERS -> {
                cornerIndex = (cornerIndex + 1) % 4
                val xOff = if (cornerIndex % 2 == 0) dist else -dist
                val zOff = if (cornerIndex < 2) dist else -dist
                Vector3f.from(targetPos.x + xOff + randX, targetPos.y, targetPos.z + zOff + randZ)
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
                val r     = dist * (0.7f + Random.nextFloat() * 0.3f)
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
        val cps     = Random.nextInt(minCPS.value.coerceAtLeast(1), maxCPS.value.coerceAtLeast(1))
        val delayMs = 1000L / cps
        return System.currentTimeMillis() - lastAttackMs >= delayMs
    }

    // Eskiden 1 swing + 3 attack paketi gönderiliyordu (repeat(2) fazladandı).
    // Sunucu aynı tick'teki çift saldırıyı zaten ignore eder; sadece 1 attack yeterli.
    private fun attack(session: NexoraRelaySession, target: EntityTracker.TrackedEntity) {
        val clickPos   = Vector3f.from(target.x, target.y + 1.62f, target.z)
        val hotbarSlot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)
    }

    private fun applySilentRotation(pkt: PlayerAuthInputPacket, target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastRotSendMs < 50L) return
        lastRotSendMs = now

        val rot         = RotationUtil.toEntity(target)
        val smoothFactor = 0.7f
        val newYaw      = smoothAngle(headLockYaw,   rot.yaw,   smoothFactor)
        val newPitch    = smoothAngle(headLockPitch, rot.pitch, smoothFactor)

        headLockYaw   = newYaw
        headLockPitch = newPitch

        pkt.rotation = Vector3f.from(newPitch, newYaw, newYaw)
        EntityTracker.selfYaw   = newYaw
        EntityTracker.selfPitch = newPitch
    }

    private fun smoothAngle(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        if (diff > 180f)  diff -= 360f
        if (diff < -180f) diff += 360f
        val result = current + (diff * factor)
        var normalized = result % 360f
        if (normalized > 180f)  normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }

    // Cached versiyon — her çağrı ayrı tarama yapmaz
    fun getTargetCount(): Int  = getCachedTargets().size
    fun isTargeting(): Boolean = getCachedTargets().isNotEmpty()
}
