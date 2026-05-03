package com.oxclient.module.combat

import android.util.Log
import com.oxclient.core.proxy.PacketFactory
import com.oxclient.core.proxy.PacketIds
import com.oxclient.events.PacketEvent
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.session.SessionManager
import com.oxclient.ui.overlay.OverlayState
import com.oxclient.utils.BinaryUtils
import java.util.concurrent.atomic.AtomicInteger

/**
 * AutoTotem — automatically equips Totem of Undying to off-hand slot.
 * Totem count (⊕ N) is shown in the overlay HUD top-left.
 */
class AutoTotem : BaseModule(
    name        = "AutoTotem",
    description = "Auto-equips Totem of Undying to off-hand",
    category    = ModuleCategory.COMBAT
) {
    private val inventoryMirror  = IntArray(36) { 0 }
    private var offhandId        = 0
    private val totemCountAtomic = AtomicInteger(0)
    @Volatile private var pendingTx  = false
    @Volatile private var pendingMs  = 0L

    val totemCount: Int get() = totemCountAtomic.get()

    override fun onEnable() {
        inventoryMirror.fill(0); offhandId = 0; pendingTx = false
        OverlayState.updateTotemCount(0)
        Log.i("AutoTotem", "enabled")
    }
    override fun onDisable() { OverlayState.updateTotemCount(0) }

    override fun onPacketReceive(event: PacketEvent) {
        when (event.packetId) {
            PacketIds.INVENTORY_CONTENT -> parseContent(event.payload)
            PacketIds.INVENTORY_SLOT    -> parseSlot(event.payload)
        }
    }

    override fun onPacketSend(event: PacketEvent) {
        if (event.packetId != PacketIds.PLAYER_AUTH_INPUT) return
        // Timeout safety
        if (pendingTx && System.currentTimeMillis() - pendingMs > 1500L) pendingTx = false
        if (offhandId == PacketIds.ITEM_TOTEM_OF_UNDYING || pendingTx) return
        val slot = inventoryMirror.indexOfFirst { it == PacketIds.ITEM_TOTEM_OF_UNDYING }
        if (slot < 0) return
        SessionManager.proxy?.injectC2S(PacketFactory.buildOffhandEquip(slot, PacketIds.ITEM_TOTEM_OF_UNDYING, 1))
        pendingTx = true; pendingMs = System.currentTimeMillis()
        Log.i("AutoTotem","Injected totem swap from slot $slot")
    }

    private fun parseContent(payload: ByteArray) {
        val b = BinaryUtils.wrap(payload)
        BinaryUtils.skipVarInt(b)
        val cid   = BinaryUtils.readVarInt(b)
        val count = BinaryUtils.readVarInt(b)
        when (cid) {
            PacketIds.CONTAINER_INVENTORY -> {
                inventoryMirror.fill(0)
                for (i in 0 until minOf(count, 36)) {
                    inventoryMirror[i] = BinaryUtils.readVarInt(b)
                    skipItemRemainder(b)
                }
                refresh()
            }
            PacketIds.CONTAINER_OFFHAND -> {
                offhandId  = if (count > 0) BinaryUtils.readVarInt(b) else 0
                pendingTx  = false
            }
        }
    }

    private fun parseSlot(payload: ByteArray) {
        val b   = BinaryUtils.wrap(payload)
        BinaryUtils.skipVarInt(b)
        val cid  = BinaryUtils.readVarInt(b)
        val slot = BinaryUtils.readVarInt(b)
        val id   = BinaryUtils.readVarInt(b)
        when (cid) {
            PacketIds.CONTAINER_INVENTORY -> { if (slot < 36) { inventoryMirror[slot] = id; refresh() } }
            PacketIds.CONTAINER_OFFHAND   -> { offhandId = id; pendingTx = false }
        }
    }

    private fun refresh() {
        val n = inventoryMirror.count { it == PacketIds.ITEM_TOTEM_OF_UNDYING }
        totemCountAtomic.set(n); OverlayState.updateTotemCount(n)
    }

    private fun skipItemRemainder(b: java.nio.ByteBuffer) {
        try {
            BinaryUtils.readVarInt(b)
            BinaryUtils.readVarInt(b)
            val nbt = b.get()
            if (nbt != 0.toByte()) { val len = BinaryUtils.readVarInt(b); repeat(len) { if (b.hasRemaining()) b.get() } }
        } catch (_: Exception) {}
    }
}
