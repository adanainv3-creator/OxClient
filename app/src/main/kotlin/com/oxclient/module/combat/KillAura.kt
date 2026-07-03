package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import kotlinx.coroutines.*

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Yakındaki düşmanlara otomatik saldırır"
) {
    enum class AttackMode   { Single, Multi, Switch, Closest }
    enum class RotationMode { Lock, Approximate, Silent, None }
    enum class SwingMode    { Client, Server, Both, None }
    enum class PriorityMode { Distance, Health, Direction, LowestHealth }
    enum class RaycastMode  { None, Basic }

    private val cpsMin          = int  ("CPS Min",          8,    1,  20)
    private val cpsMax          = int  ("CPS Max",          12,   1,  20)
    private val range           = float("Range",            3.7f, 1f,  8f)
    private val fov             = int  ("FOV",              180,  30, 360)
    private val switchDelay     = int  ("Switch Delay",     50,   0,  500)
    private val maxTargets      = int  ("Max Targets",      1,    1,  10)
    private val attackMode      = enum ("Attack Mode",      AttackMode.Single)
    private val rotationMode    = enum ("Rotation Mode",    RotationMode.Lock)
    private val swingMode       = enum ("Swing",            SwingMode.Both)
    private val priorityMode    = enum ("Priority",         PriorityMode.Distance)
    private val raycastMode     = enum ("Raycast",          RaycastMode.None)
    private val reversePriority = bool ("Reverse Priority", false)
    private val failRate        = float("Fail Rate",        0f,   0f, 0.5f)
    private val autoBlock       = bool ("Auto Block",       false)
    private val shortcut        = bool ("Shortcut",         false)

    private companion object { const val TAG = "KillAura" }

    private var currentTargetId   = 0L
    private var lastSwitchMs      = 0L
    private var lastAttackMs      = 0L
    private var consecutiveMisses = 0
    private var tickJob: Job?     = null

    override fun onEnable() {
        super.onEnable()
        currentTargetId   = 0L
        consecutiveMisses = 0
        OverlayLogger.d(TAG, "Enabled: mode=${attackMode.value} range=${range.value} fov=${fov.value} rotation=${rotationMode.value} swing=${swingMode.value}")
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled")
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(10L)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastAttackMs < MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)) return

        val inRange = EntityTracker.getEntitiesInRange(range.value)
        if (inRange.isEmpty() && consecutiveMisses % 40 == 0) {
            // Range içinde hiç entity yoksa periyodik olarak logla (spam olmasın diye %40)
            OverlayLogger.v(TAG, "tick: range=${range.value} içinde entity yok (EntityTracker boş dönüyor mu kontrol et)")
        }

        when (attackMode.value) {
            AttackMode.Multi   -> attackMulti(now)
            AttackMode.Closest -> attackClosest(now)
            else               -> attackSingle(now)
        }
    }

    private fun attackSingle(now: Long) {
        val target = selectTarget() ?: run {
            consecutiveMisses++
            if (consecutiveMisses == 1 || consecutiveMisses % 50 == 0) {
                OverlayLogger.v(TAG, "selectTarget() null döndü (consecutiveMisses=$consecutiveMisses) — FOV=${fov.value} range=${range.value}")
            }
            return
        }
        consecutiveMisses = 0
        if (shouldFail()) {
            OverlayLogger.v(TAG, "shouldFail() true — saldırı bilerek atlandı (failRate=${failRate.value})")
            return
        }
        lastAttackMs = now
        doAttack(target)
    }

    private fun attackClosest(now: Long) {
        val candidates = EntityTracker.getEntitiesInRange(range.value).filter { inFov(it) }
        val target = candidates.minByOrNull { EntityTracker.distanceTo(it) }
        if (target == null) {
            OverlayLogger.v(TAG, "attackClosest: uygun hedef yok (candidates=${candidates.size})")
            return
        }
        if (shouldFail()) return
        lastAttackMs = now
        doAttack(target)
    }

    private fun attackMulti(now: Long) {
        val targets = EntityTracker.getEntitiesInRange(range.value)
            .filter { inFov(it) }
            .take(maxTargets.value)
        if (targets.isEmpty()) {
            OverlayLogger.v(TAG, "attackMulti: hedef listesi boş")
            return
        }
        lastAttackMs = now
        targets.forEach { e -> if (!shouldFail()) doAttack(e) }
    }

    private fun doAttack(e: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "doAttack: PacketEventBus.currentSession null — relay bağlı değil mi?")
            return
        }
        OverlayLogger.v(TAG, "doAttack: target=${e.runtimeId} dist=${"%.2f".format(EntityTracker.distanceTo(e))} rotation=${rotationMode.value} swing=${swingMode.value}")

        when (rotationMode.value) {
            RotationMode.Lock -> {
                val r = RotationUtil.toEntity(e)
                PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
            }
            RotationMode.Approximate -> {
                val r = RotationUtil.approximate(RotationUtil.toEntity(e))
                PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
            }
            RotationMode.Silent -> {
                val r = RotationUtil.toEntity(e)
                PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch, teleport = false)
            }
            RotationMode.None -> {}
        }

        when (swingMode.value) {
            SwingMode.Server, SwingMode.Both -> PacketUtil.sendSwing(session)
            else -> {}
        }

        PacketUtil.sendAttack(session, e.runtimeId)
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(range.value).filter { inFov(it) }
        if (candidates.isEmpty()) return null

        if (attackMode.value == AttackMode.Switch) {
            val now = System.currentTimeMillis()
            if (now - lastSwitchMs >= switchDelay.value) { currentTargetId = 0L; lastSwitchMs = now }
            val cur = candidates.find { it.runtimeId == currentTargetId }
            if (cur != null) return cur
        }

        val sorted = when (priorityMode.value) {
            PriorityMode.Distance     -> candidates.sortedBy { EntityTracker.distanceTo(it) }
            PriorityMode.Health       -> candidates.sortedBy { it.health }
            PriorityMode.LowestHealth -> candidates.sortedBy { it.health }
            // FIX: use EntityTracker.angleToEntity (was unresolved in old version)
            PriorityMode.Direction    -> candidates.sortedBy { entity: EntityTracker.TrackedEntity ->
                EntityTracker.angleToEntity(entity)
            }
        }

        val result = if (reversePriority.value) sorted.lastOrNull() else sorted.firstOrNull()
        if (attackMode.value == AttackMode.Switch && result != null) currentTargetId = result.runtimeId
        return result
    }

    private fun inFov(e: EntityTracker.TrackedEntity): Boolean =
        fov.value >= 360 || EntityTracker.angleToEntity(e) <= fov.value / 2f

    private fun shouldFail(): Boolean =
        failRate.value > 0f && Math.random() < failRate.value
}
