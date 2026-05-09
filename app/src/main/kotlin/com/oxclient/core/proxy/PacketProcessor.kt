package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * PacketProcessor
 *
 * Bedrock batch (0xFE payload) içindeki paketleri işler.
 *
 * ✅ FIX: EntityTracker artık her iki yön için de çağrılıyor.
 *         Önceden sadece SERVER_TO_CLIENT için çağrılıyordu.
 *         Bu yüzden PLAYER_AUTH_INPUT (0x90, CLIENT_TO_SERVER) hiç
 *         işlenmiyordu → selfX/Y/Z hep 0 kalıyordu → tüm modüller
 *         mesafe hesabı yapamıyor, hedef bulamıyordu.
 */
object PacketProcessor {

    private const val TAG = "PacketProcessor"

    @Volatile var compressionEnabled   = false
    @Volatile var compressionAlgorithm = 0     // 0 = zlib, 1 = snappy

    fun processBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (body.isEmpty()) return body

        return try {
            if (compressionEnabled) {
                processCompressedBatch(body, direction)
            } else {
                processRawBatch(body, direction)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Batch işleme hatası, orijinal veri iletiliyor: ${e.message}")
            body
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SIKIŞTIRMASIZ BATCH
    // ─────────────────────────────────────────────────────────────────────

    private fun processRawBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        val stream  = ByteArrayInputStream(body)
        val packets = mutableListOf<ByteArray>()
        var changed = false

        while (stream.available() > 0) {
            val len = readVarInt(stream)
            if (len <= 0 || stream.available() < len) break

            val data = ByteArray(len)
            stream.read(data)

            val result = processSinglePacket(data, direction)

            when {
                result == null              -> { changed = true }
                !result.contentEquals(data) -> { changed = true; packets.add(result) }
                else                        -> packets.add(data)
            }
        }

        if (packets.isEmpty()) return null
        if (!changed) return body

        val out = ByteArrayOutputStream(body.size)
        for (pkt in packets) {
            writeVarInt(out, pkt.size)
            out.write(pkt)
        }
        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SIKIŞTIRMALI BATCH
    // ─────────────────────────────────────────────────────────────────────

    private fun processCompressedBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        return try {
            val decompressed: ByteArray = when (compressionAlgorithm) {
                1    -> decompressSnappy(body)
                else -> zlibInflate(body)
            }

            val processed = processRawBatch(decompressed, direction) ?: return null

            when (compressionAlgorithm) {
                1    -> compressSnappy(processed)
                else -> zlibDeflate(processed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sıkıştırma hatası: ${e.message}")
            body
        }
    }

    private fun zlibInflate(input: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(input)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

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

    // ─────────────────────────────────────────────────────────────────────
    //  SNAPPY
    // ─────────────────────────────────────────────────────────────────────

    private fun decompressSnappy(input: ByteArray): ByteArray {
        return try {
            org.iq80.snappy.Snappy.uncompress(input, 0, input.size)
        } catch (e: Exception) {
            Log.w(TAG, "Snappy decompress başarısız, zlib deneniyor: ${e.message}")
            zlibInflate(input)
        }
    }

    private fun compressSnappy(input: ByteArray): ByteArray {
        return try {
            org.iq80.snappy.Snappy.compress(input)
        } catch (e: Exception) {
            Log.w(TAG, "Snappy compress başarısız, zlib kullanılıyor: ${e.message}")
            zlibDeflate(input)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEK PAKET İŞLEME
    // ─────────────────────────────────────────────────────────────────────

    private fun processSinglePacket(data: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (data.isEmpty()) return data

        val packetId = readVarInt(ByteArrayInputStream(data))

        // NetworkSettings — sıkıştırmayı aktif et
        if (packetId == 0xC7 && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            Log.i(TAG, "NetworkSettings alındı (0xC7) → sıkıştırma aktif")
            compressionEnabled = true
            compressionAlgorithm = if (data.size > 4) data[4].toInt() and 0xFF else 0
            Log.i(TAG, "Sıkıştırma algoritması: ${if (compressionAlgorithm == 1) "Snappy" else "zlib"}")
        }

        // ✅ FIX: EntityTracker her iki yön için de çağrılıyor.
        //         Önceki kod sadece SERVER_TO_CLIENT'ı işliyordu.
        //         PLAYER_AUTH_INPUT (0x90) CLIENT_TO_SERVER paketidir —
        //         bu olmadan selfX/Y/Z hiç güncellenmiyor.
        try { EntityTracker.onPacket(PacketEvent(packetId, data, direction)) }
        catch (_: Exception) {}

        // Event bus
        val event = PacketEvent(packetId, data, direction)
        try {
            PacketEventBus.publish(event)
        } catch (e: Exception) {
            Log.e(TAG, "EventBus hatası pkt=${packetId.toString(16)}: ${e.message}")
        }

        return when {
            event.isCancelled          -> null
            event.modifiedData != null -> event.modifiedData
            else                       -> data
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  YARDIMCILAR
    // ─────────────────────────────────────────────────────────────────────

    fun readVarInt(stream: ByteArrayInputStream): Int {
        var result = 0; var shift = 0
        repeat(5) {
            val b = stream.read()
            if (b == -1) return result
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return result
    }

    fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }

    fun writeVarInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream(4)
        writeVarInt(out, value)
        return out.toByteArray()
    }
}
