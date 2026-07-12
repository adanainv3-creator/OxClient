package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.module.*
import com.oxclient.utils.PacketUtil
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import java.util.ArrayDeque

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
) {
    enum class CritMode { Vanilla, MovePacket, Jump, Packet }

    private val mode     = enum ("Mode",      CritMode.MovePacket)
    private val cooldown = int  ("Cooldown",   0, 0, 500)
    private val shortcut = bool ("Shortcut",   false)

    @Volatile private var lastCritMs = 0L
    private val pendingOffsets = ArrayDeque<Float>()

    private fun sequenceFor(m: CritMode): List<Float> = when (m) {
        CritMode.Vanilla    -> listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f)
        CritMode.MovePacket -> listOf(0.11f, 0f)
        CritMode.Jump       -> listOf(0.0625f, 0f, 0.0625f, 0f)
        CritMode.Packet     -> listOf(0.0001f, 0f)
    }

    override fun onEnable() {
        super.onEnable()
        pendingOffsets.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        when (val pkt = event.packet) {
            is InventoryTransactionPacket -> {
                val isAttack = pkt.transactionType == InventoryTransactionType.ITEM_USE_ON_ENTITY &&
                               pkt.actionType == 1
                if (!isAttack) return

                val now = System.currentTimeMillis()
                if (now - lastCritMs < cooldown.value) return
                lastCritMs = now

                pendingOffsets.clear()
                pendingOffsets.addAll(sequenceFor(mode.value))
            }
            is PlayerAuthInputPacket -> {
                val offset = pendingOffsets.poll() ?: return
                if (offset != 0f) PacketUtil.applyAuthInputFallOffset(pkt, offset)
            }
            else -> {}
        }
    }
}
