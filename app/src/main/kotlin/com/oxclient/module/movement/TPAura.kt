package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Uzaktaki oyuncuyu tespit edip yanına ışınlanır ve saldırır"
) {
    enum class MoveMode { Random, Strafe, Behind }

    private val moveMode         = enum ("Mode",              MoveMode.Strafe)
    private val detectRange      = float("Detect Range",      100f, 10f,  100f)
    private val range            = float("Range",             3f,   1f,   8f)
    private val horizontalSpeed  = float("Horizontal Speed",  2f,   0.5f, 10f)
    private val verticalSpeed    = float("Vertical Speed",    1f,   0.1f, 5f)
    private val yOffset          = float("Y Offset",          0f,  -2f,   2f)
    private val attack           = bool ("Attack",            true)
    private val attackRange      = float("Attack Range",      3.5f, 1f,   6f)
    private val cpsMin           = int  ("CPS Min",           10,   1,    30)
    private val cpsMax           = int  ("CPS Max",           15,   1,    30)
    private val shortcut         = bool ("Shortcut",          false)

    @Volatile private var isMoving = false
    private var strafeAngle = 0.0
    private var tickJob: Job? = null
    private var moveAttempts = 0L
    private var attackCount  = 0L
    private var tickCounter  = 0L
    @Volatile private var lastAttackMs = 0L

    // Relay'e gönderilen son pozisyonu takip eder.
    // serverBound() pipeline'dan geçmediği için EntityTracker güncellenmiyor —
    // bir sonraki tick'te selfX/Y/Z hâlâ eski değerde kalıp stepTowardTarget()
    // aynı koordinatı tekrar hesaplıyordu. localPos bunu çözer.
    @Volatile private var localX = 0f
    @Volatile private var localY = 0f
    @Volatile private var localZ = 0f
    @Volatile private var localInitialized = false

    private companion object { const val TAG = "TPAura" }

    override fun onEnable() {
        super.onEnable()
        isMoving = false
        strafeAngle = Random.nextDouble(0.0, Math.PI * 2)
        moveAttempts = 0L
        attackCount = 0L
        localInitialized = false
        OverlayLogger.d(TAG, "Enabled: mode=${moveMode.value} detectRange=${detectRange.value} hSpeed=${horizontalSpeed.value} vSpeed=${verticalSpeed.value} attack=${attack.value}")
        tickJob = scope.launch { movementLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        isMoving = false
        localInitialized = false
        OverlayLogger.d(TAG, "Disabled (moveAttempts=$moveAttempts attackCount=$attackCount)")
    }

    private suspend fun movementLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                val target = findTarget()
                if (target != null) {
                    moveAroundTarget(target)
                    if (attack.value) tryAttack(target)
                }
            }
            delay(50L)
        }
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(detectRange.value)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
        val target = candidates.minByOrNull { EntityTracker.distanceTo(it) }
        if (target == null) {
            OverlayLogger.v(TAG, "findTarget: aday yok (detectRange=${detectRange.value} entityCount=${EntityTracker.count()})")
        } else {
            OverlayLogger.v(TAG, "findTarget: hedef=${target.name.ifEmpty { target.runtimeId.toString() }} dist=${"%.2f".format(EntityTracker.distanceTo(target))}")
        }
        return target
    }

    private fun moveAroundTarget(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        if (!localInitialized) {
            localX = EntityTracker.selfX
            localY = EntityTracker.selfY
            localZ = EntityTracker.selfZ
            localInitialized = true
        }

        val targetPos = Vector3f.from(target.x, target.y + yOffset.value, target.z)
        val dist = MathUtil.dist3(localX, localY, localZ, target.x, target.y, target.z)

        val newPos = if (dist > range.value) {
            stepTowardTarget(targetPos)
        } else {
            calculatePosition(targetPos)
        }

        val rot = RotationUtil.toEntity(target)

        val packet = PlayerAuthInputPacket().apply {
            position = newPos
            rotation = Vector3f.from(rot.pitch, rot.yaw, 0f)
            motion = Vector2f.ZERO
            tick = tickCounter++
        }

        try {
            session.serverBound(packet)
            // serverBound() doğrudan iletim yapar, EntityTracker.selfX/Y/Z güncellenmez.
            // localX/Y/Z'yi elle güncelliyoruz ki bir sonraki tick doğru pozisyondan hesaplansın.
            localX = newPos.x
            localY = newPos.y
            localZ = newPos.z
            moveAttempts++
            if (moveAttempts % 20 == 0L) {
                OverlayLogger.d(TAG, "moveAroundTarget: gönderildi #$moveAttempts dist=${"%.1f".format(dist)} newPos=$newPos target=${target.name}")
            }
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Paket gönderme hatası: ${e.message}", e)
        }
    }

    private fun stepTowardTarget(targetPos: Vector3f): Vector3f {
        val direction = atan2(
            (targetPos.z - localZ).toDouble(),
            (targetPos.x - localX).toDouble()
        ) - Math.toRadians(90.0)

        val newX = localX - (sin(direction) * horizontalSpeed.value).toFloat()
        val newZ = localZ + (cos(direction) * horizontalSpeed.value).toFloat()
        val newY = targetPos.y.coerceIn(
            localY - verticalSpeed.value,
            localY + verticalSpeed.value
        )
        return Vector3f.from(newX, newY, newZ)
    }

    private fun tryAttack(target: EntityTracker.TrackedEntity) {
        val dist = MathUtil.dist3(localX, localY, localZ, target.x, target.y, target.z)
        if (dist > attackRange.value) return

        val now = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return
        lastAttackMs = now

        val session = PacketEventBus.currentSession ?: return
        attackCount++
        PacketUtil.sendSwingAndAttack(session, target.runtimeId)
        OverlayLogger.v(TAG, "tryAttack #$attackCount: hedef=${target.name.ifEmpty { target.runtimeId.toString() }} dist=${"%.2f".format(dist)}")
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
                val dx = targetPos.x - localX
                val dz = targetPos.z - localZ
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
}
