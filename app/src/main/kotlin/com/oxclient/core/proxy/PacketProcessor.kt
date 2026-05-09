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
 * NOT: Protokol 748'de varsayılan olarak sıkıştırma KAPALIDIR.
 * NetworkSettings paketi gönderilene kadar batch body sıkıştırılmaz.
 * NetworkSettings sonrası sıkıştırma açılır ama bu proxy bunu
 * pass-through yapar (sıkıştırılmış içeriği çözmez, sadece EventBus'a bildirir).
 *
 * Bu yaklaşım neden doğru:
 * - Login/Handshake aşamasında sıkıştırma YOK → parse edilebilir
 * - Oyun başladıktan sonra NetworkSettings paketi gelir → sıkıştırma açılır
 * - Sıkıştırma açılınca batch içini parse etmek için zlib inflate gerekir
 * - Biz sadece paket ID'yi okuyup EventBus'a bildiriyoruz
 * - Eğer modül cancel/modify etmezse orijinal veriyi iletiyoruz
 *
 * Zaman aşımı sorununun düzeltilmesi:
 * - processFrameSet MITMProxy'ye taşındı
 * - Burası sadece batch body işler
 * - Hata olursa orijinal veri döner (null/crash değil)
 */
object PacketProcessor {

    private const val TAG = "PacketProcessor"

    // NetworkSettings paketinden sonra sıkıştırma açılır
    @Volatile var compressionEnabled = false
    @Volatile var compressionAlgorithm = 0  // 0=zlib, 1=snappy

    /**
     * Bedrock batch body'sini işle (0xFE'nin arkasındaki baytlar).
     *
     * @param body      0xFE sonrası ham baytlar
     * @param direction Paketin yönü
     * @return          Güncellenmiş body veya null (hiçbir paket kalmadı)
     *                  Hata durumunda orijinal body döner.
     */
    fun processBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (body.isEmpty()) return body

        return try {
            if (compressionEnabled) {
                // Sıkıştırılmış → decompress + işle + compress
                processCompressedBatch(body, direction)
            } else {
                // Sıkıştırılmamış → direkt işle
                processRawBatch(body, direction)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Batch işleme hatası, orijinal veri iletiliyor: ${e.message}")
            body  // Hata → orijinal ilet, bağlantıyı kesme
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
                result == null       -> { changed = true /* cancel */ }
                !result.contentEquals(data) -> { changed = true; packets.add(result) }
                else                 -> packets.add(data)
            }
        }

        if (packets.isEmpty()) return null
        if (!changed) return body

        // Yeniden birleştir
        val out = ByteArrayOutputStream(body.size)
        for (pkt in packets) {
            writeVarInt(out, pkt.size)
            out.write(pkt)
        }
        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SIKIŞTIRMALI BATCH (NetworkSettings sonrası)
    // ─────────────────────────────────────────────────────────────────────

    private fun processCompressedBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        return try {
            // zlib inflate
            val inflater   = java.util.zip.Inflater()
            inflater.setInput(body)
            val inflated   = ByteArrayOutputStream()
            val ibuf       = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(ibuf)
                if (n == 0) break
                inflated.write(ibuf, 0, n)
            }
            inflater.end()

            val decompressed = inflated.toByteArray()
            val processed    = processRawBatch(decompressed, direction) ?: return null

            // zlib deflate
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

        } catch (e: Exception) {
            Log.w(TAG, "Sıkıştırma hatası: ${e.message}")
            body  // orijinali ilet
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEK PAKET İŞLEME
    // ─────────────────────────────────────────────────────────────────────

    private fun processSinglePacket(data: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (data.isEmpty()) return data

        val packetId = readVarInt(ByteArrayInputStream(data))

        // NetworkSettings gelince sıkıştırmayı aç
        if (packetId == 0x8F && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            Log.i(TAG, "NetworkSettings alındı → sıkıştırma aktif")
            compressionEnabled  = true
            compressionAlgorithm = if (data.size > 4) data[4].toInt() and 0xFF else 0
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
            event.isCancelled         -> null
            event.modifiedData != null -> event.modifiedData
            else                      -> data
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
