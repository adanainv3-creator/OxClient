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
    description = "Otomatik saldırı (MAX HASAR)"
), PacketEventBus.PacketListener {
    
    enum class AttackMode   { Single, Multi, Switch, Closest }
    enum class RotationMode { Lock, Approximate, Silent, None }
    enum class SwingMode    { Client, Server, Both, None }
    enum class PriorityMode { Distance, Health, Direction, LowestHealth }

    // ✅ [MAX DAMAGE] CPS 12-16 (hızlı ancak sunucu takip edebiliyor)
    private val cpsMin          = int  ("CPS Min",          12,   1,  30)
    private val cpsMax          = int  ("CPS Max",          16,   1,  30)
    private val range           = float("Range",            6.5f, 1f,  10f)  // 6 → 6.5
    private val fov             = int  ("FOV",              360,  30, 360)
    private val switchDelay     = int  ("Switch Delay",     0,    0,  500)
    private val maxTargets      = int  ("Max Targets",      5,    1,  10)    // 3 → 5
    private val attackMode      = enum ("Attack Mode",      AttackMode.Multi)
    private val rotationMode    = enum ("Rotation Mode",    RotationMode.Lock)
    private val swingMode       = enum ("Swing",            SwingMode.Both)
    private val priorityMode    = enum ("Priority",         PriorityMode.LowestHealth)
    private val reversePriority = bool ("Reverse Priority", false)
    private val failRate        = float("Fail Rate",        0.0f, 0f, 0.5f)
    private val headLock        = bool ("Head Lock",        true)
    private val headLockSmooth  = float("Head Lock Smooth", 0.9f, 0.01f, 1f)  // 0.8 → 0.9 (daha agresif)
    private val fakeCrit        = bool ("Fake Crit",        true)
    private val predictDelay    = float("Predict Delay",   0.1f, 0.05f, 0.5f) // lag kompanzasyonu
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
        OverlayLogger.d(TAG, "Enabled: MAXDAMAGE mode | CPS=${cpsMin.value}-${cpsMax.value} Range=${range.value}")
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
     * ✅ [MAX DAMAGE] Saldırı sequence optimize edildi:
     * 1. CRIT: Y offset paketleri (delay yok)
     * 2. ROTATION: hedefi hesapla
     * 3. SWING: harita animasyonu (sunucuya gözüksün)
     * 4. ATTACK: clickPos tahmin edilerek hesaplanmış
     */
    private suspend fun performAttackSequence(e: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        // ✅ 1. Hedefin gelecek konumunu tahmin et (lag kompanzasyonu)
        val predictionTime = predictDelay.value
        val predPos = e.predictedPosition(predictionTime)
        
        // Head'e doğru vur (1.62m height)
        val clickPos = Vector3f.from(predPos.first, (predPos.second + 1.62f).coerceIn(predPos.second - 0.5f, predPos.second + 2f), predPos.third)
        
        // Tahmin edilen pozisyona doğru rotation
        val targetRot = RotationUtil.toPoint(predPos.first, predPos.second + 1.62f, predPos.third)

        // ✅ 2. Kritik vuruş hareketleri (delay YOK - hızlı sequence)
        if (fakeCrit.value) {
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0.0625f, onGround = false)
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0.0f, onGround = false)
        }

        // ✅ 3. Rotasyonu güncelle (attack'tan hemen ÖNCE)
        if (rotationMode.value != RotationMode.None) {
            val rot = when (rotationMode.value) {
                RotationMode.Lock -> targetRot
                RotationMode.Approximate -> RotationUtil.approximate(targetRot)
                else -> targetRot
            }
            // ✅ onGround=true (sunucu hareket paketini dinlemesi için)
            PacketUtil.sendMoveAtSelf(session, rot.yaw, rot.pitch, onGround = true)
        }

        // ✅ 4. Saldırı animasyonu (ikisini de gönder)
        when (swingMode.value) {
            SwingMode.Server, SwingMode.Both -> PacketUtil.sendSwing(session)
            else -> {}
        }

        // ✅ 5. ATTACK PAKETİ — clickPos tahmin edilerek hesaplanmış
        // Hotbar slot 0-8 arası, selfHotbarSlot kullan
        val hotbarSlot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        PacketUtil.sendAttack(session, e.runtimeId, hotbarSlot, clickPos)

        OverlayLogger.v(TAG, "Attack #$attackCount: clickPos=$clickPos hotbar=$hotbarSlot")
    }

    private fun shouldFail(): Boolean = failRate.value > 0f && Math.random() < failRate.value

    private companion object {
        const val TAG = "KillAura"
    }
}
