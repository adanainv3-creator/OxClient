package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Totemi sürekli sol ele takar"
) {
    // En yüksek öncelik — KillAura/TPAura/Pro'dan önce işlenir
    override val priority = 0

    companion object {
        private const val RESEND_COOLDOWN_MS = 25L
    }

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    @Volatile private var totemSlot      = -1
    @Volatile private var offhandHasTotem = false
    @Volatile private var lastSendMs      = 0L

    override fun onEnable() {
        super.onEnable()
        totemSlot     = -1
        offhandHasTotem = false
        lastSendMs    = 0L
        refreshFromSnapshot()
        if (!offhandHasTotem && totemSlot >= 0) equipTotem()
        tickJob = launchTickLoop(10L) { tickCheck() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private fun refreshFromSnapshot() {
        val snapshot = EntityTracker.getInventorySnapshot()
        offhandHasTotem = InventoryUtil.isTotem(snapshot[InventoryUtil.OFFHAND_SLOT])
        totemSlot = -1
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.INV_END) {
            if (InventoryUtil.isTotem(snapshot[slot])) { totemSlot = slot; break }
        }
    }

    private fun tickCheck() {
        val snapshot    = EntityTracker.getInventorySnapshot()
        val hasTotemNow = InventoryUtil.isTotem(snapshot[InventoryUtil.OFFHAND_SLOT])
        offhandHasTotem = hasTotemNow
        if (hasTotemNow) return

        if (totemSlot < 0 || !InventoryUtil.isTotem(snapshot[totemSlot])) refreshFromSnapshot()
        if (totemSlot < 0) return

        val now = System.currentTimeMillis()
        if (now - lastSendMs < RESEND_COOLDOWN_MS) return
        equipTotem()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (val pkt = event.packet) {
            is InventoryContentPacket -> {
                when (pkt.containerId) {
                    0 -> {
                        totemSlot = -1
                        pkt.contents.forEachIndexed { slot, item ->
                            if (totemSlot == -1 && InventoryUtil.isTotem(item)) totemSlot = slot
                        }
                        if (!offhandHasTotem && totemSlot >= 0) equipTotem()
                    }
                    InventoryUtil.OFFHAND_SLOT -> {
                        val nowHasTotem = InventoryUtil.isTotem(pkt.contents.firstOrNull())
                        offhandHasTotem = nowHasTotem
                        if (!nowHasTotem && totemSlot >= 0) equipTotem()
                    }
                }
            }

            is InventorySlotPacket -> {
                if (pkt.containerId == InventoryUtil.OFFHAND_SLOT) {
                    val nowHasTotem = InventoryUtil.isTotem(pkt.item)
                    offhandHasTotem = nowHasTotem
                    if (!nowHasTotem && totemSlot >= 0) equipTotem()
                } else if (pkt.containerId == 0) {
                    if (InventoryUtil.isTotem(pkt.item)) {
                        if (totemSlot == -1) totemSlot = pkt.slot
                    } else if (totemSlot == pkt.slot) {
                        totemSlot = -1
                        refreshFromSnapshot()
                    }
                }
            }

            is EntityEventPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val type = runCatching { pkt.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (type.contains("CONSUME") || type.contains("TOTEM")) {
                    offhandHasTotem = false
                    totemSlot = -1
                    refreshFromSnapshot()
                    // Totem tüketildi — gecikme olmadan anında tak
                    if (totemSlot >= 0) equipTotem()
                }
            }
        }
    }

    private fun equipTotem() {
        val slot = totemSlot
        if (slot < 0) return

        val snapshot = EntityTracker.getInventorySnapshot()
        val itemData = snapshot[slot]
        if (itemData == null || !InventoryUtil.isTotem(itemData)) { totemSlot = -1; return }

        val session = PacketEventBus.currentSession ?: return
        val offhandItem = EntityTracker.getInventoryItem(InventoryUtil.OFFHAND_SLOT) ?: ItemData.AIR

        lastSendMs = System.currentTimeMillis()

        InventoryUtil.sendInventoryMove(
            session           = session,
            sourceContainer   = ContainerSlotType.HOTBAR_AND_INVENTORY,
            sourceContainerId = 0,
            sourceSlot        = slot,
            sourceItem        = itemData,
            destContainer     = ContainerSlotType.OFFHAND,
            destContainerId   = ContainerId.OFFHAND,
            destSlot          = 0,
            destItem          = offhandItem
        )
    }
}
