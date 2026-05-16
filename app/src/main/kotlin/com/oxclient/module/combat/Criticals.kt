package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.PacketUtil
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
) {
    enum class CritMode { Vanilla, MovePacket, Jump, TPJump, Packet }

    private val mode     = enum ("Mode",          CritMode.Vanilla)
    private val cooldown = int  ("Cooldown",       100, 0, 500)
    private val shortcut = bool ("Shortcut",       false)

    @Volatile private var lastCritMs = 0L

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? InventoryTransactionPacket ?: return

        val isAttack = try {
            pkt.transactionType == InventoryTransactionPacket.TYPE_ITEM_USE_ON_ENTITY &&
            pkt.actionType      == InventoryTransactionPacket.ACTION_ENTITY_ATTACK
        } catch (_: Exception) {
            pkt.transactionType == InventoryTransactionPacket.TYPE_ITEM_USE_ON_ENTITY
        }
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
        listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = dy == 0f)
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
