package com.oxclient.core.proxy

import java.io.ByteArrayOutputStream

/**
 * PacketHelper
 *
 * Tüm modüllerin ortak kullandığı Bedrock paket builder'ları.
 *
 * ✅ FIX (KRİTİK): wrapBatch() artık PacketProcessor.compressionEnabled
 *    durumuna göre sıkıştırma yapıyor.
 *
 *    Önceki hata: NetworkSettings geldikten sonra sunucu SIKIŞTIRILMIŞ
 *    paket bekliyor. Ama enjekte edilen paketler ham (sıkıştırılmamış)
 *    gönderiliyordu → sunucu çöp veri alıyor → drop → hiçbir modül
 *    sunucuda efekt yaratmıyor (KillAura vuruyor ama hasar gitmiyor,
 *    TPAura teleport etmiyor, Criticals çalışmıyor vb.)
 *
 *    Çözüm: wrapBatch() içinde, compression aktifse paketi sıkıştır.
 *    Bu fonksiyon tüm builder'lar (buildAttack, buildMovePlayer vb.)
 *    tarafından çağrıldığından tek noktadan düzeltme yeterli.
 */
object PacketHelper {

    // ── Injection ─────────────────────────────────────────────────────────

    fun injectToServer(data: ByteArray) {
        InjectionQueue.enqueueToServer(data)
    }

    fun injectToClient(data: ByteArray) {
        InjectionQueue.enqueueToClient(data)
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

    // ── Attack ────────────────────────────────────────────────────────────

    fun buildAttack(targetRuntimeId: Long, attackerRuntimeId: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.INVENTORY_TRANSACTION)
        writeVarInt(out, 0)   // legacyRequestId
        writeVarInt(out, 0)   // legacySlots count
        writeVarInt(out, 2)   // transaction type: ItemUseOnEntityTransaction
        writeVarLong(out, targetRuntimeId)
        writeVarInt(out, 1)   // action: Attack
        writeVarInt(out, 0)   // hotbar slot
        writeItem(out)        // held item (air)
        writeItem(out)        // clicked item (air) — ✅ FIX: eksik 2. item eklendi
        writeVec3(out, 0f, 0f, 0f)  // click pos
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

    fun buildMobEffect(
        runtimeId : Long,
        eventId   : Int,
        effectId  : Int,
        amplifier : Int     = 0,
        particles : Boolean = false,
        duration  : Int     = 1000000
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.MOB_EFFECT)
        writeVarLong(out, runtimeId)
        out.write(eventId)
        writeVarInt(out, effectId)
        writeVarInt(out, amplifier)
        out.write(if (particles) 1 else 0)
        writeVarInt(out, duration)
        return wrapBatch(out.toByteArray())
    }

    // ── UseItem ───────────────────────────────────────────────────────────

    fun buildUseItem(
        actionType : Int,
        blockX: Int, blockY: Int, blockZ: Int,
        face: Int = 1,
        runtimeId: Long = 0L,
        x: Float = 0f, y: Float = 0f, z: Float = 0f
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.INVENTORY_TRANSACTION)  // ✅ FIX: USE_ITEM alias kaldırıldı, doğru paket 0x1E
        writeVarInt(out, actionType)
        writeBlockPos(out, blockX, blockY, blockZ)
        writeVarInt(out, face)
        writeVarInt(out, 0)
        writeItem(out)
        writeVec3(out, x, y, z)
        writeVec3(out, 0.5f, 0.5f, 0.5f)
        return wrapBatch(out.toByteArray())
    }

    // ── PlayerAuthInput patch ─────────────────────────────────────────────

    fun patchPlayerAuthInputPosition(
        original: ByteArray,
        x: Float, y: Float, z: Float
    ): ByteArray {
        if (original.size < 13) return original
        val copy = original.copyOf()
        val baseOffset = varIntSize(BedrockPacketIds.PLAYER_AUTH_INPUT)
        val xOff = baseOffset + 8
        if (copy.size < xOff + 12) return original
        writeFloatLEInto(copy, xOff,     x)
        writeFloatLEInto(copy, xOff + 4, y)
        writeFloatLEInto(copy, xOff + 8, z)
        return copy
    }

    // ── Batch Wrap ────────────────────────────────────────────────────────

    /**
     * ✅ FIX (KRİTİK): Sıkıştırma durumuna göre batch oluştur.
     *
     * Önceki kod: her zaman ham (sıkıştırılmamış) batch üretiyordu.
     * NetworkSettings sonrası sunucu sıkıştırılmış paket bekler.
     * Ham paket gönderilince sunucu RakNet seviyesinde iyi alıyor ama
     * Bedrock decode aşamasında sıkıştırılmış bekleyip şifreli veri
     * görüyor → paketi tamamen ignore ediyor.
     *
     * Batch format (sıkıştırmasız):
     *   [0xFE] [varint(packetLen)] [packetData]
     *
     * Batch format (sıkıştırmalı):
     *   [0xFE] [compress([varint(packetLen)] [packetData])]
     *   NOT: 0xFE'den SONRAKI kısım sıkıştırılır, 0xFE sıkıştırılmaz.
     */
    fun wrapBatch(packetData: ByteArray): ByteArray {
        // İç batch body: varint(len) + packetData
        val batchBody = ByteArrayOutputStream().also { buf ->
            writeVarInt(buf, packetData.size)
            buf.write(packetData)
        }.toByteArray()

        // Sıkıştırma gerekiyorsa uygula
        val finalBody = if (PacketProcessor.compressionEnabled) {
            when (PacketProcessor.compressionAlgorithm) {
                1    -> compressSnappy(batchBody)
                else -> zlibDeflate(batchBody)
            }
        } else {
            batchBody
        }

        // 0xFE başlığı ile sar
        val out = ByteArrayOutputStream(1 + finalBody.size)
        out.write(0xFE)
        out.write(finalBody)
        return out.toByteArray()
    }

    // ── Sıkıştırma yardımcıları ───────────────────────────────────────────

    private fun zlibDeflate(input: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(input); deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun compressSnappy(input: ByteArray): ByteArray {
        return try {
            org.iq80.snappy.Snappy.compress(input)
        } catch (e: Exception) {
            zlibDeflate(input)
        }
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
        writeVarInt(out, x)
        writeVarInt(out, y)
        writeVarInt(out, z)
    }

    private fun writeItem(out: ByteArrayOutputStream) {
        writeVarInt(out, 0)  // runtime ID 0 = air
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

    /**
     * Zigzag encoded signed varlong okur (uniqueEntityId için).
     * Bedrock START_GAME ve ADD_PLAYER'da uniqueEntityId zigzag encode'ludur.
     */
    fun readZigzagVarLong(data: ByteArray, offset: Int = 0): Pair<Long, Int> {
        val (raw, pos) = readVarLong(data, offset)
        // zigzag decode: (raw >>> 1) XOR -(raw AND 1)
        val decoded = (raw ushr 1) xor -(raw and 1L)
        return decoded to pos
    }

    fun readFloatLE(data: ByteArray, offset: Int): Float {
        val bits = (data[offset].toInt() and 0xFF) or
                   ((data[offset+1].toInt() and 0xFF) shl 8) or
                   ((data[offset+2].toInt() and 0xFF) shl 16) or
                   ((data[offset+3].toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun readString(data: ByteArray, offset: Int): Pair<String, Int> {
        val (len, pos) = readVarInt(data, offset)
        if (len <= 0 || pos + len > data.size) return "" to pos
        return String(data, pos, len, Charsets.UTF_8) to pos + len
    }
}
