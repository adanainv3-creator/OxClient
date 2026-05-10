package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.ui.overlay.OverlayLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PacketProcessor
 *
 * Bedrock 1.16.220+ şifreleme: AES-256-GCM (tag-less, CTR modu ile eşdeğer)
 * Sıkıştırma: NetworkSettings paketi (0x8F) ile bildirilir.
 *
 * ✅ FIX (KRİTİK): NETWORK_SETTINGS sabiti BedrockPacketIds'e eklendi (0x8F).
 *    Önceki kodda bu sabit yoktu → Kotlin derleme hatası veya 0 değeri →
 *    NetworkSettings hiç tanınmıyor → sıkıştırma hiç açılmıyor →
 *    tüm oyun paketleri işlenemiyor → hileler/EntityTracker çalışmıyor.
 *
 * NetworkSettings paketi formatı (Bedrock 1.21.x):
 *   [varint]  packetId = 0x8F
 *   [uint16 LE] compressionThreshold
 *   [uint16 LE] compressionAlgorithm  (0=zlib, 1=snappy)
 *   [bool]    enableClientThrottling
 *   [uint8]   clientThrottleThreshold
 *   [float32] clientThrottleScalar
 */
object PacketProcessor {

    private const val TAG = "PacketProcessor"

    @Volatile var compressionEnabled   = false
    @Volatile var compressionAlgorithm = 0     // 0 = zlib, 1 = snappy

    @Volatile var encryptionEnabled    = false
    @Volatile private var secretKeyBytes: ByteArray? = null
    @Volatile private var sendCounter  = 0L
    @Volatile private var recvCounter  = 0L

    fun enableEncryption(keyBytes: ByteArray) {
        secretKeyBytes    = keyBytes.copyOf()
        sendCounter       = 0L
        recvCounter       = 0L
        encryptionEnabled = true
        OverlayLogger.i(TAG, "✅ Şifreleme aktif (AES-256-GCM/CTR, key=${keyBytes.size}B)")
    }

    fun resetEncryption() {
        encryptionEnabled = false
        secretKeyBytes    = null
        sendCounter       = 0L
        recvCounter       = 0L
    }

    fun reset() {
        compressionEnabled   = false
        compressionAlgorithm = 0
        resetEncryption()
        OverlayLogger.d(TAG, "PacketProcessor sıfırlandı")
    }

    // ── AES-256-GCM/CTR şifre çözme (S→C) ──────────────────────────────

