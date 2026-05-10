package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.ui.overlay.OverlayLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * PacketProcessor
 *
 * Diğer AI'nın verdiği "otomatik sıkıştırma tespiti" kodu 2 nedenden yanlış:
 *
 * 1) firstByte == 0x78 ise zlib aktif ediyordu.
 *    0x78 ham batch'te sıradan bir varint length byte'ı olabilir (120 byte'lık paket).
 *    Bu heuristic yanlış pozitif üretir → ham paket inflate edilmeye çalışılır
 *    → exception → fallback yine compression açar → sonsuz döngü / bağlantı kopması.
 *
 * 2) NetworkSettings algoritma okuma offset'i hatalıydı.
 *    NetworkSettings (0xC7) formatı:
 *      [packetId varint=2B] [threshold uint16LE=2B] [algorithm uint16LE=2B]
 *    Algoritmanın düşük byte'ı: data[idSize + 2]
 *    Önceki kodda idSize+2 ve idSize+3 LE birleştirme yapıyordu — gereksiz ve
 *    büyük değerlerde yanlış sonuç verebilirdi.
 *
 * DOĞRU: Sıkıştırma durumu YALNIZCA NetworkSettings paketinden okunur.
 *
 * ✅ FIX (KRİTİK): Bedrock şifreleme (AES-256-CFB8) desteği eklendi.
 *    2b2t gibi online sunucularda ServerToClientHandshake (0x03) paketi
 *    geldikten sonra TÜM paketler şifreli gelir.
 *    Şifre çözülmeden PacketProcessor ham byte okur → hiçbir şey parse edilemez
 *    → selfRuntimeId=0, konum=0,0,0 → modüller çalışmaz.
 */
object PacketProcessor {

    private const val TAG = "PacketProcessor"

    @Volatile var compressionEnabled   = false
    @Volatile var compressionAlgorithm = 0     // 0 = zlib, 1 = snappy

    // ✅ FIX: AES-256-CFB8 şifreleme state'i
    @Volatile var encryptionEnabled    = false
    private var decryptCipher: javax.crypto.Cipher? = null
    private var encryptCipher: javax.crypto.Cipher? = null
    @Volatile private var sendCounter  = 0L
    @Volatile private var recvCounter  = 0L
    private lateinit var secretKey: javax.crypto.SecretKey

