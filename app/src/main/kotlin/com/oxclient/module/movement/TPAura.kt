package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * TPAuraOrbit — hedef etrafında Random/Strafe/Behind modlarıyla dolanır.
 *
 * ✅ DÜZELTMELER (eskisi MovePlayerPacket mode=TELEPORT kullanıyordu — 2b2tpe gibi
 * anti-cheat sunucularda tamamen ignored):
 *  - MovePlayerPacket → PlayerAuthInputPacket (sunucu tarafından güvenilir sayılır)
 *  - Mode.TELEPORT → PlayerAuthInputData.JUMPING flag + normal motion
 *  - Detaylı debug logging eklendi: target bulundu, position/rotation hesaplaması, paket
 *    gönderme başarısı vb. — modül neden "çalışmıyor" görünüyor diye anlamaya yardım eder.
 */
class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Rakip etrafında hareket eder"
) {
    enum class MoveMode { Random, Strafe, Behind }

    private val moveMode         = enum ("Mode",              MoveMode.Strafe)
    private val range            = float("Range",             3f,   1f,   8f)
    private val horizontalSpeed  = float("Horizontal Speed",  2f,   0.5f, 10f)
    private val verticalSpeed    = float("Vertical Speed",    1f,   0.1f, 5f)
    private val yOffset          = float("Y Offset",          0f,  -2f,   2f)
    private val shortcut         = bool ("Shortcut",          false)

    @Volatile private var isMoving = false
    private var strafeAngle = 0.0
    private var tickJob: Job? = null
    private var moveAttempts = 0L
    private var tickCounter = 0L

    private companion object { const val TAG = "TPAura" }

    override fun onEnable() {
        super.onEnable()
        isMoving = false
        strafeAngle = Random.nextDouble(0.0, Math.PI * 2)
        moveAttempts = 0L
        OverlayLogger.d(TAG, "Enabled: mode=${moveMode.value} hSpeed=${horizontalSpeed.value} vSpeed=${verticalSpeed.value}")
        tickJob = scope.launch { movementLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        isMoving = false
        OverlayLogger.d(TAG, "Disabled (moveAttempts=$moveAttempts)")
    }

    private suspend fun movementLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                val target = findTarget()
                if (target != null) {
                    moveAttempts++
                    moveAroundTarget(target)
                }
            }
            delay(50L)
        }
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(range.value + 1f)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
        return candidates.minByOrNull { EntityTracker.distanceTo(it) }
    }

    // ✅ FIX: OpFightBot referansındaki gibi HER TICK'te doğrudan hesaplanan orbit
    // pozisyonuna set ediyoruz — eskisi gibi "motion" vektörünü elle ölçekleyip
    // PlayerAuthInputPacket.motion alanına yazmaya çalışmıyoruz. motion sadece
    // sunucunun tahmin/animasyon amacıyla kullandığı bir rapor alanıdır, hareketin
    // kendisini SAĞLAMAZ; asıl hareketi belirleyen `position` alanıdır. Eskiden
    // motion'ı manuel ölçekleyip position'ı da aynı anda göndermek (ikisi tutarsız)
    // sunucunun paketi reddetmesine ya da hareketi yok saymasına yol açıyordu.
    private fun moveAroundTarget(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        val targetPos = Vector3f.from(target.x, target.y + yOffset.value, target.z)
        val newPos    = calculatePosition(targetPos)

        val packet = PlayerAuthInputPacket().apply {
            position = newPos
            rotation = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, 0f)
            motion = Vector2f.ZERO
            tick = tickCounter++
        }

        try {
            session.serverBound(packet)
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Paket gönderme hatası: ${e.message}", e)
        }
    }

    private fun calculatePosition(targetPos: Vector3f): Vector3f {
        val radius = range.value

        return when (moveMode.value) {
            MoveMode.Random -> {
                val angle            = Random.nextDouble(0.0, Math.PI * 2)
                val horizontalOffset = radius * (0.5f + Random.nextFloat() * 0.5f)
                val verticalOffset   = (Random.nextFloat() - 0.5f) * 0.5f * verticalSpeed.value

                Vector3f.from(
                    targetPos.x + (cos(angle) * horizontalOffset).toFloat(),
                    targetPos.y + verticalOffset,
                    targetPos.z + (sin(angle) * horizontalOffset).toFloat()
                )
            }

            MoveMode.Strafe -> {
                strafeAngle += horizontalSpeed.value * 0.03
                val verticalWave = sin(strafeAngle * 0.5f).toFloat() * 0.3f * verticalSpeed.value

                Vector3f.from(
                    targetPos.x + (cos(strafeAngle) * radius).toFloat(),
                    targetPos.y + verticalWave,
                    targetPos.z + (sin(strafeAngle) * radius).toFloat()
                )
            }

            MoveMode.Behind -> {
                val selfPos = getCurrentPosition()
                val dx = targetPos.x - selfPos.x
                val dz = targetPos.z - selfPos.z
                val angle = atan2(dz.toDouble(), dx.toDouble()) + Math.PI
                val behindRadius = radius * 0.7f

                Vector3f.from(
                    targetPos.x + (cos(angle) * behindRadius).toFloat(),
                    targetPos.y,
                    targetPos.z + (sin(angle) * behindRadius).toFloat()
                )
            }
        }
    }

    private fun getCurrentPosition(): Vector3f =
        Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
}
