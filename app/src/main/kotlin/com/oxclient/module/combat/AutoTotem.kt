package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Totemi sürekli sol ele takar"
) {
    companion object {
        private const val TAG = "AutoTotem"
    }

    @Volatile private var totemSlot       = -1
    @Volatile private var offhandHasTotem = false

    override fun onEnable() {
        super.onEnable()
        totemSlot = -1
        offhandHasTotem = false
        scanCachedInventory()
        OverlayLogger.d(TAG, "Enabled: totemSlot=$totemSlot")
    }

    override fun onDisable() {
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled")
    }

    override fun onTick() {
        if (!isEnabled) return
        
        if (offhandHasTotem) return
        if (totemSlot < 0) {
            scanCachedInventory()
            if (totemSlot < 0) return
        }
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
                            if (InventoryUtil.isTotem(item) && totemSlot == -1) {
                                totemSlot = slot
                            }
                        }
                    }
                    119 -> {
                        offhandHasTotem = InventoryUtil.isTotem(pkt.contents.firstOrNull())
                    }
                }
            }

            is InventorySlotPacket -> {
                if (pkt.containerId == 119) {
                    offhandHasTotem = InventoryUtil.isTotem(pkt.item)
                } else if (pkt.containerId == 0 && pkt.slot in 0..35) {
                    if (InventoryUtil.isTotem(pkt.item)) {
                        totemSlot = pkt.slot
                    } else if (totemSlot == pkt.slot) {
                        totemSlot = -1
                    }
                }
            }

            is EntityEventPacket -> {
                if (pkt.runtimeEntityId == EntityTracker.selfRuntimeId) {
                    val type = try { pkt.type?.toString()?.uppercase() ?: "" } catch (_: Exception) { "" }
                    if (type.contains("CONSUME") || type.contains("TOTEM")) {
                        offhandHasTotem = false
                    }
                }
            }
        }
    }

    private fun scanCachedInventory() {
        val snapshot = EntityTracker.getInventorySnapshot()
        totemSlot = -1
        offhandHasTotem = InventoryUtil.isTotem(snapshot[119])

        snapshot.forEach { (slot, item) ->
            if (slot in 0..35 && totemSlot == -1 && InventoryUtil.isTotem(item)) {
                totemSlot = slot
                OverlayLogger.d(TAG, "Totem bulundu: slot=$totemSlot")
            }
        }
    }

    private fun equipTotem() {
        val slot = totemSlot
        if (slot < 0) return

        val itemData = EntityTracker.getInventorySnapshot()[slot] ?: return
        if (!InventoryUtil.isTotem(itemData)) return

        val session = PacketEventBus.currentSession ?: return

        InventoryUtil.sendOffhandEquip(session, slot, itemData)
        offhandHasTotem = true
        OverlayLogger.d(TAG, "✅ Totem takıldı: slot=$slot")
    }
}