    fun enableEncryption(secretKeyBytes: ByteArray) {
        try {
            secretKey = javax.crypto.spec.SecretKeySpec(secretKeyBytes, "AES")
            val iv = secretKeyBytes.copyOf(16)
            val ivSpec = javax.crypto.spec.IvParameterSpec(iv)

            decryptCipher = javax.crypto.Cipher.getInstance("AES/CFB8/NoPadding").apply {
                init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, ivSpec)
            }
            encryptCipher = javax.crypto.Cipher.getInstance("AES/CFB8/NoPadding").apply {
                init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            }
            sendCounter = 0L
            recvCounter = 0L
            encryptionEnabled = true
            OverlayLogger.i(TAG, "✅ Şifreleme aktif (AES-256-CFB8)")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Şifreleme başlatma hatası: ${e.message}", e)
        }
    }

    fun resetEncryption() {
        encryptionEnabled = false
        decryptCipher = null
        encryptCipher = null
        sendCounter = 0L
        recvCounter = 0L
        OverlayLogger.d(TAG, "Şifreleme sıfırlandı")
    }

    fun reset() {
        compressionEnabled = false
        compressionAlgorithm = 0
        resetEncryption()
        OverlayLogger.d(TAG, "PacketProcessor sıfırlandı")
    }

    /** Sunucudan gelen veriyi çöz (S→C) */
    private fun decrypt(data: ByteArray): ByteArray {
        return decryptCipher?.doFinal(data) ?: data
    }

    /** Sunucuya gönderilecek veriyi şifrele (C→S inject) */
    fun encrypt(data: ByteArray): ByteArray {
        return encryptCipher?.doFinal(data) ?: data
    }

    fun processBatch(body: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (body.isEmpty()) return body
        return try {
            val decrypted = if (encryptionEnabled && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
                decrypt(body)
            } else {
                body
            }
            val result = if (compressionEnabled) processCompressedBatch(decrypted, direction)
                         else                    processRawBatch(decrypted, direction)
            if (encryptionEnabled && direction == PacketEvent.Direction.CLIENT_TO_SERVER && result != null) {
                encrypt(result)
            } else {
                result
            }
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Batch işleme hatası, orijinal iletiliyor: ${e.message}")
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
        return try {
            val decompressed = when (compressionAlgorithm) {
                1    -> decompressSnappy(body)
                else -> zlibInflate(body)
            }
            val processed = processRawBatch(decompressed, direction) ?: return null
            when (compressionAlgorithm) {
                1    -> compressSnappy(processed)
                else -> zlibDeflate(processed)
            }
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Sıkıştırma hatası: ${e.message}")
            body
        }
    }

    private fun zlibInflate(input: ByteArray): ByteArray {
        val inf = java.util.zip.Inflater(); inf.setInput(input)
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
        inf.end(); return out.toByteArray()
    }

    private fun zlibDeflate(input: ByteArray): ByteArray {
        val def = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION)
        def.setInput(input); def.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!def.finished()) { val n = def.deflate(buf); if (n > 0) out.write(buf, 0, n) }
        def.end(); return out.toByteArray()
    }

    private fun decompressSnappy(input: ByteArray): ByteArray = try {
        org.iq80.snappy.Snappy.uncompress(input, 0, input.size)
    } catch (e: Exception) {
        OverlayLogger.w(TAG, "Snappy başarısız, zlib: ${e.message}"); zlibInflate(input)
    }

    private fun compressSnappy(input: ByteArray): ByteArray = try {
        org.iq80.snappy.Snappy.compress(input)
    } catch (e: Exception) {
        OverlayLogger.w(TAG, "Snappy compress başarısız, zlib: ${e.message}"); zlibDeflate(input)
    }

    private fun processSinglePacket(data: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (data.isEmpty()) return data
        val packetId = readVarInt(ByteArrayInputStream(data))

        // NetworkSettings (0xC7) — sıkıştırmayı aktif et
        if (packetId == 0xC7 && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            try {
                val idSize = writeVarInt(packetId).size
                compressionAlgorithm = if (data.size >= idSize + 3) {
                    data[idSize + 2].toInt() and 0xFF
                } else 0
                compressionEnabled = true
                OverlayLogger.i(TAG, "NetworkSettings → ${if (compressionAlgorithm == 1) "Snappy" else "zlib"} sıkıştırma aktif")
            } catch (_: Exception) {
                compressionEnabled = true; compressionAlgorithm = 0
            }
        }

        // ✅ FIX: EntityTracker.onPacket() direkt ÇAĞRILMIYOR.
        // EntityTracker, PacketEventBus'a register() ile kayıt olduğundan
        // publish() çağrısı zaten EntityTracker.onPacket()'i tetikler.
        // Önceki kod: hem direkt hem EventBus → her paket 2 kez işleniyordu
        // → StartGame 2x, AddPlayer 2x, parse hataları 2x görünüyordu.
        val event = PacketEvent(packetId, data, direction)
        try { PacketEventBus.publish(event) } catch (e: Exception) {
            OverlayLogger.e(TAG, "EventBus hatası pkt=0x${packetId.toString(16)}: ${e.message}", e)
        }

        return when {
            event.isCancelled          -> null
            event.modifiedData != null -> event.modifiedData
            else                       -> data
        }
    }

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
        do { var b = v and 0x7F; v = v ushr 7; if (v != 0) b = b or 0x80; out.write(b) } while (v != 0)
    }

    fun writeVarInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream(4); writeVarInt(out, value); return out.toByteArray()
    }
}
