
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
    
    // ✅ YENİ: Head lock ayarları
    private val headLock        = bool ("Head Lock",        true)
    private val headLockSmooth  = float("Head Lock Smooth", 0.2f, 0.01f, 1f)  // 0-1 arası interpolation
    
    private val shortcut        = bool ("Shortcut",         false)

    private companion object { 
        const val TAG = "KillAura" 
    }

    @Volatile private var currentTargetId   = 0L
    @Volatile private var lastSwitchMs      = 0L
    @Volatile private var lastAttackMs      = 0L
    @Volatile private var lastRotationSendMs = 0L
    @Volatile private var consecutiveMisses = 0
    @Volatile private var attackCount       = 0L
    
    // ✅ Head lock state
    @Volatile private var headLockYaw       = 0f
    @Volatile private var headLockPitch     = 0f
    
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        currentTargetId = 0L
        consecutiveMisses = 0
        attackCount = 0L
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        
        PacketEventBus.register(this)
        OverlayLogger.d(TAG, "Enabled: CPS=${cpsMin.value}-${cpsMax.value} Range=${range.value} FOV=${fov.value} HeadLock=${headLock.value}")
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled (attacks=$attackCount)")
    }

    /**
     * ✅ PacketListener — her PlayerAuthInputPacket'te headeyi güncelle
     * Bu sayede baş sırasında hareket paketini takip eder
     */
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

    /**
     * ✅ Baş kilidi güncelle — hedefi takip et
     * Smooth interpolation ile sudden turn'ü avoid et
     */
    private fun updateHeadLock() {
        val session = PacketEventBus.currentSession ?: return
        val target = findHeadLockTarget() ?: return

        val now = System.currentTimeMillis()
        if (now - lastRotationSendMs < 50L) return // Her 50ms'de bir (client 20 tick/s)
        lastRotationSendMs = now

        val targetRot = RotationUtil.toEntity(target)
        
        // ✅ Smooth interpolation — headLockSmooth oranında hedefe doğru yaklaş
        val smoothFactor = headLockSmooth.value.coerceIn(0.01f, 1f)
        
        val newYaw = smoothYaw(headLockYaw, targetRot.yaw, smoothFactor)
        val newPitch = smoothPitch(headLockPitch, targetRot.pitch, smoothFactor)

        headLockYaw = newYaw
        headLockPitch = newPitch

        try {
            PacketUtil.sendMoveAtSelf(session, newYaw, newPitch, onGround = true)
            OverlayLogger.v(TAG, "HeadLock: yaw=${"%.1f".format(newYaw)} pitch=${"%.1f".format(newPitch)} target=${target.name}")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "HeadLock packet error: ${e.message}", e)
        }
    }

    /**
     * ✅ Yaw smoothing — açısal fark en kısayı seç
     * 0°-360° arasında doğru interpolation
     */
    private fun smoothYaw(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        
        // 180°'den fazla fark varsa diğer yolu seç
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        
        val result = current + (diff * factor)
        
        // Normalize to -180..180
        var normalized = result % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        
        return normalized
    }

    /**
     * ✅ Pitch smoothing — -90 ile 90 arasında
     */
    private fun smoothPitch(current: Float, target: Float, factor: Float): Float {
        val diff = target - current
        return (current + (diff * factor)).coerceIn(-90f, 90f)
    }

    private fun findHeadLockTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(range.value * 1.5f)  // Biraz daha geniş range
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
        
        return selectTarget(candidates)
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

    private fun doAttack(e: EntityTracker.TrackedEntity, now: Long) {
        scope.launch { performAttackSequence(e) }
    }

    private suspend fun performAttackSequence(e: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return
        
        OverlayLogger.v(TAG, "Attack #${attackCount}: dist=${"%.2f".format(EntityTracker.distanceTo(e))}")

        // ✅ Rotation: hedefte bakmaya zaten kilitlendik (HeadLock) ama explicit attack için de gönder
        if (rotationMode.value != RotationMode.None) {
            val r = RotationUtil.toEntity(e)
            when (rotationMode.value) {
                RotationMode.Lock -> PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
                RotationMode.Approximate -> {
                    val approx = RotationUtil.approximate(r)
                    PacketUtil.sendMoveAtSelf(session, approx.yaw, approx.pitch)
                }
                RotationMode.Silent -> {}
                RotationMode.None -> {}
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
