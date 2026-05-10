package com.oxclient.core.relay

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.ui.overlay.OverlayLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * RelaySession
 *
 * Tek bir proxy bağlantısının durumunu (şifreleme, sıkıştırma, sayaçlar)
 * tutar. MITMProxy her yeni bağlantıda yeni bir RelaySession oluşturur.
 *
 * ── Neden Global State Yerine Instance? ──────────────────────────────────
 * Eski PacketProcessor tek bir object (singleton) idi. Bu yüzden:
 *  - İki bağlantı aynı anda açılırsa state birbirine karışır.
 *  - stop() + start() arasında state temizlenmezse eski sayaçlar kalır.
 * RelaySession instance-per-connection yaklaşımı bu sorunları ortadan kaldırır.
 *
 * ── Batch İşleme Akışı ───────────────────────────────────────────────────
 * S→C:  [şifre çöz] → [decompress] → [paket parse] → [EventBus] → [recompress] → [şifrele (yok)]
 * C→S:  [şifre çöz (yok)] → [decompress] → [paket parse] → [EventBus] → [recompress] → [şifrele]
 */
class RelaySession {

    companion object {
        private const val TAG = "RelaySession"
    }

    // Sıkıştırma durumu — NetworkSettings (0x8F) paketi ile ayarlanır
    @Volatile var compressionEnabled   = false
    @Volatile var compressionAlgorithm = BedrockCompression.ALG_ZLIB

    // Şifreleme durumu — ServerToClientHandshake (0x03) sonrası aktif olur
    @Volatile var encryptionEnabled = false
    @Volatile private var cipher: BedrockCipher? = null

    fun enableEncryption(secretKey: ByteArray) {
        cipher = BedrockCipher(secretKey)
        encryptionEnabled = true
        OverlayLogger.i(TAG, "✅ Şifreleme aktif (AES-CFB8, key=${secretKey.size}B)")
    }

    fun reset() {
        compressionEnabled   = false
        compressionAlgorithm = BedrockCompression.ALG_ZLIB
        encryptionEnabled    = false
        cipher               = null
        OverlayLogger.d(TAG, "RelaySession sıfırlandı")
    }

    // ── Şifreleme yardımcıları ────────────────────────────────────────────

    fun decrypt(body: ByteArray): ByteArray =
        cipher?.decrypt(body) ?: body

    fun encrypt(body: ByteArray): ByteArray =
        cipher?.encrypt(body) ?: body

    // ── Batch işleme: 0xFE body (header hariç) ───────────────────────────

    /**
     * @param body  0xFE header'ı çıkarılmış raw batch body
     * @param direction paket yönü
     * @return işlenmiş body (null = paket bloke edildi)
     */
    fun processBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (body.isEmpty()) return body

        try {
            // 1. Deşifrele (sadece S→C)
            val decrypted = if (encryptionEnabled && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
                decrypt(body)
            } else body

            // 2. Decompress
            val decompressed = if (compressionEnabled) {
                BedrockCompression.decompress(decrypted, compressionAlgorithm, encryptionEnabled)
            } else decrypted

            // 3. Paket parse + EventBus
            val (processed, changed) = processRawBatch(decompressed, direction)
            if (processed == null) return null

            // 4. Recompress
            val recompressed = if (compressionEnabled) {
                BedrockCompression.compress(
                    processed,
                    compressionAlgorithm,
                    encrypted = false  // recompress sırasında şifreleme yok, ayrı adım
                )
            } else processed

            // 5. Şifrele (sadece C→S)
            return if (encryptionEnabled && direction == PacketEvent.Direction.CLIENT_TO_SERVER) {
                encrypt(recompressed)
            } else recompressed

        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Batch işleme hatası: ${e.message}")
            return body  // Hata durumunda orijinali ilet — bağlantıyı kesme
        }
    }

    /**
     * Raw (sıkıştırılmamış) batch içindeki paketleri parse et ve işle.
     * @return (işlenmiş batch bytes, değişti mi)
     */
    private fun processRawBatch(
        raw       : ByteArray,
        direction : PacketEvent.Direction
    ): Pair<ByteArray?, Boolean> {
        val stream  = ByteArrayInputStream(raw)
        val output  = ByteArrayOutputStream(raw.size)
        var changed = false
        var hasPackets = false

        while (stream.available() > 0) {
            val len = readVarInt(stream)
            if (len <= 0 || stream.available() < len) break

            val pktData = ByteArray(len)
            stream.read(pktData)
            hasPackets = true

            val result = processSinglePacket(pktData, direction)

            when {
                result == null -> {
                    // Paket bloke edildi
                    changed = true
                }
                !result.contentEquals(pktData) -> {
                    changed = true
                    writeVarInt(output, result.size)
                    output.write(result)
                }
                else -> {
                    writeVarInt(output, pktData.size)
                    output.write(pktData)
                }
            }
        }

        if (!hasPackets) return Pair(raw, false)
        val resultBytes = output.toByteArray()
        return if (resultBytes.isEmpty()) Pair(null, true)
        else Pair(resultBytes, changed)
    }

    private fun processSinglePacket(data: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (data.isEmpty()) return data

        val stream   = ByteArrayInputStream(data)
        val packetId = readVarInt(stream)

        // NetworkSettings (0x8F) → sıkıştırmayı aktif et
        if (packetId == 0x8F && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            handleNetworkSettings(data)
        }

        val event = PacketEvent(packetId, data, direction)
        try {
            PacketEventBus.publish(event)
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "EventBus hatası pkt=0x${packetId.toString(16)}: ${e.message}", e)
        }

        return when {
            event.isCancelled          -> null
            event.modifiedData != null -> event.modifiedData
            else                       -> data
        }
    }

    private fun handleNetworkSettings(data: ByteArray) {
        try {
            // [varint packetId] [uint16 LE threshold] [uint16 LE algorithm] ...
            val stream = ByteArrayInputStream(data)
            readVarInt(stream) // packetId atla
            // threshold (2 bytes LE) — okunup atılır
            val tLow  = stream.read()
            val tHigh = stream.read()
            // algorithm (2 bytes LE)
            val aLow  = stream.read()
            val aHigh = stream.read()
            val algorithm = (aLow and 0xFF) or ((aHigh and 0xFF) shl 8)
            compressionAlgorithm = when (algorithm) {
                1    -> BedrockCompression.ALG_SNAPPY
                0xFF -> BedrockCompression.ALG_NONE
                else -> BedrockCompression.ALG_ZLIB
            }
            compressionEnabled = true
            OverlayLogger.i(TAG, "NetworkSettings → sıkıştırma aktif: " +
                when (compressionAlgorithm) {
                    BedrockCompression.ALG_SNAPPY -> "Snappy"
                    BedrockCompression.ALG_NONE   -> "Yok"
                    else                          -> "zlib"
                })
        } catch (e: Exception) {
            compressionEnabled   = true
            compressionAlgorithm = BedrockCompression.ALG_ZLIB
            OverlayLogger.w(TAG, "NetworkSettings parse hatası — zlib varsayılan: ${e.message}")
        }
    }

    // ── VarInt yardımcıları ───────────────────────────────────────────────

    private fun readVarInt(stream: ByteArrayInputStream): Int {
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

    private fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }
}
