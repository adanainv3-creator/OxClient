package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * LegitAura — KillAura/AimBot ile aynı temel altyapı (rotation-lock +
 * cancelAndReplace, CPS-gated attack loop), ama "legit görünme" öncelikli:
 *
 * - Reaction Delay: yeni bir hedef ilk göründüğünde, dönüşe HEMEN başlamak
 *   yerine [Reaction Min, Reaction Max] arası rastgele bir gecikme bekler
 *   (insan tepki süresi simülasyonu). Gecikme boyunca rotasyon olduğu gibi
 *   kalır, ışınlanmış gibi anında hedefe dönmez.
 * - Turn Smooth: headlock'taki gibi anlık kilitlenme değil, düşük bir
 *   smoothing faktörüyle YAVAŞÇA hedefe döner.
 * - Aim Tolerance: saldırı sadece rotasyon hedefe yeterince yakınsadığında
 *   (Aim Tolerance derece içinde) tetiklenir — hâlâ dönerken saldırmaz,
 *   gerçek bir oyuncu gibi önce bakar sonra vurur.
 * - Miss Rate: gerçek insanların bazen ıskalaması gibi, belirli bir
 *   olasılıkla saldırıyı hiç göndermez.
 * - Jitter: AimBot'taki "Humanize" ile aynı — hedefin tam merkezine değil,
 *   küçük rastgele bir sapmayla nişan alır.
 */
