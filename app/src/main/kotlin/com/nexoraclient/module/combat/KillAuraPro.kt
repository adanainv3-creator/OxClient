package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class KillAuraPro : BaseModule(
    name        = "KillAuraPro",
    category    = ModuleCategory.COMBAT,
    description = "Silent burst-attack, garantili kritik"
), PacketEventBus.PacketListener {

    private val cps            = int  ("CPS",              20,    1,   50)
    private val range          = float("Range",            10f,   1f,  18f)
    private val maxTargets     = int  ("Max Targets",       3,    1,   15)
    private val ignoreFriends  = bool ("Ignore Friends",   true)
    private val alwaysCrit     = bool ("Always Crit",      true)
    private val headLock       = bool ("Head Lock",        true)
    private val headLockSmooth = float("Head Lock Smooth", 0.8f, 0.01f, 1f)
    private val antiBot        = bool ("Anti Bot",         true)
    private val shortcut       = bool ("Shortcut",         false)

    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastScanMs     = 0L
    @Volatile private var pendingAttack  = false
    @Volatile private var critPending    = false
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs  = 0L
        lastScanMs    = 0L
        pendingAttack = false
        critPending   = false
        cachedTargets = emptyList()
        headLockYaw   = EntityTracker.selfYaw
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
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val targets = cachedTargets
        val primary = targets.firstOrNull()

        if (headLock.value && primary != null) {
            val rot = RotationUtil.toEntity(primary)
            val f   = headLockSmooth.value
            headLockYaw   = smoothYaw(headLockYaw,   rot.yaw,   f)
            headLockPitch = smoothPitch(headLockPitch, rot.pitch, f)
            pkt.rotation  = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            EntityTracker.selfYaw   = headLockYaw
            EntityTracker.selfPitch = headLockPitch
        }

        // Crit: JUMPING flag enjeksiyonu — hiç MovePlayerPacket gönderilmiyor
        if (critPending && alwaysCrit.value && primary != null) {
            pkt.inputData.add(PlayerAuthInputData.JUMPING)
            critPending = false
        }

        if (!pendingAttack || primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        pendingAttack = false
        val session = event.session
        val slot    = EntityTracker.selfHotbarSlot.coerceIn(0, 8)

        targets.take(maxTargets.value).forEach { t ->
            val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
            PacketUtil.sendSwing(session)
            PacketUtil.sendAttack(session, t.runtimeId, slot, click)
        }

        event.cancelAndReplace(pkt)
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(5L)
        }
    }

    private fun tick() {
        val now     = System.currentTimeMillis()
        val delayMs = (1000L / cps.value.coerceAtLeast(1))
        if (now - lastAttackMs < delayMs) return

        if (now - lastScanMs >= 50L) {
            cachedTargets = selectTargets()
            lastScanMs    = now
        }

        if (cachedTargets.isEmpty()) return

        lastAttackMs  = now
        critPending   = alwaysCrit.value && EntityTracker.selfOnGround
        pendingAttack = true
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        val raw = EntityTracker.getEntitiesInRange(range.value)
        val result = ArrayList<EntityTracker.TrackedEntity>(raw.size)
        for (e in raw) {
            if (!e.isPlayer || e.runtimeId == EntityTracker.selfRuntimeId) continue
            if (ignoreFriends.value && e.isFriendEntity) continue
            if (antiBot.value && e.isLikelyBot()) continue
            result.add(e)
        }
        result.sortBy { it.health }
        return result
    }

    private fun EntityTracker.TrackedEntity.isLikelyBot() = name.isBlank() || uniqueId == 0L

    private fun smoothYaw(cur: Float, tgt: Float, f: Float): Float {
        var d = tgt - cur
        if (d > 180f) d -= 360f; if (d < -180f) d += 360f
        var r = (cur + d * f) % 360f
        if (r > 180f) r -= 360f; if (r < -180f) r += 360f
        return r
    }

    private fun smoothPitch(cur: Float, tgt: Float, f: Float) =
        (cur + (tgt - cur) * f).coerceIn(-90f, 90f)
}
