package com.oxclient.modules.combat

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * OxInventoryTracker — Bedrock envanter durumunu takip eder.
 * Relay listener tarafından beslenir, modüller tarafından sorgulanır.
 */
object OxInventoryTracker {

    // windowId → (slot → ItemData)
    private val windows = ConcurrentHashMap<Int, ConcurrentHashMap<Int, ItemData>>()

    private const val PLAYER_INV_WINDOW = 0

    // ── Feed (OxGameListener tarafından çağrılır) ─────────────────────────────

    fun onInventoryContent(pkt: InventoryContentPacket) {
        val win = windows.getOrPut(pkt.containerId) { ConcurrentHashMap() }
        pkt.contents.forEachIndexed { slot, item -> win[slot] = item }
        Timber.d("[Tracker] Content w=${pkt.containerId} items=${pkt.contents.size}")
    }

    fun onInventorySlot(pkt: InventorySlotPacket) {
        val win = windows.getOrPut(pkt.containerId) { ConcurrentHashMap() }
        win[pkt.slot] = pkt.item
        Timber.d("[Tracker] Slot w=${pkt.containerId} s=${pkt.slot}")
    }

    // ── Sorgu API ─────────────────────────────────────────────────────────────

    fun getItem(slot: Int, windowId: Int = PLAYER_INV_WINDOW): ItemData =
        windows[windowId]?.get(slot) ?: ItemData.AIR

    /**
     * Item tanımlayıcı adına göre envanterde ara.
     * Örn: "minecraft:totem_of_undying"
     * Döner: slot indeksi veya -1.
     */
    fun findItemByName(
        identifier: String,
        windowId  : Int = PLAYER_INV_WINDOW,
        slotRange : IntRange = 0..35
    ): Int {
        val inv = windows[windowId] ?: return -1
        for (slot in slotRange) {
            val item = inv[slot] ?: continue
            if (item == ItemData.AIR) continue
            val id = item.definition?.identifier ?: continue
            if (id.equals(identifier, ignoreCase = true) && item.count > 0) return slot
        }
        return -1
    }

    /** Tüm envanteri temizle (disconnect) */
    fun clear() { windows.clear() }
}
