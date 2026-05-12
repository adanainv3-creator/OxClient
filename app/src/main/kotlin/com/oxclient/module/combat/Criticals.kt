package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.action.ItemUseOnEntityInventoryAction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
) {
    enum class CritMode { Vanilla, MovePacket, Jump, TPJump }

    private val mode     = enum("Mode",     CritMode.Vanilla)
    private val shortcut = bool("Shortcut", false)

    @Volatile private var lastCritMs = 0L

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet as? InventoryTransactionPacket ?: return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (pkt.transactionType != InventoryTransactionType.ITEM_USE_ON_ENTITY) return
        val isAttack = pkt.actions.filterIsInstance<ItemUseOnEntityInventoryAction>()
            .any { it.actionType == ItemUseOnEntityInventoryAction.TYPE_ATTACK }
        if (!isAttack) return
        val now = System.currentTimeMillis()
        if (now - lastCritMs < 100) return
        lastCritMs = now
        val session = PacketEventBus.currentSession ?: return
        when (mode.value) {
            CritMode.Vanilla    -> injectVanilla(session)
            CritMode.MovePacket -> injectMovePacket(session)
            CritMode.Jump       -> injectJump(session)
            CritMode.TPJump     -> injectTPJump(session)
        }
    }

    private fun buildMove(dy: Float, onGround: Boolean, teleport: Boolean = false) =
        MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY + dy, EntityTracker.selfZ)
            rotation              = Vector3f.from(EntityTracker.selfPitch, EntityTracker.selfYaw, EntityTracker.selfYaw)
            mode                  = if (teleport) MovePlayerPacket.Mode.TELEPORT else MovePlayerPacket.Mode.NORMAL
            isOnGround            = onGround
            ridingRuntimeEntityId = 0L
        }

    private fun injectVanilla(s: com.oxclient.core.relay.OxRelaySession) {
        var cumY = 0f
        listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f).forEach { dy ->
            cumY += dy; s.serverBound(buildMove(cumY, dy == 0f))
        }
    }

    private fun injectMovePacket(s: com.oxclient.core.relay.OxRelaySession) {
        s.serverBound(buildMove(0.11f, false))
        s.serverBound(buildMove(0f, true))
    }

    private fun injectJump(s: com.oxclient.core.relay.OxRelaySession) {
        listOf(0.0625f, 0f, 0.0625f, 0f).forEach { dy -> s.serverBound(buildMove(dy, dy == 0f)) }
    }

    private fun injectTPJump(s: com.oxclient.core.relay.OxRelaySession) {
        s.serverBound(buildMove(0.42f, false, teleport = true))
        s.serverBound(buildMove(0f, true, teleport = true))
    }
}
