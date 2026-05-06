package com.oxclient.modules.combat

import com.oxclient.events.PacketEvent
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import timber.log.Timber

/**
 * AutoTotem — Offhand totemi biter bitmez envanterden yenisini takı.
 *
 * Paket akışı:
 *   InventoryContentPacket / InventorySlotPacket → OxInventoryTracker'ı besle
 *   InventoryTransactionPacket (sunucudan)       → offhand durumunu güncelle
 *   Her tick (50ms)                              → offhand boşsa equip planla
 */
class AutoTotem : BaseModule(
    name        = "AutoTotem",
    description = "Totem bitince otomatik yenisini takı",
    category    = ModuleCategory.COMBAT
) {
    // ── Ayarlar ───────────────────────────────────────────────────────────────
    val delay   = intSetting("Delay (ms)", min = 0, max = 300, default = 60)
    val logging = boolSetting("Logging", default = false)

    // ── Runtime durum ─────────────────────────────────────────────────────────
    @Volatile private var pendingEquip    = false
    @Volatile private var lastEquipMs     = 0L
    @Volatile private var offhandHasTotem = false
    @Volatile private var totemSlot       = -1

    // Bedrock runtime item ID — bedrock-connection SNAPSHOT sürümünde
    // item runtime ID'leri dinamik. Onun yerine isim bazlı arama yapalım.
    private val TOTEM_ITEM_NAME = "minecraft:totem_of_undying"
    private val OFFHAND_WINDOW  = 119
    private val OFFHAND_SLOT    = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        pendingEquip    = false
        offhandHasTotem = false
        totemSlot       = -1
        lastEquipMs     = 0L
        log("AutoTotem aktif")
    }

    override fun onDisable() {
        pendingEquip = false
        log("AutoTotem pasif")
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    override suspend fun onTick() {
        if (!pendingEquip) return
        val now = System.currentTimeMillis()
        if (now - lastEquipMs < delay.value) return
        val slot = totemSlot
        if (slot < 0) return

        pendingEquip = false
        lastEquipMs  = now
        equipTotem(slot)
    }

    // ── Paket dinleyici ───────────────────────────────────────────────────────

    override fun onPacketReceive(event: PacketEvent) {
        val pkt = event.packet ?: return
        when (pkt) {
            is InventoryContentPacket -> {
                OxInventoryTracker.onInventoryContent(pkt)
                if (pkt.containerId == OFFHAND_WINDOW) {
                    val item = pkt.contents.firstOrNull()
                    offhandHasTotem = isTotem(item)
                    if (!offhandHasTotem) scheduleEquip()
                }
            }
            is InventorySlotPacket -> {
                OxInventoryTracker.onInventorySlot(pkt)
                if (pkt.containerId == OFFHAND_WINDOW && pkt.slot == OFFHAND_SLOT) {
                    offhandHasTotem = isTotem(pkt.item)
                    if (!offhandHasTotem) scheduleEquip()
                }
            }
            is InventoryTransactionPacket -> {
                checkOffhandUpdate(pkt)
            }
            else -> {}
        }
    }

    override fun onPacketSend(event: PacketEvent) {
        // PlayerAuthInput her frame gelir — offhand boşsa equip planla
        if (!offhandHasTotem && !pendingEquip) scheduleEquip()
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────

    private fun scheduleEquip() {
        if (offhandHasTotem || pendingEquip) return
        val slot = OxInventoryTracker.findItemByName(TOTEM_ITEM_NAME)
        if (slot < 0) return
        totemSlot    = slot
        pendingEquip = true
        log("Slot $slot'ta totem bulundu, equip planlandı (delay=${delay.value}ms)")
    }

    private fun checkOffhandUpdate(pkt: InventoryTransactionPacket) {
        // InventoryActionData API: source, fromSlot, fromItem, toSlot, toItem
        // Gerçek field adları: slot -> source+slot kombinasyonu
        pkt.actions.forEach { action ->
            val src = action.source
            if (src?.containerId == OFFHAND_WINDOW) {
                // Offhand'e bir şey geldi
                offhandHasTotem = isTotem(action.toItem)
                if (offhandHasTotem) {
                    pendingEquip = false
                    log("Offhand totemi takıldı ✓")
                }
            }
        }
    }

    private fun equipTotem(fromSlot: Int) {
        if (!OxRelayBridge.isActive) return
        try {
            val totemItem = OxInventoryTracker.getItem(fromSlot)
            if (!isTotem(totemItem)) { totemSlot = -1; return }

            val txPacket = InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.NORMAL
                // Envanterden offhand'e taşı
                actions.add(InventoryActionData(
                    InventorySource.fromContainerWindowId(0),          // oyuncu envanteri
                    fromSlot, totemItem, ItemData.AIR
                ))
                actions.add(InventoryActionData(
                    InventorySource.fromContainerWindowId(OFFHAND_WINDOW), // offhand
                    OFFHAND_SLOT, ItemData.AIR, totemItem
                ))
            }
            OxRelayBridge.sendToServer(txPacket)
            offhandHasTotem = true
            log("InventoryTransaction gönderildi: slot $fromSlot → offhand")
        } catch (e: Exception) {
            Timber.e(e, "[AutoTotem] equipTotem hata")
        }
    }

    private fun isTotem(item: ItemData?): Boolean {
        if (item == null || item == ItemData.AIR) return false
        val name = item.definition?.identifier ?: return false
        return name.equals(TOTEM_ITEM_NAME, ignoreCase = true)
    }

    private fun log(msg: String) {
        if (logging.value) Timber.d("[AutoTotem] $msg")
    }
}
