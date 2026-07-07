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
) {
    enum class AttackMode   { Single, Multi, Switch, Closest }
    enum class RotationMode { Lock, Approximate, Silent, None }
    enum class SwingMode    { Client, Server, Both, None }
    enum class PriorityMode { Distance, Health, Direction, LowestHealth }

    private val cpsMin          = int  ("CPS Min",          20,   1,  30)
    private val cpsMax          = int  ("CPS Max",          25,   1,  30)
    private val range           = float("Range",            6.0f, 1f,  10f)
    private val fov             = int  ("FOV",              360,  30, 360)
    private val switchDelay     = int  ("Switch Delay",     0,    0,  500)
    private val maxTargets      = int  ("Max Targets",      5,    1,  10)
    private val attackMode      = enum ("Attack Mode",      AttackMode.Multi)
    private val rotationMode    = enum ("Rotation Mode",    RotationMode.Lock)
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
        
        OverlayLogger.d(TAG, "Enabled: CPS=${cpsMin.value}-${cpsMax.value} Range=${range.value} FOV=${fov.value}")
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

    // ✅ FIX: Kritik simülasyonu için eklenen 60ms+50ms gecikmeler artık tick()
    // döngüsünü BLOKLAMIYOR — bu fonksiyon anında dönüyor, gerçek paket gönderimi
    // arka planda ayrı bir coroutine'de yürüyor. Eskiden doAttack suspend olup
    // tick() içinde awaitlendiği için her saldırı ~110ms tick döngüsünü durduruyordu,
    // bu da CPS ayarını (20-25) fiilen ~9 CPS'e düşürüyordu — "cps artmıyor" şikayetinin
    // sebebi buydu.
    private fun doAttack(e: EntityTracker.TrackedEntity, now: Long) {
        scope.launch { performAttackSequence(e) }
    }

    private suspend fun performAttackSequence(e: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return
        
        OverlayLogger.v(TAG, "Attack #${attackCount}: dist=${"%.2f".format(EntityTracker.distanceTo(e))}")

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

        // Fake-fall move paketleri ile attack paketi arasında gerçek tick payı
        // bırakıyoruz ki sunucu Y pozisyon farkından "düşüyor" durumunu gerçekten
        // hesaplayabilsin. Bu artık ayrı bir coroutine'de olduğu için ana CPS
        // döngüsünü etkilemiyor.
        injectCrit(session)
        kotlinx.coroutines.delay(60L)

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
        if (now - lastCritMs < 5) return
        lastCritMs = now

        // Küçük bir zıplama + düşüş hareketi: iki paket arasına gerçek bir tick
        // (~50ms) koyuyoruz ki sunucu Y pozisyon farkından "düşüyor" durumunu
        // gerçekten hesaplayabilsin. Aralıksız gönderilen iki paket sunucu için
        // aynı tick'te işlenip hiçbir fark üretmiyordu.
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0.0625f, onGround = false)
        kotlinx.coroutines.delay(50L)
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0.042f, onGround = false)
    }

    private fun shouldFail(): Boolean = failRate.value > 0f && Math.random() < failRate.value
}