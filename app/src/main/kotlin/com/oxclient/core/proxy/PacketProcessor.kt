package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.ui.overlay.OverlayLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PacketProcessor
 *
 * ✅ FIX (KRİTİK): Bedrock 1.16.220+ şifreleme AES-256-GCM kullanıyor, CFB8 değil.
 *
 * Gerçek Bedrock şifreleme protokolü (PrismarineJS/bedrock-protocol doğrulandı):
 *
 * ŞIFRELEME (C→S):
 *   1. Paketi sıkıştır (zlib raw deflate)
 *   2. Checksum = SHA256(counter_LE8 + compressed + secretKey)[0:8]
 *   3. payload = compressed + checksum
 *   4. AES-256-GCM ile şifrele (IV = secretKey[0:12], 12 byte)
 *
 * ŞIFRE ÇÖZME (S→C):
 *   1. AES-256-GCM ile çöz
 *   2. Son 8 byte = checksum → at
 *   3. Kalan = sıkıştırılmış veri → inflate
 *
 * NOT: Bedrock 1.16.220 öncesi CFB8 kullanıyordu. 2b2tpe Bedrock 1.21.x
 *      sunucusu, dolayısıyla GCM.
 *
 * NOT: GCM modu Android'de javax.crypto.Cipher ile destekleniyor
 *      ama "streaming" (parça parça doFinal) desteklenmiyor.
 *      Her paket ayrı bir Cipher instance'ı ile işlenmeli.
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
        secretKeyBytes   = keyBytes.copyOf()
        sendCounter      = 0L
        recvCounter      = 0L
        encryptionEnabled = true
        OverlayLogger.i(TAG, "✅ Şifreleme aktif (AES-256-GCM, key=${keyBytes.size}B)")
    }

    fun resetEncryption() {
        encryptionEnabled = false
        secretKeyBytes    = null
        sendCounter       = 0L
        recvCounter       = 0L
        OverlayLogger.d(TAG, "Şifreleme sıfırlandı")
    }

    fun reset() {
        compressionEnabled   = false
        compressionAlgorithm = 0
        resetEncryption()
        OverlayLogger.d(TAG, "PacketProcessor sıfırlandı")
    }

    // ── AES-256-GCM şifre çözme (S→C) ───────────────────────────────────
    //
    // Bedrock GCM'i tag-less modda kullanıyor (authentication tag yok).
    // IV = secretKey[0:12] — her paket için aynı IV, farklı counter yok.
    // Bu biraz alışılmadık ama PrismarineJS kodunda böyle:
    //   createCipheriv('aes-256-gcm', secret, iv.slice(0, 12))
    // ve GCM tag boyutu 0 (setAuthTagLength(0) eşdeğeri).
    //
    // Android'de GCM "no-auth-tag" modu: GCMParameterSpec(0, iv) ÇALIŞMIYOR.
    // Alternatif: CTR modunu simüle et — GCM'in CTR kısmı ile özdeş.
    // AES-256-CTR, IV = secretKey[0:16] (ilk 16 byte, counter=0).
    //
    // PrismarineJS iv.slice(0, 12) kullanıyor ama Android AES-CTR 16 byte IV istiyor.
    // Çözüm: iv[0:12] + [0,0,0,1] → standard GCM counter block formatı.

    private fun gcmDecrypt(data: ByteArray): ByteArray {
        val key = secretKeyBytes ?: return data
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            // IV: secretKey'in ilk 12 byte'ı
            val iv = key.copyOf(12)
            // GCM tag length 0 → Android desteklemiyor
            // CTR ile eşdeğer: IV = iv[0:12] + 00000001 (big endian counter=1)
            val ctrIv = ByteArray(16)
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

    // ── AES-256-GCM şifreleme (C→S inject) ──────────────────────────────

    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = secretKeyBytes ?: return plaintext
        return try {
            // Checksum = SHA256(counter_LE8 + plaintext + secretKey)[0:8]
            val checksum = computeChecksum(plaintext, sendCounter, key)
            sendCounter++

            val payload = plaintext + checksum

            val secretKey = SecretKeySpec(key, "AES")
            val iv = key.copyOf(12)
            val ctrIv = ByteArray(16)
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
        // counter: little-endian 64-bit
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
            // S→C: önce şifre çöz
            val decrypted = if (encryptionEnabled &&
                               direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
                val dec = gcmDecrypt(body)
                // Son 8 byte checksum — at
                if (dec.size > 8) dec.copyOf(dec.size - 8) else dec
            } else {
                body
            }

            val result = if (compressionEnabled) processCompressedBatch(decrypted, direction)
                         else                    processRawBatch(decrypted, direction)

            // C→S inject: sıkıştır + şifrele
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
        return try {
            // Bedrock 1.20+: sıkıştırılmış batch'in başında 1 byte compression header var
            // 0x00 = zlib, 0xFF = no compression, 0x01 = snappy
            val (hasHeader, dataOffset) = detectCompressionHeader(body)
            val compressed = if (hasHeader) body.copyOfRange(dataOffset, body.size) else body

            val decompressed = when (compressionAlgorithm) {
                1    -> decompressSnappy(compressed)
                else -> zlibInflate(compressed)
            }
            val processed = processRawBatch(decompressed, direction) ?: return null
            val recompressed = when (compressionAlgorithm) {
                1    -> compressSnappy(processed)
                else -> zlibDeflate(processed)
            }
            if (hasHeader) {
                byteArrayOf(body[0]) + recompressed
            } else {
                recompressed
            }
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Sıkıştırma hatası: ${e.message}")
            body
        }
    }

    /**
     * Bedrock 1.20.60+ batch başında 1 byte compression type header ekliyor.
     * 0x78 zlib magic değil, header byte'ı olabilir.
     * Güvenli tespit: byte değeri 0x00, 0x01, 0xFF ise header.
     */
    private fun detectCompressionHeader(body: ByteArray): Pair<Boolean, Int> {
        if (body.isEmpty()) return false to 0
        val first = body[0].toInt() and 0xFF
        return if (first == 0x00 || first == 0x01 || first == 0xFF) true to 1
        else false to 0
    }

    private fun zlibInflate(input: ByteArray): ByteArray {
        val inf = java.util.zip.Inflater(true)  // raw deflate (no zlib header)
        inf.setInput(input)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
        } catch (e: Exception) {
            // raw deflate başarısız olursa zlib wrapper'lı dene
            val inf2 = java.util.zip.Inflater(false)
            inf2.setInput(input)
            val out2 = ByteArrayOutputStream()
            while (!inf2.finished()) {
                val n = inf2.inflate(buf)
                if (n == 0) break
                out2.write(buf, 0, n)
            }
            inf2.end()
            return out2.toByteArray()
        }
        inf.end()
        return out.toByteArray()
    }

    private fun zlibDeflate(input: ByteArray): ByteArray {
        val def = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)  // raw
        def.setInput(input); def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) {
            val n = def.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    private fun decompressSnappy(input: ByteArray): ByteArray = try {
        org.iq80.snappy.Snappy.uncompress(input, 0, input.size)
    } catch (e: Exception) {
        OverlayLogger.w(TAG, "Snappy başarısız, zlib: ${e.message}")
        zlibInflate(input)
    }

    private fun compressSnappy(input: ByteArray): ByteArray = try {
        org.iq80.snappy.Snappy.compress(input)
    } catch (e: Exception) {
        OverlayLogger.w(TAG, "Snappy compress başarısız, zlib: ${e.message}")
        zlibDeflate(input)
    }

    private fun processSinglePacket(data: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (data.isEmpty()) return data
        val packetId = readVarInt(ByteArrayInputStream(data))

        if (packetId == BedrockPacketIds.NETWORK_SETTINGS &&
            direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            try {
                val idSize = writeVarInt(packetId).size
                // NetworkSettings: [threshold uint16LE] [algorithm uint16LE]
                if (data.size >= idSize + 4) {
                    compressionAlgorithm = (data[idSize + 2].toInt() and 0xFF) or
                                           ((data[idSize + 3].toInt() and 0xFF) shl 8)
                    // 0=zlib, 1=snappy, 2=none
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
