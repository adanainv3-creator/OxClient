package com.oxclient.modules.combat

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * OxInventoryTracker — Bedrock envanter durumunu takip eder.
 *
 * InventoryContentPacket ve InventorySlotPacket relay dinleyicisi tarafından
 * buraya beslenir. Modüller (AutoTotem, vb.) bu singleton üzerinden envanter sorgular.
 */
object OxInventoryTracker {

    // windowId → (slot → ItemData)
    private val windows = ConcurrentHashMap<Int, ConcurrentHashMap<Int, ItemData>>()

    private const val PLAYER_INVENTORY_WINDOW = 0
    private const val OFFHAND_WINDOW          = 119
    private const val OFFHAND_SLOT            = 0

    // ── Relay feed ────────────────────────────────────────────────────────────

    fun onInventoryContent(pkt: InventoryContentPacket) {
        val win = windows.getOrPut(pkt.containerId) { ConcurrentHashMap() }
        pkt.contents.forEachIndexed { slot, item ->
            win[slot] = item
        }
        Timber.d("[Tracker] InventoryContent windowId=${pkt.containerId} items=${pkt.contents.size}")
    }

    fun onInventorySlot(pkt: InventorySlotPacket) {
        val win = windows.getOrPut(pkt.containerId) { ConcurrentHashMap() }
        win[pkt.slot] = pkt.item
        Timber.d("[Tracker] InventorySlot windowId=${pkt.containerId} slot=${pkt.slot}")
    }

    // ── Query API ─────────────────────────────────────────────────────────────

    fun getItem(slot: Int, windowId: Int = PLAYER_INVENTORY_WINDOW): ItemData {
        return windows[windowId]?.get(slot) ?: ItemData.AIR
    }

    fun offhandItem(): ItemData = getItem(OFFHAND_SLOT, OFFHAND_WINDOW)

    fun offhandHasItem(runtimeId: Int): Boolean =
        offhandItem().definition?.runtimeId == runtimeId

    /**
     * Belirli item'ı envanterden arar, slot index döner (-1 = bulunamadı).
     * [hotbarOnly] = true → sadece 0-8 arası hotbar slotları.
     * [deepScan]   = true → tüm envanter 0-35.
     */
    fun findItem(
        runtimeId : Int,
        deepScan  : Boolean = true,
        hotbarOnly: Boolean = false
    ): Int {
        val inv = windows[PLAYER_INVENTORY_WINDOW] ?: return -1
        val range = when {
            hotbarOnly -> 0..8
            deepScan   -> 0..35
            else       -> 0..35
        }
        for (slot in range) {
            val item = inv[slot] ?: continue
            if (item.definition?.runtimeId == runtimeId && item.count > 0) return slot
        }
        return -1
    }

    /** Tüm envanteri temizle (disconnect vb.) */
    fun clear() { windows.clear() }

    /** Boş ItemData yardımcısı */
    fun emptyItem(): ItemData = ItemData.AIR
}
