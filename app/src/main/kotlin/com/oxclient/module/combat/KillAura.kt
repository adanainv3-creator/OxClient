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
    description = "Otomatik saldırı - maksimum hasar"
) {
    enum class AttackMode   { Single, Multi, Switch, Closest }
    enum class RotationMode { Lock, Approximate, Silent, None }
    enum class SwingMode    { Client, Server, Both, None }
    enum class PriorityMode { Distance, Health, Direction, LowestHealth }

    // ⚡ ANARŞI OPTİMİZE: CPS strictly 20, double attack her vuruş
    private val cpsMin          = int  ("CPS Min",          20,   20, 20)
    private val cpsMax          = int  ("CPS Max",          20,   20, 20)
    private val range           = float("Range",            6.0f, 1f,  10f)
    private val fov             = int  ("FOV",              360,  30, 360)
    private val switchDelay     = int  ("Switch Delay",     0,    0,  500)
    private val maxTargets      = int  ("Max Targets",      8,    1,  10)
    private val attackMode      = enum ("Attack Mode",      AttackMode.Multi)
    private val rotationMode    = enum ("Rotation Mode",    RotationMode.Silent)
    private val swingMode       = enum ("Swing",            SwingMode.Both)
    private val priorityMode    = enum ("Priority",         PriorityMode.LowestHealth)
    private val reversePriority = bool ("Reverse Priority", false)
    private val failRate        = float("Fail Rate",        0.0f, 0f, 0.5f)
    private val shortcut        = bool ("Shortcut",         false)

    private companion object { 
        const val TAG = "KillAura" 
    }

    @Volatile private var currentTargetId   = 0L
    @Volatile private var lastSwitchMs      = 0L
    @Volatile private var lastAttackMs      = 0L
    @Volatile private var lastCritMs        = 0L
    @Volatile private var consecutiveMisses = 0
    @Volatile private var attackCount       = 0L
    
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        currentTargetId = 0L
        consecutiveMisses = 0
        attackCount = 0L
        
        OverlayLogger.d(TAG, "Enabled: 20 CPS (hard limit) | Multi=8targets | Silent Rotation | Double Attack EVERY swing")
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
            delay(1L)
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        // 20 CPS = 50ms per attack — sıkı tutma
        val delayMs = 50L
        
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
                    if (!shouldFail()) {
                        doAttack(target, now)
                    }
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

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val candidates = EntityTracker.getEntitiesInRange(range.value)
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
            .toMutableList()
        return candidates
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
            PriorityMode.Distance -> candidates.sortedBy { EntityTracker.distanceTo(it) }
            PriorityMode.Health -> candidates.sortedBy { it.health }
            PriorityMode.LowestHealth -> candidates.sortedBy { it.health }
            PriorityMode.Direction -> candidates.sortedBy { EntityTracker.angleToEntity(it) }
        }

        val result = if (reversePriority.value) sorted.lastOrNull() else sorted.firstOrNull()
        
        if (attackMode.value == AttackMode.Switch && result != null) {
            currentTargetId = result.runtimeId
        }
        
        return result
    }

    // ⚡ doAttack anında dönüyor, gerçek attack sequence arka planda coroutine'de.
    private fun doAttack(e: EntityTracker.TrackedEntity, now: Long) {
        scope.launch { performAttackSequence(e) }
    }

    // ⚡ OPTİMİZE: 
    // 1. Rotation delay'i kaldır (Silent Rotation hızlı)
    // 2. Crit delay'leri 30ms'ye düşür (60->30ms, 50->20ms)
    // 3. HER saldırıda DOUBLE ATTACK yap (şu an her 2'de 1)
    // 4. Swing'i server+client çift gönder
    private suspend fun performAttackSequence(e: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return
        
        OverlayLogger.v(TAG, "Attack #${attackCount}: dist=${"%.2f".format(EntityTracker.distanceTo(e))}")

        // Silent rotation — rotation packet'ini doğrudan gönder ama doğru timing'de
        if (rotationMode.value != RotationMode.None) {
            val r = RotationUtil.toEntity(e)
            when (rotationMode.value) {
                RotationMode.Lock -> PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
                RotationMode.Approximate -> {
                    val approx = RotationUtil.approximate(r)
                    PacketUtil.sendMoveAtSelf(session, approx.yaw, approx.pitch)
                }
                RotationMode.Silent -> PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch, teleport = false)
                RotationMode.None -> {}
            }
        }

        // ⚡ OPTİMİZE: Crit delay'leri minimize et
        // Sunucu fallDistance hesaplaması için ~30ms yeterli (hızlı tick rate)
        injectCritOptimized(session)
        kotlinx.coroutines.delay(30L)

        // İlk attack
        when (swingMode.value) {
            SwingMode.Server, SwingMode.Both -> PacketUtil.sendSwing(session)
            else -> {}
        }
        PacketUtil.sendAttack(session, e.runtimeId)

        // ⚡ OPTİMİZE: Her saldırıda double attack (şu an her 2'de 1 idi)
        // Minimal delay, aynı tick'te işlenmesi için
        delay(1L)
        PacketUtil.sendAttack(session, e.runtimeId)
    }

    // ⚡ Crit injection delay'leri minimize, fallDistance hesaplaması için yeterli
    private suspend fun injectCritOptimized(session: com.oxclient.core.relay.OxRelaySession) {
        val now = System.currentTimeMillis()
        if (now - lastCritMs < 5) return
        lastCritMs = now

        // Hızlı crit: up + delay + down
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0.08f, onGround = false)
        kotlinx.coroutines.delay(20L)
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0.032f, onGround = false)
    }

    private fun shouldFail(): Boolean = failRate.value > 0f && Math.random() < failRate.value
}
