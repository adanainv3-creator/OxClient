package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
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
    description = "Güçlü rakibi çevreleyerek saldır"
), PacketEventBus.PacketListener {

    enum class MoveMode { Strafe, Aggressive, Behind, Random, PVP }

    private val moveMode         = enum ("Mode",              MoveMode.Aggressive)
    private val detectRange      = float("Detect Range",      500f, 10f,  500f)
    private val range            = float("Range",             2.5f, 1f,   8f)
    private val horizontalSpeed  = float("Horizontal Speed",  4f,   0.5f, 10f)
    private val verticalSpeed    = float("Vertical Speed",    1.5f, 0.1f, 5f)
    private val strafeSpeed      = float("Strafe Speed",      2.5f, 0.1f, 20f)
    private val yOffset          = float("Y Offset",          0.8f, -2f,   2f)
    private val pvpDepth         = float("PVP Depth",         2.5f, 2f,   3f)
    private val pvpRadius        = float("PVP Radius",        1.2f, 0.5f, 2.5f)
    private val pvpCrit          = bool ("PVP Crit",          true)
    private val attack           = bool ("Attack",            true)
    private val attackRange      = float("Attack Range",      4.2f, 1f,   6f)
    private val cpsMin           = int  ("CPS Min",           18,   1,    30)
    private val cpsMax           = int  ("CPS Max",           22,   1,    30)
    private val doubleAttack     = bool ("Double Attack",     true)
    private val shortcut         = bool ("Shortcut",          false)

    private var strafeAngle = 0.0
    private var moveAttempts = 0L
    private var attackCount  = 0L
    @Volatile private var lastAttackMs = 0L

    private companion object { const val TAG = "TPAura" }

    override fun onEnable() {
        super.onEnable()
        strafeAngle = Random.nextDouble(0.0, Math.PI * 2)
        moveAttempts = 0L
        attackCount = 0L
        PacketEventBus.register(this)
        OverlayLogger.d(TAG, "Enabled: mode=${moveMode.value} range=${range.value} attackRange=${attackRange.value} cps=${cpsMin.value}-${cpsMax.value} doubleAttack=${doubleAttack.value}")
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled (moves=$moveAttempts attacks=$attackCount)")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val target = findTarget() ?: return
        moveAroundTarget(target)
        if (attack.value) tryAttack(target)
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(detectRange.value)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
        val target = candidates.minByOrNull { EntityTracker.distanceTo(it) }
        if (target == null) {
            OverlayLogger.v(TAG, "findTarget: aday yok")
        } else {
            OverlayLogger.v(TAG, "findTarget: ${target.name.ifEmpty { target.runtimeId }} - ${"%.1f".format(EntityTracker.distanceTo(target))}m")
        }
        return target
    }

    private fun moveAroundTarget(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ

        // Y Offset ile head positioning
        val targetPos = Vector3f.from(target.x, target.y + yOffset.value, target.z)
        val dist = MathUtil.dist3(selfX, selfY, selfZ, target.x, target.y, target.z)

        val newPos = if (dist > range.value) {
            stepTowardTarget(selfX, selfY, selfZ, targetPos)
        } else {
            calculatePosition(selfX, selfZ, targetPos, target)
        }

        // ⚡ Rotation: direct aim (predictive aim RotationUtil'de yok)
        val rot = RotationUtil.toEntity(target)

        try {
            session.clientBound(MovePlayerPacket().apply {
                runtimeEntityId       = EntityTracker.selfRuntimeId
                position              = newPos
                rotation              = Vector3f.from(rot.pitch, rot.yaw, rot.yaw)
                mode                  = MovePlayerPacket.Mode.NORMAL
                isOnGround            = true
                ridingRuntimeEntityId = 0L
            })
            moveAttempts++
            if (moveAttempts % 15 == 0L) {
                OverlayLogger.d(TAG, "move: #$moveAttempts | dist=${"%.1f".format(dist)} | mode=${moveMode.value}")
            }
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Paket hatası: ${e.message}")
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

    private fun tryAttack(target: EntityTracker.TrackedEntity) {
        val dist = EntityTracker.distanceTo(target)
        if (dist > attackRange.value) return

        val now = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return
        lastAttackMs = now

        val session = PacketEventBus.currentSession ?: return
        attackCount++

        // PVP modunda hedefin altındayız — kritik şartı (fallDistance>0 && !onGround)
        // Criticals.injectMovePacket ile aynı kanıtlanmış teknikle sağlanıyor
        if (moveMode.value == MoveMode.PVP && pvpCrit.value) {
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0.11f, onGround = false)
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f,    onGround = false)
        }

        // ⚡ Double attack: swing + attack x2
        PacketUtil.sendSwing(session)
        PacketUtil.sendAttack(session, target.runtimeId)
        if (doubleAttack.value) {
            PacketUtil.sendAttack(session, target.runtimeId)
        }

        OverlayLogger.v(TAG, "attack: #$attackCount | ${target.name.ifEmpty { target.runtimeId }} | ${"%.1f".format(dist)}m")
    }

    private fun calculatePosition(selfX: Float, selfZ: Float, targetPos: Vector3f, target: EntityTracker.TrackedEntity): Vector3f {
        val radius = range.value

        return when (moveMode.value) {
            // ⚡ AGGRESSIVE: Tight circle, hızlı dönüş, hareket halindeki rakibe karşı
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

            // PVP: hedefin 2-3 blok altına geçip yukarı bakarak dar çemberde strafe —
            // her vuruş öncesi crit enjeksiyonuyla birleştirilir (tryAttack'te)
            MoveMode.PVP -> {
                strafeAngle += horizontalSpeed.value * strafeSpeed.value * 0.045

                Vector3f.from(
                    target.x + (cos(strafeAngle) * pvpRadius.value).toFloat(),
                    target.y - pvpDepth.value,
                    target.z + (sin(strafeAngle) * pvpRadius.value).toFloat()
                )
            }
        }
    }
}
