package com.oxclient.module.combat

import android.util.Log
import com.oxclient.events.PacketDirection
import com.oxclient.module.BaseModule
import com.oxclient.proxy.BedrockPacketIds
import com.oxclient.proxy.PacketProcessor
import io.netty.buffer.Unpooled

/**
 * AutoTotem
 *
 * Can < eşik değerine düştüğünde envanterden ölümsüzlük tılsımını
 * otomatik olarak off-hand (slot 119) slotuna taşır.
 *
 * Mekanizma:
 *  1. UpdateAttributes → mevcut canı izler
 *  2. InventoryContent / InventorySlot → totem'in slot numarasını bulur
 *  3. Can tehlikeli seviyeye düşünce ItemStackRequest (SWAP) enjekte eder
 */
class AutoTotem : BaseModule(
    name        = "AutoTotem",
    description = "Can azaldığında otomatik totem takıp çıkarır",
    category    = Category.COMBAT
) {
    // ── Ayarlar ───────────────────────────────────────────────────────────
    var healthThreshold: Float = 6.0f      // Can < bu değerde → totem tak (6 = 3 kalp)
    var alwaysEquip    : Boolean = false   // true = canı ne olursa olsun hep totemli kal

    // ── İç durum ──────────────────────────────────────────────────────────
    @Volatile private var currentHealth : Float = 20f
    @Volatile private var totemSlot     : Int   = -1    // -1 = bulunamadı
    @Volatile private var offhandHasTotem: Boolean = false

    // Totem item ID'si (1.21.60'ta 523)
    private val TOTEM_ITEM_ID = 523
    private val OFFHAND_SLOT  = 119

    override fun onEnable() {
        totemSlot     = -1
        offhandHasTotem = false
        subscribePackets()
        Log.d("AutoTotem", "Etkinleştirildi (eşik=${healthThreshold}hp)")
    }

    private fun subscribePackets() {
        // UpdateAttributes → canı izle
        subscribe(packetId = BedrockPacketIds.UPDATE_ATTRIBUTES, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseHealth(event.data)
        }

        // InventoryContent → tüm envanter içeriğini tara
        subscribe(packetId = BedrockPacketIds.INVENTORY_CONTENT, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseInventoryContent(event.data)
        }

        // InventorySlot → tek slot değişikliğini izle
        subscribe(packetId = BedrockPacketIds.INVENTORY_SLOT, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseInventorySlot(event.data)
        }

        // ItemStackResponse → swap onayı
        subscribe(packetId = BedrockPacketIds.ITEM_STACK_RESPONSE, direction = PacketDirection.CLIENT_BOUND) { event ->
            Log.v("AutoTotem", "StackResponse alındı")
        }

        // EntityEvent → ölüm/yeniden doğma olayı
        subscribe(packetId = BedrockPacketIds.ENTITY_EVENT, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                readVarLong(buf)     // entityRuntimeId
                val eventType = buf.readByte().toInt()
                if (eventType == 57) {  // CONSUME_TOTEM
                    offhandHasTotem = false
                    totemSlot = -1
                    Log.i("AutoTotem", "Totem kullanıldı! Yeni totem aranıyor...")
                }
            } catch (_: Exception) {}
            finally { buf.release() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PARSE
    // ─────────────────────────────────────────────────────────────────────

    private fun parseHealth(data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte()
            readVarLong(buf)                // entityRuntimeId
            val count = readVarInt(buf)
            repeat(count) {
                val attrName = readString(buf)
                val minVal   = buf.readFloatLE()
                val curVal   = buf.readFloatLE()
                val maxVal   = buf.readFloatLE()
                if (attrName == "minecraft:health") {
                    currentHealth = curVal
                    checkAndEquipTotem()
                }
            }
        } catch (_: Exception) {}
        finally { buf.release() }
    }

    private fun parseInventoryContent(data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte()
            readVarInt(buf)                 // containerId
            val count = readVarInt(buf)
            for (i in 0 until count) {
                val itemId = readVarInt(buf)
                if (buf.isReadable) readVarInt(buf)   // count
                if (buf.isReadable) readVarInt(buf)   // damage
                if (itemId == TOTEM_ITEM_ID && i != OFFHAND_SLOT) {
                    totemSlot = i
                }
                if (itemId == TOTEM_ITEM_ID && i == OFFHAND_SLOT) {
                    offhandHasTotem = true
                }
            }
            checkAndEquipTotem()
        } catch (_: Exception) {}
        finally { buf.release() }
    }

    private fun parseInventorySlot(data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte()
            readVarInt(buf)                 // containerId
            val slot   = readVarInt(buf)
            val itemId = readVarInt(buf)
            if (itemId == TOTEM_ITEM_ID && slot != OFFHAND_SLOT) totemSlot = slot
            if (slot == OFFHAND_SLOT) offhandHasTotem = (itemId == TOTEM_ITEM_ID)
        } catch (_: Exception) {}
        finally { buf.release() }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TOTEM TAKMA
    // ─────────────────────────────────────────────────────────────────────

    private fun checkAndEquipTotem() {
        val shouldEquip = alwaysEquip || currentHealth <= healthThreshold
        if (!shouldEquip || offhandHasTotem) return
        if (totemSlot == -1) {
            Log.w("AutoTotem", "Envanterde totem bulunamadı!")
            return
        }
        val swapPacket = PacketProcessor.buildSwapTotemPacket(totemSlot, OFFHAND_SLOT)
        PacketProcessor.injectToServer(swapPacket)
        offhandHasTotem = true
        Log.i("AutoTotem", "Totem takıldı (slot $totemSlot → offhand) | Can: $currentHealth")
    }

    // ── VarInt/String yardımcıları ────────────────────────────────────────

    private fun readVarLong(buf: io.netty.buffer.ByteBuf): Long {
        var r = 0L; var s = 0
        while (buf.isReadable) { val b = buf.readByte().toLong(); r = r or ((b and 0x7F) shl s); if (b and 0x80L == 0L) break; s += 7 }
        return r
    }

    private fun readVarInt(buf: io.netty.buffer.ByteBuf): Int {
        var r = 0; var s = 0
        while (buf.isReadable) { val b = buf.readByte().toInt(); r = r or ((b and 0x7F) shl s); if (b and 0x80 == 0) break; s += 7 }
        return r
    }

    private fun readString(buf: io.netty.buffer.ByteBuf): String {
        val len = readVarInt(buf)
        if (len <= 0 || len > buf.readableBytes()) return ""
        val b = ByteArray(len); buf.readBytes(b); return String(b, Charsets.UTF_8)
    }
}
