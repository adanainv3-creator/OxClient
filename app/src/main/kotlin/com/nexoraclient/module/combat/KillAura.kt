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

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Otomatik saldırı"
), PacketEventBus.PacketListener {

    enum class AttackMode   { Single, Multi, Closest }
    enum class PriorityMode { Distance, Health, LowestHealth, Direction }
    enum class CritMode     { InputFlag, MovePacket, None }

    private val cpsMin          = int  ("CPS Min",          18,    1,  30)
    private val cpsMax          = int  ("CPS Max",          20,    1,  30)
    private val range           = float("Range",            10f,   1f, 18f)
    private val fov             = int  ("FOV",              360,   30, 360)
    private val maxTargets      = int  ("Max Targets",       3,    1,  10)
    private val attackMode      = enum ("Attack Mode",      AttackMode.Multi)
    private val priorityMode    = enum ("Priority",         PriorityMode.LowestHealth)
    private val reversePriority = bool ("Reverse Priority", false)
    private val critMode        = enum ("Crit Mode",        CritMode.InputFlag)
    private val headLock        = bool ("Head Lock",        true)
    private val headLockSmooth  = float("Head Lock Smooth", 0.8f, 0.01f, 1f)
    private val ignoreFriends   = bool ("Ignore Friends",   true)
    private val antiBot         = bool ("Anti Bot",         true)
    private val shortcut        = bool ("Shortcut",         false)

    // Tick döngüsünden set, onPacket'ten okunur
    @Volatile private var pendingAttack  = false
    @Volatile private var lastAttackMs   = 0L
    @Volatile private var lastScanMs     = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    @Volatile private var headLockYaw    = 0f
    @Volatile private var headLockPitch  = 0f
    @Volatile private var critPending    = false

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        pendingAttack = false
        lastAttackMs  = 0L
        lastScanMs    = 0L
        cachedTargets = emptyList()
        headLockYaw   = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        critPending   = false
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

        val currentYaw   = pkt.rotation.y
        val currentPitch = pkt.rotation.x

        val targets = cachedTargets
        val primary = targets.firstOrNull()

        // Head lock rotasyonu
        if (headLock.value && primary != null) {
            val rot = RotationUtil.toEntity(primary)
            val f   = headLockSmooth.value
            headLockYaw   = smoothYaw(headLockYaw,   rot.yaw,   f)
            headLockPitch = smoothPitch(headLockPitch, rot.pitch, f)
            pkt.rotation  = Vector3f.from(headLockPitch, headLockYaw, headLockYaw)
            EntityTracker.selfYaw   = headLockYaw
            EntityTracker.selfPitch = headLockPitch
        }

        // Crit: InputFlag modu — MovePlayerPacket göndermek yerine
        // mevcut PlayerAuthInputPacket'e JUMPING flag enjekte ediliyor.
        // Sunucu bu paketi normal input olarak işler, Y koordinatı değişmez,
        // rubber band olmaz.
        if (critPending && critMode.value == CritMode.InputFlag && primary != null) {
            pkt.inputData.add(PlayerAuthInputData.JUMPING)
            critPending = false
        }

        if (!pendingAttack || primary == null) {
            event.cancelAndReplace(pkt)
            return
        }

        pendingAttack = false
        val session = event.session

        // MovePacket crit — sadece bu modda sendMoveAtSelf çağrılır
        // ama bunu da PlayerAuthInputPacket ile sync tutmak için
        // aynı Y değerini kullanıyoruz (dyOffset = 0, onGround kontrolü)
        if (critMode.value == CritMode.MovePacket && EntityTracker.selfOnGround) {
            scope.launch {
                PacketUtil.sendMoveAtSelf(session, dyOffset = 0.42f, onGround = false)
                delay(8L)
                // landing attack'tan SONRA gelecek
            }
        }

        val slot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)

        when (attackMode.value) {
            AttackMode.Multi -> {
                targets.take(maxTargets.value).forEach { t ->
                    val click = Vector3f.from(t.x, t.y + 1.5f, t.z)
                    PacketUtil.sendSwing(session)
                    PacketUtil.sendAttack(session, t.runtimeId, slot, click)
                }
            }
            else -> {
                val click = Vector3f.from(primary.x, primary.y + 1.5f, primary.z)
                PacketUtil.sendSwing(session)
                PacketUtil.sendAttack(session, primary.runtimeId, slot, click)
            }
        }

        if (critMode.value == CritMode.MovePacket) {
            scope.launch {
                delay(12L)
                PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)
            }
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
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        if (now - lastScanMs >= 50L) {
            cachedTargets = selectTargets()
            lastScanMs    = now
        }

        if (cachedTargets.isEmpty()) return

        lastAttackMs  = now
        critPending   = critMode.value == CritMode.InputFlag && EntityTracker.selfOnGround
        pendingAttack = true
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        val raw = EntityTracker.getEntitiesInRange(range.value)
        val result = ArrayList<EntityTracker.TrackedEntity>(raw.size)
        for (e in raw) {
            if (!e.isPlayer || e.runtimeId == EntityTracker.selfRuntimeId) continue
            if (fov.value < 360 && EntityTracker.angleToEntity(e) > fov.value / 2f) continue
            if (ignoreFriends.value && e.isFriendEntity) continue
            if (antiBot.value && e.isLikelyBot()) continue
            result.add(e)
        }
        result.sortWith { a, b ->
            when (priorityMode.value) {
                PriorityMode.Distance     -> MathUtil.dist3sq(a.x, a.y, a.z, sx, sy, sz).compareTo(MathUtil.dist3sq(b.x, b.y, b.z, sx, sy, sz))
                PriorityMode.Health,
                PriorityMode.LowestHealth -> a.health.compareTo(b.health)
                PriorityMode.Direction    -> EntityTracker.angleToEntity(a).compareTo(EntityTracker.angleToEntity(b))
            }.let { if (reversePriority.value) -it else it }
        }
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
