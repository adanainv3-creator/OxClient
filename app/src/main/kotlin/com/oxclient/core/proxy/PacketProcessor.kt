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
 * Batch format:
 *   [varint(len)][packet_data] × N
 *
 * Sıkıştırma:
 *   NetworkSettings paketi (0xC7) gelene kadar sıkıştırma KAPALIDIR.
 *   NetworkSettings içindeki algorithm byte'ı:
 *     0 = zlib (deflate)
 *     1 = Snappy
 *   2b2tpe ve benzeri sunucular genellikle Snappy kullanır.
 *
 * ✅ FIX: NetworkSettings ID 0x8F → 0xC7 (Bedrock 1.20.10+ protokol 748)
 *         Snappy decompression/compression desteği eklendi.
 */
object PacketProcessor {

    private const val TAG = "PacketProcessor"

    @Volatile var compressionEnabled   = false
    @Volatile var compressionAlgorithm = 0     // 0 = zlib, 1 = snappy

    /**
     * Bedrock batch body'sini işle (0xFE'nin arkasındaki baytlar).
     *
     * @param body      0xFE sonrası ham baytlar
     * @param direction Paketin yönü
     * @return          Güncellenmiş body veya null (hiçbir paket kalmadı).
     *                  Hata durumunda orijinal body döner.
     */
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
                result == null                  -> { changed = true }
                !result.contentEquals(data)     -> { changed = true; packets.add(result) }
                else                            -> packets.add(data)
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
            // ── Decompress ────────────────────────────────────────────────
            val decompressed: ByteArray = when (compressionAlgorithm) {
                1 -> {
                    // ✅ FIX: Snappy desteği (2b2tpe algoritması)
                    decompressSnappy(body)
                }
                else -> {
                    // zlib inflate
                    val inflater = java.util.zip.Inflater()
                    inflater.setInput(body)
                    val inflated = ByteArrayOutputStream()
                    val ibuf     = ByteArray(4096)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(ibuf)
                        if (n == 0) break
                        inflated.write(ibuf, 0, n)
                    }
                    inflater.end()
                    inflated.toByteArray()
                }
            }

            // ── İşle ─────────────────────────────────────────────────────
            val processed = processRawBatch(decompressed, direction) ?: return null

            // ── Compress ─────────────────────────────────────────────────
            when (compressionAlgorithm) {
                1 -> {
                    // ✅ FIX: Snappy compress
                    compressSnappy(processed)
                }
                else -> {
                    val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION)
                    deflater.setInput(processed)
                    deflater.finish()
                    val deflated = ByteArrayOutputStream()
                    val dbuf     = ByteArray(4096)
                    while (!deflater.finished()) {
                        val n = deflater.deflate(dbuf)
                        if (n > 0) deflated.write(dbuf, 0, n)
                    }
                    deflater.end()
                    deflated.toByteArray()
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Sıkıştırma hatası: ${e.message}")
            body
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SNAPPY YARDIMCILARI
    //  build.gradle: implementation 'org.iq80.snappy:snappy:0.4'
    // ─────────────────────────────────────────────────────────────────────

    private fun decompressSnappy(input: ByteArray): ByteArray {
        return try {
            org.iq80.snappy.Snappy.uncompress(input, 0, input.size)
        } catch (e: Exception) {
            Log.w(TAG, "Snappy decompress başarısız, zlib deneniyor: ${e.message}")
            // Snappy başarısız olursa zlib ile dene (sunucu uyumsuzluğu)
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
            out.toByteArray()
        }
    }

    private fun compressSnappy(input: ByteArray): ByteArray {
        return try {
            org.iq80.snappy.Snappy.compress(input)
        } catch (e: Exception) {
            Log.w(TAG, "Snappy compress başarısız, zlib kullanılıyor: ${e.message}")
            val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            deflater.setInput(input); deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            deflater.end()
            out.toByteArray()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEK PAKET İŞLEME
    // ─────────────────────────────────────────────────────────────────────

    private fun processSinglePacket(data: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (data.isEmpty()) return data

        val packetId = readVarInt(ByteArrayInputStream(data))

        // ✅ FIX: NetworkSettings paket ID'si 0x8F DEĞİL 0xC7'dir.
        //         Bedrock protokol 748 (1.20.10+) itibarıyla NetworkSettings = 0xC7.
        //         0x8F hiç gelmez → compressionEnabled asla true olmazdı,
        //         bu yüzden sıkıştırmalı paketler hiç işlenmiyordu.
        //         Algoritma byte'ı: data[2] (varint paketId + 1 byte sonrası)
        if (packetId == 0xC7 && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            Log.i(TAG, "NetworkSettings alındı (0xC7) → sıkıştırma aktif")
            compressionEnabled = true
            // NetworkSettings format: [packetId varint][threshold short LE][algorithm byte]
            // varint(0xC7) = 2 byte → algorithm byte index = 4
            compressionAlgorithm = if (data.size > 4) data[4].toInt() and 0xFF else 0
            Log.i(TAG, "Sıkıştırma algoritması: ${if (compressionAlgorithm == 1) "Snappy" else "zlib"}")
        }

        // EntityTracker'a bildir (S→C paketleri için)
        if (direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            try { EntityTracker.onPacket(PacketEvent(packetId, data, direction)) }
            catch (_: Exception) {}
        }

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
