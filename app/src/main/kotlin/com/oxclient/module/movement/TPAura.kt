package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketListener
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Hedefe ışınlanarak saldırır"
), PacketListener {

    override val priority = 85

    enum class TpMode { Random, Strafe, Behind, Speed }

    private val mode            = EnumSetting("Mode",            TpMode.Strafe, TpMode.entries)
    private val range           = FloatSetting("Range",          1.5f,  0.5f, 6f)
    private val yOffset         = FloatSetting("Y Offset",       0f,   -2f,   2f)
    private val passive         = BoolSetting("Passive",         false)
    private val horizontalSpeed = FloatSetting("HorizontalSpeed",6.11f, 0.1f, 20f)
    private val verticalSpeed   = FloatSetting("VerticalSpeed",  4f,    0.1f, 10f)
    private val strafeSpeed     = FloatSetting("StrafeSpeed",    20f,   1f,   50f)
    private val shortcut        = BoolSetting("Shortcut",        true)

    override fun registerSettings() = listOf(
        mode, range, yOffset, passive,
        horizontalSpeed, verticalSpeed, strafeSpeed, shortcut
    )

    @Volatile private var tpInProgress = false
    @Volatile private var lastAttackMs = 0L
    private var strafeAngle            = 0.0

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob : Job? = null

    override fun onEnable() {
        PacketEventBus.register(this)
        tpInProgress = false; strafeAngle = 0.0
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        tpInProgress = false
    }

    override fun onPacket(event: PacketEvent) { /* EntityTracker hallediyor */ }

    private suspend fun tickLoop() {
        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            if (isEnabled && !tpInProgress && now - lastAttackMs >= 200L) {
                val target = EntityTracker.getEntitiesInRange(range.value)
                    .minByOrNull { EntityTracker.distanceTo(it) }
                if (target != null) tpAttack(target, now)
            }
            delay(50L)
        }
    }

    private suspend fun tpAttack(target: EntityTracker.TrackedEntity, now: Long) {
        tpInProgress = true; lastAttackMs = now

        val origX = EntityTracker.selfX
        val origY = EntityTracker.selfY
        val origZ = EntityTracker.selfZ

        val (tpX, tpY, tpZ) = calcPosition(target)

        // 1. Işınlan
        PacketHelper.injectToServer(
            PacketHelper.buildMovePlayer(
                EntityTracker.selfRuntimeId,
                tpX, tpY + verticalSpeed.value * 0.1f, tpZ,
                EntityTracker.selfYaw, EntityTracker.selfPitch, EntityTracker.selfYaw,
                onGround = true, teleport = true
            )
        )
        delay(30L)

        // 2. Swing + Saldırı
        PacketHelper.injectToServer(PacketHelper.buildAnimate(EntityTracker.selfRuntimeId))
        PacketHelper.injectToServer(
            PacketHelper.buildAttack(target.runtimeId, EntityTracker.selfRuntimeId)
        )
        delay(50L)

        // 3. Geri dön
        PacketHelper.injectToServer(
            PacketHelper.buildMovePlayer(
                EntityTracker.selfRuntimeId,
                origX, origY, origZ,
                EntityTracker.selfYaw, EntityTracker.selfPitch, EntityTracker.selfYaw,
                onGround = true, teleport = true
            )
        )
        tpInProgress = false
    }

    private fun calcPosition(t: EntityTracker.TrackedEntity): Triple<Float, Float, Float> {
        val tx = t.x; val ty = t.y + yOffset.value; val tz = t.z
        return when (mode.value) {
            TpMode.Behind -> {
                val dx = tx - EntityTracker.selfX; val dz = tz - EntityTracker.selfZ
                val dist = Math.sqrt((dx*dx + dz*dz).toDouble()).toFloat()
                val nx = if (dist > 0) dx/dist else 0f
                val nz = if (dist > 0) dz/dist else 0f
                Triple(tx + nx * horizontalSpeed.value * 0.1f, ty, tz + nz * horizontalSpeed.value * 0.1f)
            }
            TpMode.Random -> {
                val angle = Math.random() * Math.PI * 2
                Triple(
                    (tx + Math.cos(angle) * horizontalSpeed.value * 0.1f).toFloat(),
                    ty,
                    (tz + Math.sin(angle) * horizontalSpeed.value * 0.1f).toFloat()
                )
            }
            TpMode.Strafe -> {
                strafeAngle += 0.3 * (strafeSpeed.value / 20f)
                Triple(
                    (tx + Math.cos(strafeAngle) * horizontalSpeed.value * 0.1f).toFloat(),
                    ty,
                    (tz + Math.sin(strafeAngle) * horizontalSpeed.value * 0.1f).toFloat()
                )
            }
            TpMode.Speed -> {
                // Hızlı yaklaşım — oyuncunun baktığı yönde hedefe doğru
                val dx = tx - EntityTracker.selfX; val dz = tz - EntityTracker.selfZ
                val dist = Math.sqrt((dx*dx + dz*dz).toDouble()).toFloat()
                val spd  = horizontalSpeed.value * 0.5f
                val ratio = if (dist > spd) (dist - spd) / dist else 0f
                Triple(EntityTracker.selfX + dx * ratio, ty, EntityTracker.selfZ + dz * ratio)
            }
        }
    }
}