package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * TPAuraOrbit — hedef etrafında Random/Strafe/Behind modlarıyla dolanır.
 *
 * DÜZELTMELER (önceki bozuk sürüme göre):
 *  - `by enumValue(...)/floatValue(...)` BaseModule'de yok, `by` delegate
 *    desteklenmiyor (EnumSetting/FloatSetting getValue/setValue implement
 *    etmiyor) -> gerçek API'ye çevrildi: enum(...), float(...) (min,max ayrı param).
 *  - `EntityTracker.selfId` yok -> `EntityTracker.selfRuntimeId`.
 *  - Reflection ile `sendPacket` arama tamamen kaldırıldı (metod adı zaten
 *    yanlıştı, her zaman NoSuchMethodException atıp paketi sessizce yutuyordu).
 *    Bunun yerine projenin geri kalanıyla tutarlı şekilde `session.serverBound(...)`
 *    direkt kullanılıyor.
 *  - `it.name != null` anlamsızdı (name zaten non-null String) -> kaldırıldı.
 *
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

    private companion object { const val TAG = "TPAura" }

    override fun onEnable() {
        super.onEnable()
        isMoving = false
        strafeAngle = Random.nextDouble(0.0, Math.PI * 2)
        OverlayLogger.d(TAG, "Enabled: mode=${moveMode.value} hSpeed=${horizontalSpeed.value} vSpeed=${verticalSpeed.value}")
        tickJob = scope.launch { movementLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        isMoving = false
        OverlayLogger.d(TAG, "Disabled")
    }

    private suspend fun movementLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled && !isMoving) {
                val target = findTarget()
                if (target != null) {
                    isMoving = true
                    try {
                        moveAroundTarget(target)
                    } catch (e: Exception) {
                        OverlayLogger.e(TAG, "moveAroundTarget hatası: ${e.message}", e)
                    } finally {
                        isMoving = false
                    }
                }
            }
            delay(50L)
        }
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        return EntityTracker.getEntitiesInRange(range.value + 1f)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .minByOrNull { EntityTracker.distanceTo(it) }
    }

    private suspend fun moveAroundTarget(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "moveAroundTarget: session null — relay bağlı değil")
            return
        }

        val selfPos   = getCurrentPosition()
        val targetPos = Vector3f.from(target.x, target.y + yOffset.value, target.z)
        val newPos    = calculatePosition(targetPos)

        val motionX = (newPos.x - selfPos.x) * horizontalSpeed.value * 0.1f
        val motionY = (newPos.y - selfPos.y) * verticalSpeed.value * 0.1f
        val motionZ = (newPos.z - selfPos.z) * horizontalSpeed.value * 0.1f

        session.serverBound(SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion          = Vector3f.from(motionX, motionY, motionZ)
        })

        delay(20L)

        session.serverBound(MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = newPos
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = MovePlayerPacket.Mode.TELEPORT
            isOnGround            = false
            ridingRuntimeEntityId = 0L
        })

        val avgSpeed = (horizontalSpeed.value + verticalSpeed.value) / 2f
        val delayMs  = (100f / avgSpeed.coerceAtLeast(0.5f)).toLong()
        delay(delayMs)
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
