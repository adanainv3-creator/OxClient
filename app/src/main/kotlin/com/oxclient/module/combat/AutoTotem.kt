package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Totemi sürekli sol ele takar"
) {
    companion object {
        private const val TAG = "AutoTotem"
        private const val RESEND_COOLDOWN_MS = 150L
        private const val NO_RESPONSE_WARN_AFTER = 15
    }

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    @Volatile private var totemSlot = -1
    @Volatile private var offhandHasTotem = false
    @Volatile private var lastSendMs = 0L
    @Volatile private var consecutiveSendsWithoutChange = 0

    override fun onEnable() {
        super.onEnable()
        totemSlot = -1
        offhandHasTotem = false
        consecutiveSendsWithoutChange = 0
        OverlayLogger.i(TAG, "=== AutoTotem ENABLE ===")
        refreshFromSnapshot()
        if (!offhandHasTotem && totemSlot >= 0) {
            equipTotem()
        }
        tickJob = launchTickLoop(100L) { tickCheck() }
    }

    override fun onDisable() {
        super.onDisable()
        tickJob?.cancel()
        tickJob = null
        OverlayLogger.i(TAG, "=== AutoTotem DISABLE === (totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem)")
    }

    private fun refreshFromSnapshot() {
        val snapshot = EntityTracker.getInventorySnapshot()
        offhandHasTotem = InventoryUtil.isTotem(snapshot[InventoryUtil.OFFHAND_SLOT])
        totemSlot = -1
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.INV_END) {
            if (InventoryUtil.isTotem(snapshot[slot])) {
                totemSlot = slot
                break
            }
        }
    }

    private fun tickCheck() {
        val snapshot = EntityTracker.getInventorySnapshot()
        val hasTotemNow = InventoryUtil.isTotem(snapshot[InventoryUtil.OFFHAND_SLOT])
        offhandHasTotem = hasTotemNow
        if (hasTotemNow) {
            consecutiveSendsWithoutChange = 0
            return
        }

        if (totemSlot < 0 || !InventoryUtil.isTotem(snapshot[totemSlot])) {
            refreshFromSnapshot()
        }
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
                    }
                    InventoryUtil.OFFHAND_SLOT -> {
                        val item = pkt.contents.firstOrNull()
                        val nowHasTotem = InventoryUtil.isTotem(item)
                        offhandHasTotem = nowHasTotem
                        if (nowHasTotem) consecutiveSendsWithoutChange = 0
                        if (!nowHasTotem && totemSlot >= 0) equipTotem()
                    }
                }
            }

            is InventorySlotPacket -> {
                if (pkt.containerId == InventoryUtil.OFFHAND_SLOT) {
                    val nowHasTotem = InventoryUtil.isTotem(pkt.item)
                    offhandHasTotem = nowHasTotem
                    if (nowHasTotem) consecutiveSendsWithoutChange = 0
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
                    OverlayLogger.i(TAG, "Totem tüketildi (event=$type)")
                    offhandHasTotem = false
                    totemSlot = -1
                    refreshFromSnapshot()
                    if (totemSlot >= 0) equipTotem() else OverlayLogger.w(TAG, "Totem tüketildi ama envanterde yedek yok")
                }
            }
        }
    }

    private fun equipTotem() {
        val slot = totemSlot
        if (slot < 0) return

        val snapshot = EntityTracker.getInventorySnapshot()
        val itemData = snapshot[slot]
        if (itemData == null || !InventoryUtil.isTotem(itemData)) {
            totemSlot = -1
            return
        }

        val session = PacketEventBus.currentSession
        if (session == null) {
            OverlayLogger.e(TAG, "equipTotem: currentSession NULL")
            return
        }

        lastSendMs = System.currentTimeMillis()
        InventoryUtil.sendOffhandEquip(session, slot, itemData)

        consecutiveSendsWithoutChange++
        if (consecutiveSendsWithoutChange == NO_RESPONSE_WARN_AFTER) {
            OverlayLogger.w(TAG, "MobEquipmentPacket $NO_RESPONSE_WARN_AFTER kez gönderildi ama offhand hala doğrulanmadı - sunucu paketi görmezden geliyor olabilir")
        }
    }
}
