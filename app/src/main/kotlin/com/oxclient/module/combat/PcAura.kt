package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.module.social.isFriendEntity
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.CritLock
import com.oxclient.utils.InventoryUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import kotlinx.coroutines.*

class PcAura : BaseModule(
    name        = "OxAura",
    category    = ModuleCategory.COMBAT,
    description = "PC kalitesinde ultra güçlü otomatik saldırı"
), PacketEventBus.PacketListener {

    private val cpsMin        = int  ("CPS Min",         28,   1,   50)
    private val cpsMax        = int  ("CPS Max",         32,   1,   50)
    private val range         = float("Range",           10f,  1f,  20f)
    private val fov           = int  ("FOV",             360,  30,  360)
    private val maxTargets    = int  ("Max Targets",     5,    1,   15)
    private val predictDelay  = float("Predict Delay",   0.15f, 0.05f, 0.5f)
    private val ignoreFriends = bool ("Ignore Friends",  true)
    private val autoWeapon    = bool ("Auto Weapon",     true)

    enum class TimerMode { STATIC, STUTTER, RAMP }
    private val timerMode     = enum ("Timer Mode",      TimerMode.STATIC)
    private val timerSpeed    = float("Timer Speed",     30f,   20f,  60f)
    private val timerMin      = float("Timer Min",       24f,   20f,  50f)
    private val timerMax      = float("Timer Max",       40f,   20f,  60f)
    private val timerStep     = float("Timer Step",      1f,    0.1f, 5f)
    private val stutterFreq   = float("Stutter Freq",    0.5f,  0f,   5f)
    private val stutterValue  = float("Stutter Value",   10f,   0f,   20f)

    private val alwaysCrit    = bool ("Always Crit",     true)
    private val silentRot     = bool ("Silent Rotation", true)
    private val ignoreHurt    = bool ("Ignore HurtTime", true)
    private val mobAura       = bool ("Mob Aura",        false)

    enum class PriorityMode { DISTANCE, HEALTH, LOWEST_HEALTH, DIRECTION }
    private val priorityMode  = enum ("Priority",        PriorityMode.DISTANCE)
    private val reversePri    = bool ("Reverse Priority", false)
    private val shortcut      = bool ("Shortcut",        true)

    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastRotSendMs = 0L
    @Volatile private var headLockYaw = 0f
    @Volatile private var headLockPitch = 0f

    @Volatile private var currentTimer = 20f
    @Volatile private var timerIncreasing = true

    private var tickJob: Job? = null
    private var timerJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        lastRotSendMs = 0L
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        currentTimer = timerMin.value
        timerIncreasing = true

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

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled || !silentRot.value) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val pkt = event.packet as? org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket ?: return
        val target = selectTargets().firstOrNull()
        if (target != null) {
            applySilentRotation(pkt, target)
        }
    }

    private suspend fun timerLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                applyTimer()
            } else {
                resetTimer()
            }
            delay(16L)
        }
    }

    private fun applyTimer() {
        val session = PacketEventBus.currentSession ?: return

        when (timerMode.value) {
            TimerMode.STATIC -> {
                setTimer(session, timerSpeed.value)
            }
            TimerMode.STUTTER -> {
                val base = timerSpeed.value
                val stutter = if (stutterFreq.value > 0 && Math.random() < stutterFreq.value / 20f) {
                    (base - stutterValue.value).coerceIn(timerMin.value, timerMax.value)
                } else {
                    base
                }
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
        try {
            val session = PacketEventBus.currentSession ?: return
            setTimer(session, 20f)
        } catch (_: Exception) { }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(1L)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)

        if (now - lastAttackMs < delayMs) return

        val targets = selectTargets()
        if (targets.isEmpty()) return

        lastAttackMs = now
        val session = PacketEventBus.currentSession ?: return

        if (autoWeapon.value) {
            selectBestWeapon(session)
        }

        val targetsToHit = targets.take(maxTargets.value)

        targetsToHit.forEach { target ->
            scope.launch {
                performAttack(session, target)
            }
        }

        if (!silentRot.value && targets.isNotEmpty()) {
            val target = targets.first()
            val rot = RotationUtil.toEntity(target)
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

        if (!ignoreHurt.value && entity.hurtTime > 0) return false

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

    private fun selectBestWeapon(session: OxRelaySession) {
        try {
            var bestSlot = EntityTracker.selfHotbarSlot
            var bestDamage = 0f

            for (slot in 0..8) {
                val item = EntityTracker.getInventoryItem(slot) ?: continue
                val damage = getWeaponDamage(item)
                if (damage > bestDamage) {
                    bestDamage = damage
                    bestSlot = slot
                }
            }

            if (bestSlot != EntityTracker.selfHotbarSlot) {
                InventoryUtil.sendHotbarSelect(session, bestSlot)
            }
        } catch (_: Exception) { }
    }

    private fun getWeaponDamage(item: ItemData): Float {
        val identifier = runCatching { item.definition?.identifier }.getOrElse { null } ?: return 1f

        return when {
            identifier.contains("netherite_sword") -> 8f
            identifier.contains("diamond_sword") -> 7f
            identifier.contains("iron_sword") -> 6f
            identifier.contains("stone_sword") -> 5f
            identifier.contains("wooden_sword") -> 4f
            identifier.contains("netherite_axe") -> 7f
            identifier.contains("diamond_axe") -> 6f
            identifier.contains("iron_axe") -> 5f
            identifier.contains("stone_axe") -> 4f
            identifier.contains("wooden_axe") -> 3f
            identifier.contains("trident") -> 6f
            else -> 1f
        }
    }

    private suspend fun performAttack(session: OxRelaySession, target: EntityTracker.TrackedEntity) {
        if (alwaysCrit.value) {
            CritLock.tryRun { injectCritical(session) }
        }

        val predPos = target.predictedPosition(predictDelay.value)

        val clickPos = Vector3f.from(
            predPos.first,
            predPos.second + 1.62f,
            predPos.third
        )

        val hotbarSlot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)

        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)

        repeat(2) {
            PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)
        }
    }

    private suspend fun injectCritical(session: OxRelaySession) {
        try {
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0.42f, onGround = false)
            delay(8L)

            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = false)
            delay(5L)

            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)

        } catch (_: Exception) { }
    }

    private fun applySilentRotation(
        pkt: org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket,
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