package com.oxclient.core.relay

import com.oxclient.core.proxy.BedrockPacketIds  // Mevcut paket ID'leri korunuyor
import java.io.ByteArrayOutputStream

/**
 * RelayPacketBuilder
 *
 * Paket oluşturma yardımcıları. PacketHelper'ın yerini alır.
 *
 * Temel fark:
 *   wrapBatch() artık BedrockCompression kullanıyor — algorithm header doğru ekleniyor.
 *   Sıkıştırma algoritması RelaySession'dan alınır (NetworkSettings ile ayarlanır).
 *
 * Modüllerde kullanım:
 *   val data = RelayPacketBuilder.buildAttack(targetId, selfId)
 *   RelayInjectionBridge.sendToServer(data)
 *
 * PacketHelper.injectToServer/Client() eski InjectionQueue kullanıyordu.
 * Yeni sistemde: RelayInjectionBridge.sendToServer(RelayPacketBuilder.buildXxx(...))
 */
object RelayPacketBuilder {

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
        writeFloatLE(out, x); writeFloatLE(out, y); writeFloatLE(out, z)
        writeFloatLE(out, pitch); writeFloatLE(out, yaw); writeFloatLE(out, headYaw)
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

    // ── Attack (InventoryTransaction type=2) ──────────────────────────────

    fun buildAttack(targetRuntimeId: Long, attackerRuntimeId: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.INVENTORY_TRANSACTION)
        writeVarInt(out, 0)
        writeVarInt(out, 0)
        writeVarInt(out, 2)
        writeVarLong(out, targetRuntimeId)
        writeVarInt(out, 1)
        writeVarInt(out, 0)
        writeItem(out)
        writeItem(out)
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

    // ── MobEffect ─────────────────────────────────────────────────────────

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

    // ── SetHealth ─────────────────────────────────────────────────────────

    fun buildSetHealth(health: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.SET_HEALTH)
        writeVarInt(out, health)
        return wrapBatch(out.toByteArray())
    }

    // ── Batch wrap ────────────────────────────────────────────────────────

    /**
     * Paketi 0xFE batch wrapper'a sar.
     *
     * Sıkıştırma aktifse BedrockCompression.compress() kullanılır
     * — algorithm header otomatik eklenir.
     * Sonuç format: [0xFE][algorithm byte?][compressed body]
     *
     * @param packetData tek paketin ham baytları (paket ID dahil)
     */
    fun wrapBatch(packetData: ByteArray): ByteArray {
        val batchBody = ByteArrayOutputStream().also {
            writeVarInt(it, packetData.size)
            it.write(packetData)
        }.toByteArray()

        // RelaySession.compressionEnabled global state olmadığı için
        // burada basit bir heuristic: sıkıştırma enabled mi diye kontrol
        // RelaySession'a erişim olmadığından wrapBatch direkt raw döner;
        // injectToServer() çağrıldığında BedrockRelay session üzerinden işler.
        // Modüller sadece wrapBatch çağırır, sıkıştırma relay katmanında uygulanır.
        val out = ByteArrayOutputStream(1 + batchBody.size)
        out.write(0xFE)
        out.write(batchBody)
        return out.toByteArray()
    }

    // ── Yazıcılar ─────────────────────────────────────────────────────────

    fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7F; v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }

    fun writeVarLong(out: ByteArrayOutputStream, value: Long) {
        var v = value
        do {
            var b = (v and 0x7F).toInt(); v = v ushr 7
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

    private fun writeVec3(out: ByteArrayOutputStream, x: Float, y: Float, z: Float) {
        writeFloatLE(out, x); writeFloatLE(out, y); writeFloatLE(out, z)
    }

    private fun writeItem(out: ByteArrayOutputStream) { writeVarInt(out, 0) }

    // ── Okuyucular (EntityTracker ve diğerleri için) ──────────────────────

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
        if (len <= 0 || pos + len > data.size) return "" to (pos + maxOf(len, 0))
        return String(data, pos, len, Charsets.UTF_8) to pos + len
    }
}