    private fun gcmDecrypt(data: ByteArray): ByteArray {
        val key = secretKeyBytes ?: return data
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val iv        = key.copyOf(12)
            val ctrIv     = ByteArray(16)
            System.arraycopy(iv, 0, ctrIv, 0, 12)
            ctrIv[15] = 1  // GCM initial counter = 1
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ctrIv))
            cipher.doFinal(data)
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "GCM decrypt hatası: ${e.message}")
            data
        }
    }

    // ── AES-256-GCM/CTR şifreleme (C→S inject) ──────────────────────────

    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = secretKeyBytes ?: return plaintext
        return try {
            val checksum  = computeChecksum(plaintext, sendCounter, key)
            sendCounter++
            val payload   = plaintext + checksum
            val secretKey = SecretKeySpec(key, "AES")
            val iv        = key.copyOf(12)
            val ctrIv     = ByteArray(16)
            System.arraycopy(iv, 0, ctrIv, 0, 12)
            ctrIv[15] = 1
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(ctrIv))
            cipher.doFinal(payload)
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "GCM encrypt hatası: ${e.message}")
            plaintext
        }
    }

    private fun computeChecksum(data: ByteArray, counter: Long, key: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val counterBytes = ByteArray(8)
        var c = counter
        for (i in 0 until 8) { counterBytes[i] = (c and 0xFF).toByte(); c = c ushr 8 }
        digest.update(counterBytes)
        digest.update(data)
        digest.update(key)
        return digest.digest().copyOf(8)
    }

    // ── Ana işleme ───────────────────────────────────────────────────────

    fun processBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (body.isEmpty()) return body
        return try {
            val decrypted = if (encryptionEnabled &&
                               direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
                val dec = gcmDecrypt(body)
                if (dec.size > 8) dec.copyOf(dec.size - 8) else dec
            } else {
                body
            }

            val result = if (compressionEnabled) processCompressedBatch(decrypted, direction)
                         else                    processRawBatch(decrypted, direction)

            if (encryptionEnabled &&
                direction == PacketEvent.Direction.CLIENT_TO_SERVER &&
                result != null) {
                encrypt(result)
            } else {
                result
            }
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Batch işleme hatası: ${e.message}")
            body
        }
    }

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
        for (pkt in packets) { writeVarInt(out, pkt.size); out.write(pkt) }
        return out.toByteArray()
    }

    private fun processCompressedBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        // Bedrock 1.20+: sıkıştırılmış batch'in başında 1 byte compression header olabilir.
        // 0x00 = zlib, 0x01 = snappy, 0xFF = sıkıştırma yok (raw batch)
        //
        // KRITIK: Bu header'ı algılamak için body[0]'a bakmak yeterli değil;
        // 0x78 (zlib magic) veya geçerli bir varint ile başlıyorsa header YOK demektir.
        // En güvenli yol: önce header var mı dene, yoksa direkt dene.

        val first = if (body.isNotEmpty()) body[0].toInt() and 0xFF else -1

        // 0xFF = "no compression" header → body[1..] raw batch
        if (first == 0xFF) {
            val raw = body.copyOfRange(1, body.size)
            val processed = processRawBatch(raw, direction) ?: return null
            return byteArrayOf(0xFF.toByte()) + processed
        }

        // 0x00 veya 0x01 = compression type header → body[1..] sıkıştırılmış
        // Ama bu byte'lar aynı zamanda varint'in başı da olabilir.
        // Güvenli yol: eğer 0x00 ya da 0x01 ise ve arkasındaki data decompress edilebiliyorsa header kabul et.
        val hasAlgorithmHeader = (first == 0x00 || first == 0x01)
        val headerAlgorithm    = first  // 0=zlib, 1=snappy

        if (hasAlgorithmHeader) {
            val candidate = body.copyOfRange(1, body.size)
            val decompressed = tryDecompress(candidate, headerAlgorithm)
            if (decompressed != null) {
                val processed = processRawBatch(decompressed, direction) ?: return null
                val recompressed = recompress(processed, headerAlgorithm)
                return byteArrayOf(body[0]) + recompressed
            }
            // Header olmadığı anlaşıldı — tüm body'yi dene
        }

        // Header yok — tüm body sıkıştırılmış veri
        val decompressed = tryDecompress(body, compressionAlgorithm)
        if (decompressed != null) {
            val processed = processRawBatch(decompressed, direction) ?: return null
            return recompress(processed, compressionAlgorithm)
        }

        // Hiç decompress edilemedi — sıkıştırılmamış raw batch olarak dene (geçiş dönemi)
        OverlayLogger.w(TAG, "Sıkıştırma başarısız — raw batch olarak işleniyor (${body.size}B)")
        return processRawBatch(body, direction)
    }

    /** Verilen algoritmaya göre decompress dener; başarısızsa null döner. */
    private fun tryDecompress(input: ByteArray, algorithm: Int): ByteArray? {
        return when (algorithm) {
            1 -> try { decompressSnappy(input) } catch (_: Exception) { null }
            else -> zlibInflateSafe(input)
        }
    }

    /** Verilen algoritmaya göre recompress eder. */
    private fun recompress(input: ByteArray, algorithm: Int): ByteArray {
        return when (algorithm) {
            1 -> try { compressSnappy(input) } catch (_: Exception) { zlibDeflate(input) }
            else -> zlibDeflate(input)
        }
    }

    // detectCompressionHeader kaldırıldı — artık processCompressedBatch içinde inline yapılıyor.

    private fun zlibInflateSafe(input: ByteArray): ByteArray? {
        // Önce zlib wrapper ile dene (0x78 0x9C / 0x78 0xDA header içerir)
        try {
            val inf = java.util.zip.Inflater(false)
            inf.setInput(input)
            val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
            inf.end()
            val result = out.toByteArray()
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {}

        // Sonra raw deflate ile dene (nowrap=true)
        try {
            val inf = java.util.zip.Inflater(true)
            inf.setInput(input)
            val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
            inf.end()
            val result = out.toByteArray()
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {}

        return null
    }

    private fun zlibInflate(input: ByteArray): ByteArray {
        return zlibInflateSafe(input) ?: throw java.util.zip.DataFormatException("Tüm zlib stratejileri başarısız")
    }

    private fun zlibDeflate(input: ByteArray): ByteArray {
        val def = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        def.setInput(input); def.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!def.finished()) { val n = def.deflate(buf); if (n > 0) out.write(buf, 0, n) }
        def.end(); return out.toByteArray()
    }

    private fun decompressSnappy(input: ByteArray): ByteArray = try {
        org.iq80.snappy.Snappy.uncompress(input, 0, input.size)
    } catch (e: Exception) {
        OverlayLogger.w(TAG, "Snappy başarısız → zlib: ${e.message}")
        zlibInflate(input)
    }

    private fun compressSnappy(input: ByteArray): ByteArray = try {
        org.iq80.snappy.Snappy.compress(input)
    } catch (e: Exception) {
        OverlayLogger.w(TAG, "Snappy compress başarısız → zlib: ${e.message}")
        zlibDeflate(input)
    }

    // ── Tek paket ────────────────────────────────────────────────────────

    private fun processSinglePacket(data: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (data.isEmpty()) return data
        val packetId = readVarInt(ByteArrayInputStream(data))

        // ✅ KRİTİK FIX: NETWORK_SETTINGS = 0x8F artık BedrockPacketIds'de tanımlı
        if (packetId == BedrockPacketIds.NETWORK_SETTINGS &&
            direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            try {
                val idSize = writeVarInt(packetId).size
                // Format: [uint16 LE threshold] [uint16 LE algorithm]
                if (data.size >= idSize + 4) {
                    compressionAlgorithm = (data[idSize + 2].toInt() and 0xFF) or
                                           ((data[idSize + 3].toInt() and 0xFF) shl 8)
                    if (compressionAlgorithm > 1) compressionAlgorithm = 0
                }
                compressionEnabled = true
                OverlayLogger.i(TAG, "NetworkSettings → ${if (compressionAlgorithm == 1) "Snappy" else "zlib"} sıkıştırma aktif")
            } catch (_: Exception) {
                compressionEnabled   = true
                compressionAlgorithm = 0
            }
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

    // ── Yardımcılar ──────────────────────────────────────────────────────

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
