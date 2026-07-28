package com.nexoraclient.module.movement

import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.core.relay.NexoraRelaySession
import com.nexoraclient.events.PacketEvent
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.*
import com.nexoraclient.module.social.isFriendEntity
import com.nexoraclient.utils.MathUtil
import com.nexoraclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OrbitLock — TPAura'nın teleport (MovePlayerPacket.Mode.TELEPORT) tabanlı
 * pozisyon spoofing'i yerine, Jetpack'in kullandığı SetEntityMotionPacket ile
 * GERÇEK fiziksel itiş uygulayarak hedefin etrafında döner. Pozisyon
 * doğrudan yazılmıyor, sadece motion veriliyor — sunucu fizik simülasyonunu
 * kendisi yürütüyor.
 *
 * - Lock Range içine giren en yakın oyuncuya otomatik kilitlenir (Auto Engage).
 * - Rotation Lock: KillAura'daki headlock ile birebir aynı mekanizma —
 *   giden PlayerAuthInputPacket'in rotation alanı SYNC olarak (aynı onPacket
 *   çağrısı içinde, event.packet üzerinde) değiştiriliyor. Bu paket zaten
 *   gönderilmeden önce mutate edildiği için async dispatch sorunlarından
 *   etkilenmiyor.
 * - Orbit: hedefin etrafında Orbit Radius yarıçapında teğetsel (tangential)
 *   hareket + yarıçap/yükseklik düzeltmesi, motion vektörü olarak gönderilir.
 * - Rotation Lock ve Orbit birbirinden bağımsız açılıp kapatılabilir.
 */
class OrbitLock : BaseModule(
    name        = "OrbitLock",
    category    = ModuleCategory.MOVEMENT,
    description = "Belirli mesafeye giren hedefe kilitlenir, jetpack motion'ıyla etrafında döner (teleport yok)"
), PacketEventBus.PacketListener {

    private val lockRange       = float("Lock Range",       10f,  1f,   30f)
    private val autoEngage      = bool ("Auto Engage",      true)
    private val rotationLock    = bool ("Rotation Lock",    true)
    private val rotationSmooth  = float("Rotation Smooth",  1f,   0.01f, 1f)
    private val orbitEnabled    = bool ("Orbit",             true)
    private val orbitRadius     = float("Orbit Radius",      2.5f, 1f,   8f)
    private val orbitSpeed      = float("Orbit Speed",       3f,   0.1f, 10f)
    private val thrustPower     = float("Thrust Power",      1.2f, 0.1f, 3.0f)
    private val matchHeight     = bool ("Match Height",      true)
    private val ignoreFriends   = bool ("Ignore Friends",    true)
    private val shortcut        = bool ("Shortcut",          false)

    @Volatile private var orbitAngle    = 0.0
    @Volatile private var lockYaw       = 0f
    @Volatile private var lockPitch     = 0f
    @Volatile private var cachedTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var lastScanMs    = 0L

    private val SCAN_INTERVAL_MS = 50L

    override fun onEnable() {
        super.onEnable()
        orbitAngle    = 0.0
        lockYaw       = EntityTracker.selfYaw
        lockPitch     = EntityTracker.selfPitch
        cachedTarget  = null
        lastScanMs    = 0L
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

        if (rotationLock.value) applyRotationLock(event, pkt, target)
        if (orbitEnabled.value) applyOrbitMotion(target)
    }

    private fun getTarget(): EntityTracker.TrackedEntity? {
        val now = System.currentTimeMillis()
        val cached = cachedTarget?.takeIf {
            EntityTracker.getById(it.runtimeId) != null &&
            EntityTracker.distanceTo(it) <= lockRange.value
        }
        if (cached != null) return cached
        if (!autoEngage.value) {
            cachedTarget = null
            return null
        }
        if (now - lastScanMs < SCAN_INTERVAL_MS && cachedTarget == null) return null
        lastScanMs = now

        val found = EntityTracker.getEntitiesInRange(lockRange.value)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .minByOrNull { EntityTracker.distanceTo(it) }

        cachedTarget = found
        return found
    }

    private fun applyRotationLock(event: PacketEvent, pkt: PlayerAuthInputPacket, target: EntityTracker.TrackedEntity) {
        val targetRot = RotationUtil.toEntity(target)
        val factor = rotationSmooth.value.coerceIn(0.01f, 1f)

        lockYaw   = smoothYaw(lockYaw, targetRot.yaw, factor)
        lockPitch = smoothPitch(lockPitch, targetRot.pitch, factor)

        pkt.rotation = Vector3f.from(lockPitch, lockYaw, lockYaw)
        EntityTracker.selfYaw   = lockYaw
        EntityTracker.selfPitch = lockPitch

        // NexoraRelaySession raw wire byte passthrough kullanıyor; cancelAndReplace
        // çağrılmazsa bu mutation server'a hiç gitmez (bkz. KillAura.kt fix).
        event.cancelAndReplace(pkt)
    }

    private fun applyOrbitMotion(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        orbitAngle += orbitSpeed.value * 0.05

        // hedefin etrafında olmak istediğimiz nokta
        val desiredX = target.x + (cos(orbitAngle) * orbitRadius.value).toFloat()
        val desiredZ = target.z + (sin(orbitAngle) * orbitRadius.value).toFloat()
        val desiredY = if (matchHeight.value) target.y else selfY

        var dirX = desiredX - selfX
        var dirY = desiredY - selfY
        var dirZ = desiredZ - selfZ

        val mag = sqrt((dirX * dirX + dirY * dirY + dirZ * dirZ).toDouble()).toFloat()
        if (mag < 0.0001f) return

        dirX /= mag
        dirY /= mag
        dirZ /= mag

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(
                dirX * thrustPower.value,
                dirY * thrustPower.value,
                dirZ * thrustPower.value
            )
        }
        session.clientBound(motionPacket)
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
