package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.PacketUtil
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.action.ItemUseOnEntityInventoryAction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
) {
    enum class CritMode { Vanilla, MovePacket, Jump, TPJump, Packet }

    private val mode      = enum ("Mode",         CritMode.Vanilla)
    private val onlyAura  = bool ("Only KillAura", false)
    private val cooldown  = int  ("Cooldown",      100, 0, 500)
    private val shortcut  = bool ("Shortcut",      false)

    @Volatile private var lastCritMs = 0L

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet as? InventoryTransactionPacket ?: return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (pkt.transactionType != InventoryTransactionType.ITEM_USE_ON_ENTITY) return

        // FIX: 3.x — actions list contains InventoryAction subtypes; filter and check type
        val isAttack = pkt.actions
            .filterIsInstance<ItemUseOnEntityInventoryAction>()
            .any { it.type == ItemUseOnEntityInventoryAction.TYPE_ATTACK }

        if (!isAttack) return
        val now = System.currentTimeMillis()
        if (now - lastCritMs < cooldown.value) return
        lastCritMs = now
        val session = PacketEventBus.currentSession ?: return
        when (mode.value) {
            CritMode.Vanilla    -> injectVanilla(session)
            CritMode.MovePacket -> injectMovePacket(session)
            CritMode.Jump       -> injectJump(session)
            CritMode.TPJump     -> injectTPJump(session)
            CritMode.Packet     -> injectPacket(session)
        }
    }

    private fun injectVanilla(s: com.oxclient.core.relay.OxRelaySession) {
        var cumY = 0f
        listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f).forEach { dy ->
            cumY += dy
            PacketUtil.sendMoveAtSelf(s, dyOffset = cumY, onGround = dy == 0f)
        }
    }

    private fun injectMovePacket(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.11f, onGround = false)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = true)
    }

    private fun injectJump(s: com.oxclient.core.relay.OxRelaySession) {
        listOf(0.0625f, 0f, 0.0625f, 0f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = dy == 0f)
        }
    }

    private fun injectTPJump(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false, teleport = true)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = true,  teleport = true)
    }

    private fun injectPacket(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0001f, onGround = false)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,      onGround = false)
    }
}
