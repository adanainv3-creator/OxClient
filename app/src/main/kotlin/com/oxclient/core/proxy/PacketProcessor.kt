package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
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
    // Bedrock şifrelemesi: her yön için ayrı cipher, counter mod (send counter / receive counter)
    @Volatile var encryptionEnabled    = false
    private var decryptCipher: javax.crypto.Cipher? = null  // sunucu→biz
    private var encryptCipher: javax.crypto.Cipher? = null  // biz→sunucu
    @Volatile private var sendCounter  = 0L
    @Volatile private var recvCounter  = 0L
    private lateinit var secretKey: javax.crypto.SecretKey

    /**
     * ServerToClientHandshake JWT'sinden şifreli oturumu başlat.
     * WClient'in OnlineLoginPacketListener.beforeServerBound içinde yaptığının aynısı.
     *
     * jwt: ServerToClientHandshake paketinin içindeki JWT string
     * clientPrivateKey: Login sırasında oluşturulan EC private key (Base64)
     */
    fun enableEncryption(secretKeyBytes: ByteArray) {
        try {
            secretKey = javax.crypto.spec.SecretKeySpec(secretKeyBytes, "AES")
            // Bedrock CFB8: IV = secretKey'in ilk 16 byte'ı
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
            Log.i(TAG, "✅ Şifreleme aktif (AES-256-CFB8)")
        } catch (e: Exception) {
            Log.e(TAG, "Şifreleme başlatma hatası: ${e.message}")
        }
    }

    fun resetEncryption() {
        encryptionEnabled = false
        decryptCipher = null
        encryptCipher = null
        sendCounter = 0L
        recvCounter = 0L
    }

    fun reset() {
        compressionEnabled = false
        compressionAlgorithm = 0
        resetEncryption()
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
            // ✅ FIX: Sunucudan gelen veri şifrelenmiş olabilir — önce çöz
            val decrypted = if (encryptionEnabled && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
                decrypt(body)
            } else {
                body
            }
            val result = if (compressionEnabled) processCompressedBatch(decrypted, direction)
                         else                    processRawBatch(decrypted, direction)
            // ✅ FIX: Sunucuya enjekte edilecek paketleri şifrele
            if (encryptionEnabled && direction == PacketEvent.Direction.CLIENT_TO_SERVER && result != null) {
                encrypt(result)
            } else {
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Batch işleme hatası, orijinal iletiliyor: ${e.message}")
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
            Log.w(TAG, "Sıkıştırma hatası: ${e.message}")
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
        Log.w(TAG, "Snappy başarısız, zlib: ${e.message}"); zlibInflate(input)
    }

    private fun compressSnappy(input: ByteArray): ByteArray = try {
        org.iq80.snappy.Snappy.compress(input)
    } catch (e: Exception) {
        Log.w(TAG, "Snappy compress başarısız, zlib: ${e.message}"); zlibDeflate(input)
    }

    private fun processSinglePacket(data: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (data.isEmpty()) return data
        val packetId = readVarInt(ByteArrayInputStream(data))

        // NetworkSettings (0xC7) — sıkıştırmayı aktif et
        // Format: [packetId varint] [threshold uint16LE] [algorithm uint16LE]
        // Sıkıştırma bilgisi SADECE buradan okunur, başka hiçbir heuristik yok.
        if (packetId == 0xC7 && direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            try {
                val idSize = writeVarInt(packetId).size  // 0xC7 → varint 2 byte
                // algorithm düşük byte'ı: data[idSize + 2]
                compressionAlgorithm = if (data.size >= idSize + 3) {
                    data[idSize + 2].toInt() and 0xFF
                } else 0
                compressionEnabled = true
                Log.i(TAG, "NetworkSettings → ${if (compressionAlgorithm == 1) "Snappy" else "zlib"}")
            } catch (_: Exception) {
                compressionEnabled = true; compressionAlgorithm = 0
            }
        }

        // EntityTracker — HER İKİ YÖN (C→S de dahil, PLAYER_AUTH_INPUT için)
        try { EntityTracker.onPacket(PacketEvent(packetId, data, direction)) } catch (_: Exception) {}

        val event = PacketEvent(packetId, data, direction)
        try { PacketEventBus.publish(event) } catch (e: Exception) {
            Log.e(TAG, "EventBus hatası pkt=0x${packetId.toString(16)}: ${e.message}")
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
