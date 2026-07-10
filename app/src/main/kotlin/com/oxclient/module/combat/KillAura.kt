
package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import kotlinx.coroutines.*

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Otomatik saldırı"
), PacketEventBus.PacketListener {
    
    enum class AttackMode   { Single, Multi, Switch, Closest }
    enum class RotationMode { Lock, Approximate, Silent, None }
    enum class SwingMode    { Client, Server, Both, None }
    enum class PriorityMode { Distance, Health, Direction, LowestHealth }

    private val cpsMin          = int  ("CPS Min",          15,   1,  30)
    private val cpsMax          = int  ("CPS Max",          18,   1,  30)
    private val range           = float("Range",            6.0f, 1f,  10f)
    private val fov             = int  ("FOV",              360,  30, 360)
    private val switchDelay     = int  ("Switch Delay",     0,    0,  500)
    private val maxTargets      = int  ("Max Targets",      3,    1,  10)
    private val attackMode      = enum ("Attack Mode",      AttackMode.Multi)
    private val rotationMode    = enum ("Rotation Mode",    RotationMode.Lock)
    private val swingMode       = enum ("Swing",            SwingMode.Both)
    private val priorityMode    = enum ("Priority",         PriorityMode.LowestHealth)
    private val reversePriority = bool ("Reverse Priority", false)
    private val failRate        = float("Fail Rate",        0.0f, 0f, 0.5f)
    private val headLock        = bool ("Head Lock",        true)
    private val headLockSmooth  = float("Head Lock Smooth", 0.2f, 0.01f, 1f)
    private val shortcut        = bool ("Shortcut",         false)

    @Volatile private var currentTargetId    = 0L
    @Volatile private var lastSwitchMs       = 0L
    @Volatile private var lastAttackMs       = 0L
    @Volatile private var lastRotationSendMs = 0L
    @Volatile private var consecutiveMisses  = 0
    @Volatile private var attackCount        = 0L
    @Volatile private var headLockYaw        = 0f
    @Volatile private var headLockPitch      = 0f
    
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        currentTargetId = 0L
        consecutiveMisses = 0
        attackCount = 0L
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled || !headLock.value) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket) return
        updateHeadLock()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(1L)
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        val targets = selectTargets()
        if (targets.isEmpty()) {
            consecutiveMisses++
            return
        }

        consecutiveMisses = 0
        attackCount++

        when (attackMode.value) {
            AttackMode.Multi -> {
                val maxTargetsVal = maxTargets.value.coerceAtMost(targets.size)
                targets.take(maxTargetsVal).forEach { target ->
                    if (!shouldFail()) doAttack(target, now)
                }
                lastAttackMs = now
            }
            AttackMode.Closest -> {
                val target = targets.minByOrNull { EntityTracker.distanceTo(it) }
                if (target != null && !shouldFail()) {
                    doAttack(target, now)
                    lastAttackMs = now
                }
            }
            else -> {
                val target = selectTarget(targets)
                if (target != null && !shouldFail()) {
                    doAttack(target, now)
                    lastAttackMs = now
                }
            }
        }
    }

    private fun updateHeadLock() {
        val session = PacketEventBus.currentSession ?: return
        val target = findHeadLockTarget() ?: return

        val now = System.currentTimeMillis()
        if (now - lastRotationSendMs < 50L) return
        lastRotationSendMs = now

        val targetRot = RotationUtil.toEntity(target)
        val smoothFactor = headLockSmooth.value.coerceIn(0.01f, 1f)

        val newYaw = smoothYaw(headLockYaw, targetRot.yaw, smoothFactor)
        val newPitch = smoothPitch(headLockPitch, targetRot.pitch, smoothFactor)

        headLockYaw = newYaw
        headLockPitch = newPitch

        try {
            PacketUtil.sendMoveAtSelf(session, newYaw, newPitch, onGround = true)
        } catch (e: Exception) {
        }
    }

    private fun smoothYaw(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val result = current + (diff * factor)
        var normalized = result % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }

    private fun smoothPitch(current: Float, target: Float, factor: Float): Float {
        val diff = target - current
        return (current + (diff * factor)).coerceIn(-90f, 90f)
    }

    private fun findHeadLockTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(range.value * 1.5f)
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
        return selectTarget(candidates)
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        return EntityTracker.getEntitiesInRange(range.value)
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
            .toMutableList()
    }

    private fun selectTarget(candidates: List<EntityTracker.TrackedEntity>): EntityTracker.TrackedEntity? {
        if (candidates.isEmpty()) return null

        if (attackMode.value == AttackMode.Switch) {
            val now = System.currentTimeMillis()
            if (now - lastSwitchMs >= switchDelay.value) {
                currentTargetId = 0L
                lastSwitchMs = now
            }
            val cur = candidates.find { it.runtimeId == currentTargetId }
            if (cur != null) return cur
        }

        val sorted = when (priorityMode.value) {
            PriorityMode.Distance    -> candidates.sortedBy { EntityTracker.distanceTo(it) }
            PriorityMode.Health      -> candidates.sortedBy { it.health }
            PriorityMode.LowestHealth -> candidates.sortedBy { it.health }
            PriorityMode.Direction   -> candidates.sortedBy { EntityTracker.angleToEntity(it) }
        }

        val result = if (reversePriority.value) sorted.lastOrNull() else sorted.firstOrNull()
        if (attackMode.value == AttackMode.Switch && result != null) {
            currentTargetId = result.runtimeId
        }
        return result
    }

    private fun doAttack(e: EntityTracker.TrackedEntity, now: Long) {
        scope.launch { performAttackSequence(e) }
    }

    private suspend fun performAttackSequence(e: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        if (rotationMode.value != RotationMode.None) {
            val r = RotationUtil.toEntity(e)
            when (rotationMode.value) {
                RotationMode.Lock -> PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
                RotationMode.Approximate -> {
                    val approx = RotationUtil.approximate(r)
                    PacketUtil.sendMoveAtSelf(session, approx.yaw, approx.pitch)
                }
                RotationMode.Silent -> {}
                RotationMode.None   -> {}
            }
        }

        injectCrit(session)
        kotlinx.coroutines.delay(50L)

        when (swingMode.value) {
            SwingMode.Server, SwingMode.Both -> PacketUtil.sendSwing(session)
            else -> {}
        }

        PacketUtil.sendAttack(session, e.runtimeId)
        if (attackCount % 2 == 0L) {
            kotlinx.coroutines.delay(1)
            PacketUtil.sendAttack(session, e.runtimeId)
        }
    }

    private suspend fun injectCrit(session: com.oxclient.core.relay.OxRelaySession) {
        val now = System.currentTimeMillis()
        if (now - lastAttackMs < 5) return
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0.0625f, onGround = false)
        kotlinx.coroutines.delay(50L)
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0.042f, onGround = false)
    }

    private fun shouldFail(): Boolean = failRate.value > 0f && Math.random() < failRate.value
}
