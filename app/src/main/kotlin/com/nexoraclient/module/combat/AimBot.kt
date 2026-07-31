package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * AimBot — FOV konisi içindeki en yakın (imleçe en yakın açılı) hedefe kilitlenir
 * ve giden PlayerAuthInputPacket'in rotation alanını SYNC olarak değiştirir
 * (OrbitLock'taki rotation-lock mekanizmasıyla birebir aynı yöntem). Orbit/Jetpack
 * motion kısmı yok — sadece rotasyon.
 */
class AimBot : BaseModule(
    name        = "AimBot",
    category    = ModuleCategory.COMBAT,
    description = "FOV icindeki en yakin hedefe otomatik nisan alir"
), PacketEventBus.PacketListener {

    private val fov            = float("FOV",            90f,  10f,  360f)
    private val range          = float("Range",           8f,   1f,   30f)
    private val smooth         = float("Smooth",           0.35f, 0.01f, 1f)
    private val humanize       = bool ("Humanize",         true)
    private val yawJitter      = float("Yaw Jitter",       1.5f, 0f,   10f)
    private val pitchJitter    = float("Pitch Jitter",     0.8f, 0f,   10f)
    private val autoEngage     = bool ("Auto Engage",      true)
    private val ignoreFriends  = bool ("Ignore Friends",   true)
    private val shortcut       = bool ("Shortcut",         false)

    @Volatile private var lockYaw       = 0f
    @Volatile private var lockPitch     = 0f
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var lastScanMs    = 0L

    private val SCAN_INTERVAL_MS = 50L

    override fun onEnable() {
        super.onEnable()
        lockYaw      = EntityTracker.selfYaw
        lockPitch    = EntityTracker.selfPitch
        cachedTarget = null
        lastScanMs   = 0L
        PacketEventBus.register(this)
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        cachedTarget = null
        super.onDisable()
    }

    // NOT: Bu handler'ın SENKRON çalıştığından emin ol (PacketEventBus'ta
    // PlayerAuthInputPacket için async dispatch açıksa rotation mutation'ı
    // paket gönderildikten sonra çalışır ve hiç etkisi olmaz).
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        val target = getTarget() ?: return
        applyRotationLock(event, pkt, target)
    }

    private fun getTarget(): EntityTracker.TrackedEntity? {
        val now = System.currentTimeMillis()
        val cached = cachedTarget?.takeIf {
            EntityTracker.getById(it.runtimeId) != null &&
            EntityTracker.distanceTo(it) <= range.value &&
            RotationUtil.fovCheck(it, fov.value)
        }
        if (cached != null) return cached
        if (!autoEngage.value) {
            cachedTarget = null
            return null
        }
        if (now - lastScanMs < SCAN_INTERVAL_MS && cachedTarget == null) return null
        lastScanMs = now

        // FOV konisi icindeki hedefler arasindan imlece en yakin acida olani sec
        // (sadece en yakin mesafedeki degil — gercek aimbot davranisi).
        val found = EntityTracker.getEntitiesInRange(range.value)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { RotationUtil.fovCheck(it, fov.value) }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .minByOrNull { RotationUtil.angleDiff(RotationUtil.toEntity(it).yaw, EntityTracker.selfYaw) }

        cachedTarget = found
        return found
    }

    private fun applyRotationLock(event: PacketEvent, pkt: PlayerAuthInputPacket, target: EntityTracker.TrackedEntity) {
        var targetRot = RotationUtil.toEntity(target)
        if (humanize.value) {
            targetRot = RotationUtil.approximate(targetRot, yawJitter.value, pitchJitter.value)
        }
        val factor = smooth.value.coerceIn(0.01f, 1f)

        lockYaw   = smoothYaw(lockYaw, targetRot.yaw, factor)
        lockPitch = smoothPitch(lockPitch, targetRot.pitch, factor)

        pkt.rotation = Vector3f.from(lockPitch, lockYaw, lockYaw)
        EntityTracker.selfYaw   = lockYaw
        EntityTracker.selfPitch = lockPitch

        // RubidiumRelaySession raw wire byte passthrough kullaniyor; cancelAndReplace
        // cagrilmazsa bu mutation server'a hic gitmez (bkz. OrbitLock.kt / KillAura.kt).
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

    private fun smoothPitch(current: Float, target: Float, factor: Float): Float {
        val diff = target - current
        return (current + (diff * factor)).coerceIn(-90f, 90f)
    }
}
