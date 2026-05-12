package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.module.*
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.action.ItemUseOnEntityInventoryAction

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Yakındaki düşmanlara otomatik saldırır"
) {
    enum class AttackMode  { Single, Multi, Switch }
    enum class RotationMode{ Lock, Approximate, None }
    enum class SwingMode   { Client, Server, Both, None }
    enum class PriorityMode{ Distance, Health, Direction }

    private val cpsMin          = int  ("CPS Min",          8,    1,  20)
    private val cpsMax          = int  ("CPS Max",          12,   1,  20)
    private val range           = float("Range",            3.7f, 1f,  6f)
    private val fov             = int  ("Fov",              180,  30, 360)
    private val switchDelay     = int  ("SwitchDelay",      50,   0,  500)
    private val attackMode      = enum ("AttackMode",       AttackMode.Single)
    private val rotationMode    = enum ("RotationMode",     RotationMode.Lock)
    private val swingMode       = enum ("Swing",            SwingMode.Both)
    private val priorityMode    = enum ("PriorityMode",     PriorityMode.Distance)
    private val reversePriority = bool ("ReversePriority",  false)
    private val failRate        = float("FailRate",         0f,   0f,  1f)
    private val shortcut        = bool ("Shortcut",         false)

    private var currentTargetId = 0L
    private var lastSwitchMs    = 0L
    private var lastAttackMs    = 0L
    private var tickJob: Job?   = null

    override fun onEnable() {
        super.onEnable()
        currentTargetId = 0L
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                if (attackMode.value == AttackMode.Multi) attackMulti()
                else attackSingle()
            }
            delay(50L)
        }
    }

    private fun attackSingle() {
        val target = selectTarget() ?: return
        val now = System.currentTimeMillis()
        if (now - lastAttackMs < delayMs()) return
        if (failRate.value > 0f && Math.random() < failRate.value) return
        lastAttackMs = now
        doAttack(target)
    }

    private fun attackMulti() {
        val now = System.currentTimeMillis()
        EntityTracker.getEntitiesInRange(range.value)
            .filter { isInFov(it) }
            .forEach { e ->
                if (failRate.value > 0f && Math.random() < failRate.value) return@forEach
                if (now - lastAttackMs >= delayMs()) {
                    lastAttackMs = now
                    doAttack(e)
                }
            }
    }

    private fun doAttack(e: EntityTracker.TrackedEntity) {
        val session = com.oxclient.events.PacketEventBus.currentSession ?: return

        when (rotationMode.value) {
            RotationMode.Lock        -> sendRotation(e, approx = false, session)
            RotationMode.Approximate -> sendRotation(e, approx = true, session)
            RotationMode.None        -> {}
        }

        when (swingMode.value) {
            SwingMode.Server, SwingMode.Both -> {
                val anim = AnimatePacket().apply {
                    action    = AnimatePacket.Action.SWING_ARM
                    runtimeEntityId = EntityTracker.selfRuntimeId
                }
                session.serverBound(anim)
            }
            else -> {}
        }

        val attack = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
            val action = ItemUseOnEntityInventoryAction()
            action.runtimeEntityId = e.runtimeId
            action.actionType      = ItemUseOnEntityInventoryAction.TYPE_ATTACK
            action.hotbarSlot      = 0
            actions.add(action)
        }
        session.serverBound(attack)
    }

    private fun sendRotation(
        e: EntityTracker.TrackedEntity,
        approx: Boolean,
        session: com.oxclient.core.relay.OxRelaySession
    ) {
        val dx = e.x - EntityTracker.selfX
        val dy = e.y - EntityTracker.selfY
        val dz = e.z - EntityTracker.selfZ
        var yaw   = Math.toDegrees(Math.atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        var pitch = Math.toDegrees(Math.atan2(-dy.toDouble(),
            Math.sqrt((dx*dx + dz*dz).toDouble()))).toFloat()
        if (approx) { yaw += (Math.random()*4-2).toFloat(); pitch += (Math.random()*2-1).toFloat() }

        val move = MovePlayerPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            position        = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            rotation        = Vector3f.from(pitch, yaw, yaw)
            mode            = MovePlayerPacket.Mode.NORMAL
            isOnGround      = true
            ridingRuntimeEntityId = 0L
        }
        session.serverBound(move)
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(range.value).filter { isInFov(it) }

        if (attackMode.value == AttackMode.Switch) {
            val now = System.currentTimeMillis()
            if (now - lastSwitchMs >= switchDelay.value) { currentTargetId = 0L; lastSwitchMs = now }
            val cur = candidates.find { it.runtimeId == currentTargetId }
            if (cur != null) return cur
        }

        val sorted = when (priorityMode.value) {
            PriorityMode.Distance  -> candidates.sortedBy { EntityTracker.distanceTo(it) }
            PriorityMode.Health    -> candidates.sortedBy { it.health }
            PriorityMode.Direction -> candidates.sortedBy { EntityTracker.angleToEntity(it) }
        }
        val result = if (reversePriority.value) sorted.lastOrNull() else sorted.firstOrNull()
        if (attackMode.value == AttackMode.Switch && result != null) currentTargetId = result.runtimeId
        return result
    }

    private fun isInFov(e: EntityTracker.TrackedEntity) =
        fov.value >= 360 || EntityTracker.angleToEntity(e) <= fov.value / 2f

    private fun delayMs(): Long {
        val lo = cpsMin.value.coerceIn(1, 20)
        val hi = cpsMax.value.coerceIn(lo, 20)
        return 1000L / (lo..hi).random()
    }
}
