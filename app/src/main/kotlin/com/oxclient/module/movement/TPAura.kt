package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.action.ItemUseOnEntityInventoryAction
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Hedefe ışınlanarak saldırır"
) {
    enum class TpMode { Random, Strafe, Behind, Speed }

    private val mode             = enum ("Mode",            TpMode.Strafe)
    private val range            = float("Range",           1.5f, 0.5f, 6f)
    private val yOffset          = float("Y Offset",        0f,  -2f,   2f)
    private val horizontalSpeed  = float("HorizontalSpeed", 6.11f,0.1f,20f)
    private val verticalSpeed    = float("VerticalSpeed",   4f,   0.1f,10f)
    private val strafeSpeed      = float("StrafeSpeed",     20f,  1f,  50f)
    private val shortcut         = bool ("Shortcut",        true)

    @Volatile private var tpInProgress = false
    @Volatile private var lastAttackMs = 0L
    private var strafeAngle = 0.0
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        tpInProgress = false; strafeAngle = 0.0
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
        val session = PacketEventBus.currentSession

        val origX = EntityTracker.selfX; val origY = EntityTracker.selfY; val origZ = EntityTracker.selfZ
        val (tpX, tpY, tpZ) = calcPosition(target)

        fun move(x: Float, y: Float, z: Float, teleport: Boolean) = MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(x, y, z)
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = if (teleport) MovePlayerPacket.Mode.TELEPORT else MovePlayerPacket.Mode.NORMAL
            isOnGround            = true
            ridingRuntimeEntityId = 0L
        }

        session?.serverBound(move(tpX, tpY + verticalSpeed.value * 0.1f, tpZ, teleport = true))
        delay(30L)

        session?.serverBound(AnimatePacket().apply { action = AnimatePacket.Action.SWING_ARM; runtimeEntityId = EntityTracker.selfRuntimeId })
        session?.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
            val a = ItemUseOnEntityInventoryAction()
            a.runtimeEntityId = target.runtimeId; a.actionType = ItemUseOnEntityInventoryAction.TYPE_ATTACK; a.hotbarSlot = 0
            actions.add(a)
        })
        delay(50L)

        session?.serverBound(move(origX, origY, origZ, teleport = true))
        tpInProgress = false
    }

    private fun calcPosition(t: EntityTracker.TrackedEntity): Triple<Float, Float, Float> {
        val tx = t.x; val ty = t.y + yOffset.value; val tz = t.z
        return when (mode.value) {
            TpMode.Behind -> {
                val dx = tx - EntityTracker.selfX; val dz = tz - EntityTracker.selfZ
                val dist = Math.sqrt((dx*dx + dz*dz).toDouble()).toFloat().coerceAtLeast(0.001f)
                Triple(tx + (dx/dist) * horizontalSpeed.value * 0.1f, ty, tz + (dz/dist) * horizontalSpeed.value * 0.1f)
            }
            TpMode.Random -> {
                val a = Math.random() * Math.PI * 2
                Triple((tx + Math.cos(a) * horizontalSpeed.value * 0.1f).toFloat(), ty, (tz + Math.sin(a) * horizontalSpeed.value * 0.1f).toFloat())
            }
            TpMode.Strafe -> {
                strafeAngle += 0.3 * (strafeSpeed.value / 20f)
                Triple((tx + Math.cos(strafeAngle) * horizontalSpeed.value * 0.1f).toFloat(), ty, (tz + Math.sin(strafeAngle) * horizontalSpeed.value * 0.1f).toFloat())
            }
            TpMode.Speed -> {
                val dx = tx - EntityTracker.selfX; val dz = tz - EntityTracker.selfZ
                val dist = Math.sqrt((dx*dx + dz*dz).toDouble()).toFloat()
                val spd = horizontalSpeed.value * 0.5f
                val ratio = if (dist > spd) (dist - spd) / dist else 0f
                Triple(EntityTracker.selfX + dx * ratio, ty, EntityTracker.selfZ + dz * ratio)
            }
        }
    }
}
