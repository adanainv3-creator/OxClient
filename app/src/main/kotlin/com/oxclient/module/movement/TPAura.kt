package com.oxclient.module.movement

import android.util.Log
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.module.social.isFriendEntity
import com.oxclient.utils.MathUtil
import com.oxclient.utils.RotationUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Rakip etrafında hareket eder"
), PacketEventBus.PacketListener {

    enum class MoveMode { Random, Strafe, Behind, Aggressive }

    private val moveMode         = enum ("Mode",              MoveMode.Aggressive)
    private val detectRange      = float("Detect Range",      100f, 10f,  500f)
    private val range            = float("Range",             1.52f, 1f,   8f)
    private val horizontalSpeed  = float("Horizontal Speed",  3.8f, 0.5f, 8f)
    private val verticalSpeed    = float("Vertical Speed",    1.8f, 0.1f, 8f)
    private val strafeSpeed      = float("Strafe Speed",      1.5f, 0.1f, 50f)
    private val yOffset          = float("Y Offset",          0.8f, -2f,  2f)
    private val rotateToTarget   = bool ("Rotate To Target",  true)
    private val ignoreFriends    = bool ("Ignore Friends",    true)
    private val shortcut         = bool ("Shortcut",          false)

    private var strafeAngle = 0.0
    private var moveAttempts = 0L
    @Volatile private var lastTargetId = 0L

    companion object {
        private const val TAG = "TPAura"
    }

    override fun onEnable() {
        super.onEnable()
        strafeAngle = Random.nextDouble(0.0, Math.PI * 2)
        moveAttempts = 0L
        lastTargetId = 0L
        PacketEventBus.register(this)
        Log.d(TAG, "onEnable: registered, listening for PlayerAuthInputPacket")
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
        Log.d(TAG, "onDisable: unregistered, total moveAttempts=$moveAttempts")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val target = findTarget()
        if (target == null) {
            Log.d(TAG, "onPacket: no target found in range ${detectRange.value}")
            return
        }
        moveAroundTarget(target)
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(detectRange.value)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }

        val target = candidates.minByOrNull { EntityTracker.distanceTo(it) }
        if (target != null && target.runtimeId != lastTargetId) {
            lastTargetId = target.runtimeId
            Log.d(TAG, "findTarget: new target=${target.runtimeId} dist=${EntityTracker.distanceTo(target)}")
        }
        return target
    }

    private fun moveAroundTarget(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession
        if (session == null) {
            Log.w(TAG, "moveAroundTarget: currentSession is NULL, cannot send packet")
            return
        }

        val selfX   = EntityTracker.selfX
        val selfY   = EntityTracker.selfY
        val selfZ   = EntityTracker.selfZ

        val targetPos = Vector3f.from(target.x, target.y + yOffset.value, target.z)
        val dist      = MathUtil.dist3(selfX, selfY, selfZ, target.x, target.y, target.z)

        val newPos = if (dist > range.value) {
            stepTowardTarget(selfX, selfY, selfZ, targetPos)
        } else {
            calculatePosition(selfX, selfZ, targetPos)
        }

        val rot = if (rotateToTarget.value) RotationUtil.toEntity(target) else null

        try {
            val movePacket = MovePlayerPacket().apply {
                runtimeEntityId       = EntityTracker.selfRuntimeId
                position              = newPos
                rotation              = if (rot != null) Vector3f.from(rot.pitch, rot.yaw, rot.yaw)
                                        else Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, 0f)
                mode                  = MovePlayerPacket.Mode.NORMAL
                isOnGround            = true
                ridingRuntimeEntityId = 0L
            }

            // KRİTİK FIX: eskiden sadece clientBound gönderiliyordu, yani bu pozisyon
            // SADECE senin ekranına yansıyordu — sunucu senin hâlâ eski yerde olduğunu
            // sanıyordu. Bu yüzden saldırı paketleri sunucu tarafında mesafe kontrolüne
            // takılıp reddediliyordu ve hiç hasar geçmiyordu. Artık serverBound da
            // gönderiyoruz ki sunucu gerçekten yakınında olduğunu bilsin.
            session.serverBound(movePacket)
            session.clientBound(movePacket)


            EntityTracker.selfX = newPos.x
            EntityTracker.selfY = newPos.y
            EntityTracker.selfZ = newPos.z
            if (rot != null) {
                EntityTracker.selfYaw   = rot.yaw
                EntityTracker.selfPitch = rot.pitch
            }

            moveAttempts++
            if (moveAttempts % 20 == 0L) {
                Log.d(TAG, "moveAroundTarget: sent $moveAttempts packets, last pos=$newPos dist=$dist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "moveAroundTarget: FAILED to send packet - ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun stepTowardTarget(selfX: Float, selfY: Float, selfZ: Float, targetPos: Vector3f): Vector3f {
        val direction = atan2(
            (targetPos.z - selfZ).toDouble(),
            (targetPos.x - selfX).toDouble()
        ) - Math.toRadians(90.0)

        val newX = selfX - (sin(direction) * horizontalSpeed.value).toFloat()
        val newZ = selfZ + (cos(direction) * horizontalSpeed.value).toFloat()
        val newY = targetPos.y.coerceIn(
            selfY - verticalSpeed.value,
            selfY + verticalSpeed.value
        )
        return Vector3f.from(newX, newY, newZ)
    }

    private fun calculatePosition(selfX: Float, selfZ: Float, targetPos: Vector3f): Vector3f {
        val radius = range.value

        return when (moveMode.value) {
            // ⚡ AGGRESSIVE: Tight circle, hızlı dönüş, en iyi PvP
            MoveMode.Aggressive -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.05
                val verticalWave = sin(strafeAngle * 0.7f).toFloat() * 0.25f * verticalSpeed.value

                Vector3f.from(
                    targetPos.x + (cos(strafeAngle) * radius).toFloat(),
                    targetPos.y + verticalWave,
                    targetPos.z + (sin(strafeAngle) * radius).toFloat()
                )
            }

            // Klasik strafe: daha geniş radius, dengeli hareket
            MoveMode.Strafe -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.03
                val verticalWave = sin(strafeAngle * 0.5f).toFloat() * 0.3f * verticalSpeed.value

                Vector3f.from(
                    targetPos.x + (cos(strafeAngle) * radius * 1.3f).toFloat(),
                    targetPos.y + verticalWave,
                    targetPos.z + (sin(strafeAngle) * radius * 1.3f).toFloat()
                )
            }

            // Behind: Rakinin arkasına konumlan, defensive strat
            MoveMode.Behind -> {
                val dx = targetPos.x - selfX
                val dz = targetPos.z - selfZ
                val angle = atan2(dz.toDouble(), dx.toDouble()) + Math.PI
                val behindRadius = radius * 1.2f

                Vector3f.from(
                    targetPos.x + (cos(angle) * behindRadius).toFloat(),
                    targetPos.y,
                    targetPos.z + (sin(angle) * behindRadius).toFloat()
                )
            }

            // Random: Unpredictable, chaos atak
            MoveMode.Random -> {
                val angle            = Random.nextDouble(0.0, Math.PI * 2)
                val horizontalOffset = radius * (0.7f + Random.nextFloat() * 0.3f)
                val verticalOffset   = (Random.nextFloat() - 0.5f) * 0.4f * verticalSpeed.value

                Vector3f.from(
                    targetPos.x + (cos(angle) * horizontalOffset).toFloat(),
                    targetPos.y + verticalOffset,
                    targetPos.z + (sin(angle) * horizontalOffset).toFloat()
                )
            }
        }
    }
}
