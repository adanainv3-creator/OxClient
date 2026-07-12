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
import org.cloudburstmc.math.vector.Vector3f

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Otomatik saldırı (GUARANTEED CRIT)"
), PacketEventBus.PacketListener {
    
    enum class AttackMode   { Single, Multi, Switch, Closest }
    enum class RotationMode { Lock, Approximate, Silent, None }
    enum class SwingMode    { Client, Server, Both, None }
    enum class PriorityMode { Distance, Health, Direction, LowestHealth }
    enum class CritMode     { Vanilla, MovePacket, Jump }

    private val cpsMin          = int  ("CPS Min",          12,   1,  30)
    private val cpsMax          = int  ("CPS Max",          16,   1,  30)
    private val range           = float("Range",            6.5f, 1f,  10f)
    private val fov             = int  ("FOV",              360,  30, 360)
    private val switchDelay     = int  ("Switch Delay",     0,    0,  500)
    private val maxTargets      = int  ("Max Targets",      5,    1,  10)
    private val attackMode      = enum ("Attack Mode",      AttackMode.Multi)
    private val rotationMode    = enum ("Rotation Mode",    RotationMode.Lock)
    private val swingMode       = enum ("Swing",            SwingMode.Both)
    private val priorityMode    = enum ("Priority",         PriorityMode.LowestHealth)
    private val reversePriority = bool ("Reverse Priority", false)
    private val failRate        = float("Fail Rate",        0.0f, 0f, 0.5f)
    private val headLock        = bool ("Head Lock",        true)
    private val headLockSmooth  = float("Head Lock Smooth", 0.9f, 0.01f, 1f)
    
    // ✅ GUARANTEED CRIT
    private val critMode        = enum ("Crit Mode",        CritMode.MovePacket)  // 2 paket, en hızlı
    private val predictDelay    = float("Predict Delay",    0.1f, 0.05f, 0.5f)
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
        OverlayLogger.d(TAG, "Enabled: GUARANTEED CRIT mode | CPS=${cpsMin.value}-${cpsMax.value} CritMode=${critMode.value}")
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled (totalAttacks=$attackCount)")
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
                    if (!shouldFail()) doAttack(target)
                }
                lastAttackMs = now
            }
            AttackMode.Closest -> {
                val target = targets.minByOrNull { EntityTracker.distanceTo(it) }
                if (target != null && !shouldFail()) {
                    doAttack(target)
                    lastAttackMs = now
                }
            }
            else -> {
                val target = selectTarget(targets)
                if (target != null && !shouldFail()) {
                    doAttack(target)
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
            OverlayLogger.e(TAG, "HeadLock error: ${e.message}", e)
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

    private fun doAttack(e: EntityTracker.TrackedEntity) {
        scope.launch { performAttackSequence(e) }
    }

    /**
     * ✅ HER VURUŞ KRİTİK
     * 
     * Sıra:
     * 1. Crit effect (Y offset paketleri) - GUARANTEED
     * 2. Rotation update
     * 3. Swing animation
     * 4. Attack packet
     */
    private suspend fun performAttackSequence(e: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        // Tahmin edilen konum
        val predPos = e.predictedPosition(predictDelay.value)
        val clickPos = Vector3f.from(
            predPos.first, 
            (predPos.second + 1.62f).coerceIn(predPos.second - 0.5f, predPos.second + 2f), 
            predPos.third
        )
        val targetRot = RotationUtil.toPoint(predPos.first, predPos.second + 1.62f, predPos.third)

        // ✅ 1. KRİTİK EFFECT (GUARANTEED) — her attack'ta mutlaka çalışır
        injectCrit(session)

        // ✅ 2. Rotasyonu güncelle
        if (rotationMode.value != RotationMode.None) {
            val rot = when (rotationMode.value) {
                RotationMode.Lock -> targetRot
                RotationMode.Approximate -> RotationUtil.approximate(targetRot)
                else -> targetRot
            }
            PacketUtil.sendMoveAtSelf(session, rot.yaw, rot.pitch, onGround = true)
        }

        // ✅ 3. Saldırı animasyonu
        when (swingMode.value) {
            SwingMode.Server, SwingMode.Both -> PacketUtil.sendSwing(session)
            else -> {}
        }

        // ✅ 4. ATTACK PAKETİ
        val hotbarSlot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        PacketUtil.sendAttack(session, e.runtimeId, hotbarSlot, clickPos)

        OverlayLogger.d(TAG, "Attack #$attackCount CRIT: mode=${critMode.value}")
    }

    /**
     * ✅ KRİTİK EFFECT İNJEKSİYONU
     * 
     * MovePacket Crit: 2 paket (0.11→0) — en hızlı, en etkili
     * Vanilla Crit: 7 paket — daha tutarlı
     * Jump Crit: 4 paket alternating
     */
    private suspend fun injectCrit(s: com.oxclient.core.relay.OxRelaySession) {
        try {
            when (critMode.value) {
                CritMode.MovePacket -> {
                    // 🚀 EN HIZLI: 2 paket, 0.11f → 0
                    PacketUtil.sendMoveAtSelf(s, dyOffset = 0.11f, onGround = false)
                    PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = true)
                }
                
                CritMode.Vanilla -> {
                    // 7 paket gradual düşüş (0.42→0)
                    listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f).forEach { dy ->
                        PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = dy == 0f)
                    }
                }
                
                CritMode.Jump -> {
                    // 4 paket alternating
                    listOf(0.0625f, 0f, 0.0625f, 0f).forEach { dy ->
                        PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = dy == 0f)
                    }
                }
            }
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Crit injection error: ${e.message}", e)
        }
    }

    private fun shouldFail(): Boolean = failRate.value > 0f && Math.random() < failRate.value

    private companion object {
        const val TAG = "KillAura"
    }
}
