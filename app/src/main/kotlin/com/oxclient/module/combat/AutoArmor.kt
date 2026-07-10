package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap

class AutoArmor : BaseModule(
    name        = "AutoArmor",
    category    = ModuleCategory.COMBAT,
    description = "En iyi zırhı otomatik giyer"
) {
    companion object {
        private const val RESEND_COOLDOWN_MS = 250L
        private const val NO_RESPONSE_WARN_AFTER = 15
    }

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    private val armorSlots = ConcurrentHashMap<Int, ItemData>()
    private val lastSendMs = ConcurrentHashMap<Int, Long>()
    private val consecutiveSendsWithoutChange = ConcurrentHashMap<Int, Int>()

    override fun onEnable() {
        super.onEnable()
        armorSlots.clear()
        lastSendMs.clear()
        consecutiveSendsWithoutChange.clear()
        tickJob = launchTickLoop(300L) { checkAndEquipBestArmor() }
    }

    override fun onDisable() {
        super.onDisable()
        tickJob?.cancel()
        tickJob = null
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (val pkt = event.packet) {
            is InventoryContentPacket -> {
                if (pkt.containerId == ContainerId.ARMOR) {
                    armorSlots.clear()
                    pkt.contents.forEachIndexed { slot, item ->
                        if (!InventoryUtil.isEmpty(item)) armorSlots[slot] = item
                    }
                    consecutiveSendsWithoutChange.clear()
                }
            }
            is InventorySlotPacket -> {
                if (pkt.containerId == ContainerId.ARMOR) {
                    if (InventoryUtil.isEmpty(pkt.item)) armorSlots.remove(pkt.slot)
                    else armorSlots[pkt.slot] = pkt.item
                    consecutiveSendsWithoutChange.remove(pkt.slot)
                }
            }
        }
    }

    private fun checkAndEquipBestArmor() {
        val session = PacketEventBus.currentSession ?: return
        val snapshot = EntityTracker.getInventorySnapshot()

        val bestBySlot = HashMap<InventoryUtil.ArmorSlotType, Pair<Int, ItemData>>()
        for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.INV_END) {
            val item = snapshot[slot] ?: continue
            val armorType = InventoryUtil.resolveArmorSlotType(item) ?: continue
            val tier = InventoryUtil.armorMaterialTier(item)
            val current = bestBySlot[armorType]
            if (current == null || tier > InventoryUtil.armorMaterialTier(current.second)) {
                bestBySlot[armorType] = slot to item
            }
        }

        for ((armorType, candidate) in bestBySlot) {
            val (sourceSlot, sourceItem) = candidate
            val equipped = armorSlots[armorType.slotIndex]
            val equippedTier = InventoryUtil.armorMaterialTier(equipped)
            val candidateTier = InventoryUtil.armorMaterialTier(sourceItem)

            if (equippedTier >= candidateTier) continue

            val now = System.currentTimeMillis()
            val last = lastSendMs[armorType.slotIndex] ?: 0L
            if (now - last < RESEND_COOLDOWN_MS) continue
            lastSendMs[armorType.slotIndex] = now

            InventoryUtil.sendInventoryMove(
                session           = session,
                sourceContainer   = ContainerSlotType.HOTBAR_AND_INVENTORY,
                sourceContainerId = 0,
                sourceSlot        = sourceSlot,
                sourceItem        = sourceItem,
                destContainer     = ContainerSlotType.ARMOR,
                destContainerId   = ContainerId.ARMOR,
                destSlot          = armorType.slotIndex,
                destItem          = equipped ?: ItemData.AIR
            )

            val count = (consecutiveSendsWithoutChange[armorType.slotIndex] ?: 0) + 1
            consecutiveSendsWithoutChange[armorType.slotIndex] = count
        }
    }
}
