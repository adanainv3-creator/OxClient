package com.oxclient.module.combat

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.module.BaseModule
import com.oxclient.module.BoolSetting
import com.oxclient.module.IntSetting
import com.oxclient.module.ModuleCategory
import com.oxclient.module.ModuleSetting

/**
 * AutoTotem
 *
 * Envanterde totem of undying varken otomatik olarak
 * off-hand slotuna taşır. Ölüm paketi algılandığında tetiklenir.
 *
 * Paket akışı:
 * - SERVER → CLIENT: [MobEffectPacket] / [PlayerActionPacket] ile düşük HP algıla
 * - CLIENT → SERVER: [InventoryTransactionPacket] ile totem taşı
 */
class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Totemi otomatik off-hand'e taşır"
) {

    // ── Ayarlar ───────────────────────────────────────────────────────────

    private val strictMode = BoolSetting(
        name    = "Strict Mode",
        default = false
    )

    private val hpThreshold = IntSetting(
        name    = "HP Eşiği",
        default = 6,
        min     = 1,
        max     = 20
    )

    private val swapDelay = IntSetting(
        name    = "Swap Gecikmesi (ms)",
        default = 50,
        min     = 0,
        max     = 500
    )

    private val notifyOnSwap = BoolSetting(
        name    = "Swap Bildir",
        default = true
    )

    override fun registerSettings(): List<ModuleSetting<*>> =
        listOf(strictMode, hpThreshold, swapDelay, notifyOnSwap)

    // ── State ─────────────────────────────────────────────────────────────

    private var totemSlot: Int = -1
    private var offHandHasTotem: Boolean = false
    private var lastSwapTime: Long = 0L

    companion object {
        private const val TAG          = "AutoTotem"
        const val  TOTEM_ITEM_ID       = 752   // Bedrock runtime ID (yaklaşık)
        const val  OFF_HAND_SLOT       = -1    // off-hand slot ID
        private const val SWAP_COOLDOWN = 200L
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onEnable() {
        totemSlot      = -1
        offHandHasTotem = false
        Log.d(TAG, "AutoTotem aktif — HP eşiği: ${hpThreshold.value}")
    }

    override fun onDisable() {
        totemSlot = -1
        Log.d(TAG, "AutoTotem devre dışı")
    }

    // ── Paket işleme ──────────────────────────────────────────────────────

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (event.direction) {
            PacketEvent.Direction.SERVER_TO_CLIENT -> handleServerPacket(event)
            PacketEvent.Direction.CLIENT_TO_SERVER -> { /* gözlemle */ }
        }
    }

    private fun handleServerPacket(event: PacketEvent) {
        // Gerçek Bedrock protokol implementasyonunda burası
        // RespawnPacket, InventorySlotPacket ve PlayerActionPacket'leri işler.
        // Relay mimarisinde OnlineLoginPacketListener'dan geçen paketler
        // buraya EventBus aracılığıyla ulaşır.

        val data = event.data
        if (data.isEmpty()) return

        val packetId = event.packetId

        when (packetId) {
            // 0x1B = InventorySlotPacket (Bedrock)
            0x1B -> handleInventorySlot(data)
            // 0x2C = PlayerActionPacket (respawn)
            0x2C -> handlePlayerAction(data)
        }
    }

    private fun handleInventorySlot(data: ByteArray) {
        // Inventory slot güncellemesi — totem slotunu izle
        // Gerçek implementasyonda CloudburstMC codec ile parse edilir
        Log.v(TAG, "InventorySlot paketi — totem konumu güncelleniyor")
        updateTotemSlot(data)
    }

    private fun handlePlayerAction(data: ByteArray) {
        // Respawn algılandı → totem off-hand'de olmalı
        if (!offHandHasTotem && totemSlot >= 0) {
            val now = System.currentTimeMillis()
            if (now - lastSwapTime < SWAP_COOLDOWN) return

            Log.d(TAG, "Ölüm algılandı → totem swap (slot $totemSlot → off-hand)")
            performTotemSwap()
        }
    }

    // ── Totem swap logic ──────────────────────────────────────────────────

    private fun updateTotemSlot(data: ByteArray) {
        // Packet parse: InventorySlotPacket → containerId, slot, item.id
        // Simplified — gerçek implementasyonda codec ile
        if (data.size > 4) {
            val potentialSlot = data[2].toInt() and 0xFF
            val potentialId   = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)

            if (potentialId == TOTEM_ITEM_ID) {
                totemSlot = potentialSlot
                Log.d(TAG, "Totem slot güncellendi: $totemSlot")
            }
        }
    }

    private fun performTotemSwap() {
        if (totemSlot < 0) return

        val now = System.currentTimeMillis()
        if (now - lastSwapTime < swapDelay.value + SWAP_COOLDOWN) return

        lastSwapTime    = now
        offHandHasTotem = true

        // Relay üzerinden InventoryTransaction paketi gönder
        // Bu gerçek implementasyonda OxRelaySession.serverBound() ile yapılır
        Log.d(TAG, "Totem swap yapıldı: slot $totemSlot → off-hand")

        if (notifyOnSwap.value) {
            com.oxclient.ui.overlay.OverlayNotifier.showModuleToast("AutoTotem", true)
        }
    }
}
