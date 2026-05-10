package com.oxclient.core.proxy

import com.oxclient.core.relay.RelayInjectionBridge
import java.io.ByteArrayOutputStream

/**
 * PacketHelper
 *
 * ── Mimari Değişikliği ────────────────────────────────────────────────────
 * Eski: injectToServer/Client() → InjectionQueue
 * Yeni: injectToServer/Client() → RelayInjectionBridge → BedrockRelay
 *
 * Şifreleme ve sıkıştırma artık RelayInjectionBridge üzerinden
 * BedrockRelay tarafından otomatik uygulanır.
 *
 * wrapBatch() içindeki PacketProcessor.compressionEnabled kontrolü kaldırıldı.
 * Sıkıştırma relay katmanında (BedrockRelay.injectToServer) yapılır.
 * wrapBatch() sadece [0xFE][varint len][packet] formatında raw batch üretir.
 *
 * Modüller bu sınıfı eskisi gibi kullanmaya devam edebilir — API değişmedi.
 */
object PacketHelper {

    // ── Injection ─────────────────────────────────────────────────────────

    /**
     * Sunucuya paket gönder.
     * Şifreleme + sıkıştırma BedrockRelay tarafından otomatik uygulanır.
     * @param data wrapBatch() çıktısı: [0xFE][batch body]
     */
    fun injectToServer(data: ByteArray) {
        RelayInjectionBridge.sendToServer(data)
    }

    /**
     * İstemciye paket gönder (şifreleme yok).
     * @param data wrapBatch() çıktısı: [0xFE][batch body]
     */
    fun injectToClient(data: ByteArray) {
        RelayInjectionBridge.sendToClient(data)
    }

    // ── MovePlayer ────────────────────────────────────────────────────────