class LegitAura : BaseModule(
    name        = "LegitAura",
    category    = ModuleCategory.COMBAT,
    description = "İnsan gibi tepki süresi/dönüş/sapma ile 'legit' görünen otomatik saldırı"
), PacketEventBus.PacketListener {

    companion object {
        private const val TICK_INTERVAL_MS = 10L
        private const val SCAN_INTERVAL_MS = 50L
    }

    // --- Hedefleme ---
    private val range          = float("Range",            4.5f, 1f,   6f)
    private val fov             = int  ("FOV",              75,   10,   360)
    private val ignoreFriends   = bool ("Ignore Friends",   true)

    // --- İnsan davranışı simülasyonu ---
    private val reactionMinMs   = int  ("Reaction Min",     80,   0,   1000)
    private val reactionMaxMs   = int  ("Reaction Max",     220,  0,   1500)
    private val turnSmooth      = float("Turn Smooth",      0.18f, 0.02f, 1f)
    private val yawJitter       = float("Yaw Jitter",       1.2f, 0f,   8f)
    private val pitchJitter     = float("Pitch Jitter",     0.7f, 0f,   8f)
    private val aimTolerance    = float("Aim Tolerance",    6f,   1f,   45f)
    private val missRate        = float("Miss Rate",        0.06f, 0f,  0.5f)

    // --- Saldırı ---
    // CPS yerine doğrudan ms cinsinden saldırı gecikmesi — her saldırıdan sonra
    // [Attack Delay Min, Attack Delay Max] arasından rastgele bir gecikme
    // seçilir (sabit tek bir CPS değeri yerine, insan vuruş ritmindeki doğal
    // değişkenliği taklit eder).
    private val attackDelayMinMs = int  ("Attack Delay Min",  180,  20,  2000)
    private val attackDelayMaxMs = int  ("Attack Delay Max",  320,  20,  3000)
    private val shortcut        = bool ("Shortcut",         false)

    @Volatile private var curYaw = 0f
    @Volatile private var curPitch = 0f
    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastScanMs = 0L
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null
    // Bir sonraki saldırıya kadar beklenecek, rastgele seçilmiş ms gecikmesi.
    // Her saldırıdan hemen sonra yeniden rastgele seçilir (rollNextDelay()).
    @Volatile private var nextDelayMs = 0L

    // Reaction-delay durumu: yeni bir hedef kilitlendiğinde, bu zaman damgasına
    // kadar rotasyon hareket ETMEZ (insan "fark etme" gecikmesi).
    @Volatile private var trackingSince = 0L
    @Volatile private var reactionDeadlineMs = 0L
    @Volatile private var lastTargetId = 0L

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        curYaw = EntityTracker.selfYaw
        curPitch = EntityTracker.selfPitch
        lastAttackMs = 0L
        lastScanMs = 0L
        cachedTarget = null
        trackingSince = 0L
        reactionDeadlineMs = 0L
        lastTargetId = 0L
        nextDelayMs = rollNextDelay()
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        cachedTarget = null
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        applyLegitRotation(event, pkt)
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val now = System.currentTimeMillis()
        val cached = cachedTarget?.takeIf {
            EntityTracker.getById(it.runtimeId) != null &&
            EntityTracker.distanceTo(it) <= range.value
        }
        if (cached != null) return cached
        if (now - lastScanMs < SCAN_INTERVAL_MS && cachedTarget == null) return null
        lastScanMs = now

        val found = EntityTracker.getEntitiesInRange(range.value)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { RotationUtil.fovCheck(it, fov.value.toFloat()) }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .minByOrNull { EntityTracker.distanceTo(it) }

        cachedTarget = found
        return found
    }

    private fun applyLegitRotation(event: PacketEvent, pkt: PlayerAuthInputPacket) {
        val target = findTarget() ?: return
        val now = System.currentTimeMillis()

        // Yeni hedef -> reaction-delay penceresini başlat, dönüşü henüz uygulama.
        if (target.runtimeId != lastTargetId) {
            lastTargetId = target.runtimeId
            trackingSince = now
            reactionDeadlineMs = now + MathUtil.randomInt(reactionMinMs.value, reactionMaxMs.value.coerceAtLeast(reactionMinMs.value))
        }
        if (now < reactionDeadlineMs) return // hâlâ "fark etmedik", rotasyona dokunma

        var targetRot = RotationUtil.toEntity(target)
        targetRot = RotationUtil.approximate(targetRot, yawJitter.value, pitchJitter.value)

        curYaw = smoothYaw(curYaw, targetRot.yaw, turnSmooth.value)
        curPitch = smoothPitch(curPitch, targetRot.pitch, turnSmooth.value)

        pkt.rotation = Vector3f.from(curPitch, curYaw, curYaw)
        EntityTracker.selfYaw = curYaw
        EntityTracker.selfPitch = curPitch

        // KRİTİK: cancelAndReplace çağrılmazsa relay ham wire byte'larını
        // gönderiyor, bu mutation server'a hiç ulaşmıyor (bkz. KillAura.kt).
        event.cancelAndReplace(pkt)
    }

    private fun tick() {
        val target = cachedTarget?.let { EntityTracker.getById(it.runtimeId) } ?: return
        if (EntityTracker.distanceTo(target) > range.value) return

        val now = System.currentTimeMillis()
        if (now < reactionDeadlineMs) return // reaction-delay bitmeden asla saldırma

        if (now - lastAttackMs < nextDelayMs) return

        // Sadece nişan gerçekten hedefe yeterince yakınsamışsa vur — hâlâ
        // dönerken saldırmak "legit" görünümü bozar.
        val realRot = RotationUtil.toEntity(target)
        val aimedEnough = RotationUtil.angleDiff(curYaw, realRot.yaw) <= aimTolerance.value &&
            kotlin.math.abs(curPitch - realRot.pitch) <= aimTolerance.value
        if (!aimedEnough) return

        lastAttackMs = now
        nextDelayMs = rollNextDelay()
        if (missRate.value > 0f && Math.random() < missRate.value) return // "ıskaladı"

        val session = PacketEventBus.currentSession ?: return
        scope.launch { attack(session, target.runtimeId) }
    }

    private fun rollNextDelay(): Long {
        val lo = attackDelayMinMs.value
        val hi = attackDelayMaxMs.value.coerceAtLeast(lo)
        return MathUtil.randomInt(lo, hi).toLong()
    }

    private suspend fun attack(session: RubidiumRelaySession, targetRid: Long) {
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, targetRid, EntityTracker.selfHotbarSlot)
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
}
