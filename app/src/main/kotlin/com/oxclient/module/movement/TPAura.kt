package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import kotlinx.coroutines.*

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Hedefe ışınlanarak saldırır"
) {
    enum class TpMode    { Random, Strafe, Behind, Speed, Orbit }
    enum class ReturnMode{ Instant, Delayed, None }

    private val mode            = enum ("Mode",             TpMode.Strafe)
    private val returnMode      = enum ("Return Mode",      ReturnMode.Delayed)
    private val range           = float("Range",            1.5f,  0.5f,  6f)
    private val yOffset         = float("Y Offset",         0f,   -2f,    2f)
    private val horizontalSpeed = float("Horizontal Speed", 6.11f, 0.1f, 20f)
    private val verticalSpeed   = float("Vertical Speed",   4f,    0.1f, 10f)
    private val strafeSpeed     = float("Strafe Speed",     20f,   1f,   50f)
    private val attackCooldown  = int  ("Attack Cooldown",  200,   50,  1000)
    private val attacksPerTp    = int  ("Attacks Per TP",   1,     1,    5)
    private val rotate          = bool ("Rotate",           true)
    private val shortcut        = bool ("Shortcut",         true)

    @Volatile private var tpInProgress = false
    @Volatile private var lastAttackMs = 0L
    private var strafeAngle = 0.0
    private var orbitAngle  = 0.0
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        tpInProgress = false
        strafeAngle  = 0.0
        orbitAngle   = 0.0
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        tpInProgress = false
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (isEnabled && !tpInProgress && now - lastAttackMs >= attackCooldown.value) {
                val target = EntityTracker.getEntitiesInRange(range.value)
                    .minByOrNull { EntityTracker.distanceTo(it) }
                if (target != null) tpAttack(target, now)
            }
            delay(50L)
        }
    }

    private suspend fun tpAttack(target: EntityTracker.TrackedEntity, now: Long) {
        tpInProgress = true
        lastAttackMs = now
        val session = PacketEventBus.currentSession ?: run { tpInProgress = false; return }

        val origX = EntityTracker.selfX
        val origY = EntityTracker.selfY
        val origZ = EntityTracker.selfZ

        val (tpX, tpY, tpZ) = calcPosition(target)

        PacketUtil.sendMove(session, tpX, tpY + verticalSpeed.value * 0.1f, tpZ,
            EntityTracker.selfYaw, EntityTracker.selfPitch, onGround = true, teleport = true)
        delay(30L)

        if (rotate.value) {
            val r = RotationUtil.toEntity(target)
            PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        }

        repeat(attacksPerTp.value) {
            PacketUtil.sendSwingAndAttack(session, target.runtimeId)
            if (attacksPerTp.value > 1) delay(50L)
        }

        delay(50L)

        when (returnMode.value) {
            ReturnMode.Instant -> {
                PacketUtil.sendMove(session, origX, origY, origZ,
                    EntityTracker.selfYaw, EntityTracker.selfPitch, onGround = true, teleport = true)
            }
            ReturnMode.Delayed -> {
                delay(100L)
                PacketUtil.sendMove(session, origX, origY, origZ,
                    EntityTracker.selfYaw, EntityTracker.selfPitch, onGround = true, teleport = true)
            }
            ReturnMode.None -> {}
        }

        tpInProgress = false
    }

    private fun calcPosition(t: EntityTracker.TrackedEntity): Triple<Float, Float, Float> {
        val tx = t.x
        val ty = t.y + yOffset.value
        val tz = t.z
        val hs = horizontalSpeed.value * 0.1f
        return when (mode.value) {
            TpMode.Behind -> {
                val dx = tx - EntityTracker.selfX
                val dz = tz - EntityTracker.selfZ
                val dist = MathUtil.dist2(tx, tz, EntityTracker.selfX, EntityTracker.selfZ).coerceAtLeast(0.001f)
                Triple(tx + (dx / dist) * hs, ty, tz + (dz / dist) * hs)
            }
            TpMode.Random -> {
                val a = Math.random() * Math.PI * 2
                Triple(
                    (tx + Math.cos(a) * hs).toFloat(),
                    ty,
                    (tz + Math.sin(a) * hs).toFloat()
                )
            }
            TpMode.Strafe -> {
                strafeAngle += 0.3 * (strafeSpeed.value / 20f)
                Triple(
                    (tx + Math.cos(strafeAngle) * hs).toFloat(),
                    ty,
                    (tz + Math.sin(strafeAngle) * hs).toFloat()
                )
            }
            TpMode.Speed -> {
                val dx = tx - EntityTracker.selfX
                val dz = tz - EntityTracker.selfZ
                val dist = MathUtil.dist2(tx, tz, EntityTracker.selfX, EntityTracker.selfZ)
                val spd = horizontalSpeed.value * 0.5f
                val ratio = if (dist > spd) (dist - spd) / dist else 0f
                Triple(EntityTracker.selfX + dx * ratio, ty, EntityTracker.selfZ + dz * ratio)
            }
            TpMode.Orbit -> {
                orbitAngle += 0.15 * (strafeSpeed.value / 20f)
                val orbitRadius = range.value * 0.9f
                Triple(
                    (tx + Math.cos(orbitAngle) * orbitRadius).toFloat(),
                    ty,
                    (tz + Math.sin(orbitAngle) * orbitRadius).toFloat()
                )
            }
        }
    }
}