    fun buildMovePlayer(
        runtimeId : Long,
        x         : Float,
        y         : Float,
        z         : Float,
        yaw       : Float   = 0f,
        pitch     : Float   = 0f,
        headYaw   : Float   = 0f,
        onGround  : Boolean = true,
        teleport  : Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.MOVE_PLAYER)
        writeVarLong(out, runtimeId)
        writeFloatLE(out, x)
        writeFloatLE(out, y)
        writeFloatLE(out, z)
        writeFloatLE(out, pitch)
        writeFloatLE(out, yaw)
        writeFloatLE(out, headYaw)
        out.write(if (teleport) 1 else 0)
        out.write(if (onGround) 1 else 0)
        writeVarLong(out, 0L)
        return wrapBatch(out.toByteArray())
    }

    // ── Animate ───────────────────────────────────────────────────────────

    fun buildAnimate(runtimeId: Long, action: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.ANIMATE)
        writeVarInt(out, action)
        writeVarLong(out, runtimeId)
        return wrapBatch(out.toByteArray())
    }

    // ── Attack (InventoryTransaction USE_ITEM_ON_ENTITY) ──────────────────

    fun buildAttack(targetRuntimeId: Long, attackerRuntimeId: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.INVENTORY_TRANSACTION)
        writeVarInt(out, 0)   // legacyRequestId
        writeVarInt(out, 0)   // legacySlot count
        writeVarInt(out, 2)   // txType = USE_ITEM_ON_ENTITY
        writeVarLong(out, targetRuntimeId)
        writeVarInt(out, 1)   // actionType = ATTACK
        writeVarInt(out, 0)   // hotbarSlot
        writeItem(out)        // held item
        writeItem(out)        // unused
        writeVec3(out, 0f, 0f, 0f)
        return wrapBatch(out.toByteArray())
    }

    // ── Interact ──────────────────────────────────────────────────────────

    fun buildInteract(targetRuntimeId: Long, actionId: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.INTERACT)
        out.write(actionId)
        writeVarLong(out, targetRuntimeId)
        writeVec3(out, 0f, 0f, 0f)
        return wrapBatch(out.toByteArray())
    }

    // ── SetHealth ─────────────────────────────────────────────────────────

    fun buildSetHealth(health: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.SET_HEALTH)
        writeVarInt(out, health)
        return wrapBatch(out.toByteArray())
    }

    // ── ContainerClose ────────────────────────────────────────────────────

    fun buildContainerClose(windowId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.CONTAINER_CLOSE)
        out.write(windowId)
        return wrapBatch(out.toByteArray())
    }

    // ── MobEffect ─────────────────────────────────────────────────────────

    /**
     * MobEffect paketi.
     * @param uniqueEntityId zigzag-encoded signed varlong (runtimeId değil!)
     * @param eventId 1=add, 2=modify, 3=remove (varint)
     */
    fun buildMobEffect(
        uniqueEntityId : Long,
        eventId        : Int,
        effectId       : Int,
        amplifier      : Int     = 0,
        particles      : Boolean = false,
        duration       : Int     = 1_000_000
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.MOB_EFFECT)
        writeZigzagVarLong(out, uniqueEntityId)
        writeVarInt(out, eventId)
        writeVarInt(out, effectId)
        writeVarInt(out, amplifier)
        out.write(if (particles) 1 else 0)
        writeVarInt(out, duration)
        return wrapBatch(out.toByteArray())
    }

    // ── UseItem (InventoryTransaction USE_ITEM) ────────────────────────────

    fun buildUseItem(
        actionType : Int,
        blockX: Int, blockY: Int, blockZ: Int,
        face: Int = 1,
        runtimeId: Long = 0L,
        x: Float = 0f, y: Float = 0f, z: Float = 0f
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.INVENTORY_TRANSACTION)
        writeVarInt(out, actionType)
        writeBlockPos(out, blockX, blockY, blockZ)
        writeVarInt(out, face)
        writeVarInt(out, 0)
        writeItem(out)
        writeVec3(out, x, y, z)
        writeVec3(out, 0.5f, 0.5f, 0.5f)
        return wrapBatch(out.toByteArray())
    }

    // ── PlayerAuthInput pozisyon patch ────────────────────────────────────

    fun patchPlayerAuthInputPosition(
        original: ByteArray,
        x: Float, y: Float, z: Float
    ): ByteArray {
        if (original.size < 13) return original
        val copy = original.copyOf()
        val baseOffset = varIntSize(BedrockPacketIds.PLAYER_AUTH_INPUT)
        // [pitch 4][yaw 4][headYaw 4] = 12 byte atla → x offset
        val xOff = baseOffset + 12
        if (copy.size < xOff + 12) return original
        writeFloatLEInto(copy, xOff,     x)
        writeFloatLEInto(copy, xOff + 4, y)
        writeFloatLEInto(copy, xOff + 8, z)
        return copy
    }

    // ── Batch Wrap ────────────────────────────────────────────────────────

    /**
     * Paketi 0xFE batch formatına sar.
     *
     * Format: [0xFE][varint packetLen][packetData]
     *
     * Sıkıştırma ve şifreleme relay katmanında uygulanır (BedrockRelay.injectToServer).
     * Bu metod sadece ham batch üretir — algorithm header eklemez.
     */
    fun wrapBatch(packetData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(1 + 5 + packetData.size)
        out.write(0xFE)
        writeVarInt(out, packetData.size)
        out.write(packetData)
        return out.toByteArray()
    }

    // ── Yazıcılar ─────────────────────────────────────────────────────────

    fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }

    fun writeVarLong(out: ByteArrayOutputStream, value: Long) {
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
    }

    fun writeZigzagVarLong(out: ByteArrayOutputStream, value: Long) {
        val encoded = (value shl 1) xor (value shr 63)
        writeVarLong(out, encoded)
    }

    fun writeFloatLE(out: ByteArrayOutputStream, value: Float) {
        val bits = java.lang.Float.floatToIntBits(value)
        out.write(bits and 0xFF)
        out.write((bits shr 8) and 0xFF)
        out.write((bits shr 16) and 0xFF)
        out.write((bits shr 24) and 0xFF)
    }

    private fun writeFloatLEInto(arr: ByteArray, offset: Int, value: Float) {
        val bits = java.lang.Float.floatToIntBits(value)
        arr[offset]     = (bits and 0xFF).toByte()
        arr[offset + 1] = ((bits shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((bits shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((bits shr 24) and 0xFF).toByte()
    }

    private fun writeVec3(out: ByteArrayOutputStream, x: Float, y: Float, z: Float) {
        writeFloatLE(out, x); writeFloatLE(out, y); writeFloatLE(out, z)
    }

    private fun writeBlockPos(out: ByteArrayOutputStream, x: Int, y: Int, z: Int) {
        writeVarInt(out, x); writeVarInt(out, y); writeVarInt(out, z)
    }

    private fun writeItem(out: ByteArrayOutputStream) {
        writeVarInt(out, 0)  // air item
    }

    private fun varIntSize(value: Int): Int {
        var v = value; var size = 0
        do { v = v ushr 7; size++ } while (v != 0)
        return size
    }

    // ── Okuyucular ────────────────────────────────────────────────────────

    fun readVarInt(data: ByteArray, offset: Int = 0): Pair<Int, Int> {
        var result = 0; var shift = 0; var pos = offset
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to pos
    }

    fun readVarLong(data: ByteArray, offset: Int = 0): Pair<Long, Int> {
        var result = 0L; var shift = 0; var pos = offset
        while (pos < data.size) {
            val b = data[pos++].toLong() and 0xFF
            result = result or ((b and 0x7FL) shl shift)
            if (b and 0x80L == 0L) break
            shift += 7
        }
        return result to pos
    }

    fun readZigzagVarLong(data: ByteArray, offset: Int = 0): Pair<Long, Int> {
        val (raw, pos) = readVarLong(data, offset)
        return ((raw ushr 1) xor -(raw and 1L)) to pos
    }

    fun readFloatLE(data: ByteArray, offset: Int): Float {
        val bits = (data[offset].toInt() and 0xFF) or
                   ((data[offset + 1].toInt() and 0xFF) shl 8) or
                   ((data[offset + 2].toInt() and 0xFF) shl 16) or
                   ((data[offset + 3].toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun readString(data: ByteArray, offset: Int): Pair<String, Int> {
        val (len, pos) = readVarInt(data, offset)
        if (len <= 0) return "" to pos
        if (pos + len > data.size) return "" to (pos + maxOf(len, 0))
        return String(data, pos, len, Charsets.UTF_8) to pos + len
    }
}
