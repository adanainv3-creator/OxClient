package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.CritLock
import com.rubidiumclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlinx.coroutines.*

class KillAuraPro : BaseModule(
    name        = "KillAuraPro",
    category    = ModuleCategory.COMBAT,
    description = "Silent, burst-attack, garantili kritik — profesyonel KillAura varyantı"
), PacketEventBus.PacketListener {

    private val cps            = int  ("CPS",              20,    1,   50)
    private val range          = float("Range",            10f,  1f,  18f)
    private val maxTargets     = int  ("Max Targets",      3,    1,   15)
    private val predictDelay   = float("Predict Delay",   0.05f, 0.05f, 0.5f)
    private val ignoreFriends  = bool ("Ignore Friends",   true)
    private val alwaysCrit     = bool ("Always Crit",      true)
    private val headLock       = bool ("Head Lock",        true)
    private val headLockSmooth = float("Head Lock Smooth", 1f,  0.01f, 1f)
    private val shortcut       = bool ("Shortcut",         false)
    private val antiBot        = bool ("Anti Bot",         true)

    @Volatile private var lastAttackMs        = 0L
    @Volatile private var headLockYaw         = 0f
    @Volatile private var headLockPitch       = 0f
    @Volatile private var cachedHeadLockTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var lastHeadLockScanMs  = 0L
    @Volatile private var lastScanMs          = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    private var tickJob: Job? = null

    companion object {
        private const val HEAD_LOCK_SCAN_INTERVAL_MS = 50L
        private const val TICK_INTERVAL_MS            = 15L
        private const val SCAN_INTERVAL_MS            = 50L
    }

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        cachedHeadLockTarget = null
        lastHeadLockScanMs = 0L; lastScanMs = 0L
        cachedTargets = emptyList()
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
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        applyHeadLock(event, pkt)
    }

    private fun applyHeadLock(event: PacketEvent, pkt: PlayerAuthInputPacket) {
        val now    = System.currentTimeMillis()
        val cached = cachedHeadLockTarget?.takeIf { EntityTracker.getById(it.runtimeId) != null }
        if (cached == null || now - lastHeadLockScanMs >= HEAD_LOCK_SCAN_INTERVAL_MS) {
            cachedHeadLockTarget = findHeadLockTarget()
            lastHeadLockScanMs   = now
        }
        val target       = cachedHeadLockTarget ?: return
        val targetRot    = RotationUtil.toEntity(target)
        val smoothFactor = headLockSmooth.value.coerceIn(0.01f, 1f)
        val newYaw       = smoothYaw(headLockYaw, targetRot.yaw, smoothFactor)
        val newPitch     = smoothPitch(headLockPitch, targetRot.pitch, smoothFactor)

        headLockYaw = newYaw; headLockPitch = newPitch
        pkt.rotation = Vector3f.from(newPitch, newYaw, newYaw)
        EntityTracker.selfYaw   = newYaw
        EntityTracker.selfPitch = newPitch
        event.cancelAndReplace(pkt)
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

    private fun smoothPitch(current: Float, target: Float, factor: Float): Float =
        (current + (target - current) * factor).coerceIn(-90f, 90f)

    private fun findHeadLockTarget(): EntityTracker.TrackedEntity? =
        EntityTracker.getEntitiesInRange(range.value * 1.5f)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .let { if (antiBot.value)       it.filterNot { e -> e.isLikelyBot()   } else it }
            .minByOrNull { it.health }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val now     = System.currentTimeMillis()
        if (now - lastAttackMs < 1000L / cps.value) return

        if (now - lastScanMs >= SCAN_INTERVAL_MS) {
            cachedTargets = selectTargets()
            lastScanMs    = now
        }
        val targets = cachedTargets
        if (targets.isEmpty()) return

        lastAttackMs = now
        targets.take(maxTargets.value).forEach { target ->
            scope.launch { burstAttack(session, target) }
        }
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> =
        EntityTracker.getEntitiesInRange(range.value)
            .asSequence()
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .let { if (antiBot.value)       it.filterNot { e -> e.isLikelyBot()   } else it }
            .sortedBy { it.health }
            .toList()

    private fun EntityTracker.TrackedEntity.isLikelyBot(): Boolean =
        name.isBlank() || uniqueId == 0L

    private suspend fun burstAttack(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        // ① Crit enjeksiyonu: 0.42f yukarı → 10ms → 0f düşüyor
        //    Landing burada YOK — attack'tan sonra gönderilecek.
        var didCrit = false
        if (alwaysCrit.value) {
            CritLock.tryRun("KillAuraPro") {
                didCrit = injectCritTimed(session)
            }
        }

        val predPos  = target.predictedPosition(predictDelay.value)
        val clickPos = Vector3f.from(predPos.first, predPos.second + 1.5f, predPos.third)

        // ② Swing + saldırı — hâlâ havadayken
        PacketUtil.sendSwing(session)
        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        PacketUtil.sendAttack(session, target.runtimeId, slot, clickPos)
        PacketUtil.sendAttack(session, target.runtimeId, slot, clickPos)

        // ③ İniş — attack'tan SONRA (crit register etmesi için kritik sıralama)
        if (didCrit) {
            delay(5L)
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)
        }
    }

    private suspend fun injectCritTimed(s: RubidiumRelaySession): Boolean =
        try {
            PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
            delay(10L)
            PacketUtil.sendMoveAtSelf(s, dyOffset = 0f, onGround = false)
            true
        } catch (_: Exception) { false }
}
