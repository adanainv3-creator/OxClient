package com.oxclient.proxy

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * BedrockPipeline
 *
 * Bedrock 0xFE (Game/Batch) paketleri için:
 *  1. zlib inflate/deflate
 *  2. AES-256-CFB8 şifreleme (el sıkışma sonrası)
 *
 * Sıkıştırma eşiği: NetworkSettings'te belirlenir (varsayılan 512 byte → ZLIB).
 * Protokol 748'de sıkıştırma türü byte'ı paketin başındadır:
 *   0x00 = NONE, 0x01 = ZLIB, 0x02 = SNAPPY
 */
object BedrockPipeline {

    // ── Sıkıştırma tipleri ────────────────────────────────────────────────
    const val COMPRESS_NONE   : Byte = 0x00
    const val COMPRESS_ZLIB   : Byte = 0x01
    const val COMPRESS_SNAPPY : Byte = 0x02

    // ── Şifreleme durumu ──────────────────────────────────────────────────
    @Volatile private var encryptionEnabled = false
    private var encryptCipher : Cipher?    = null
    private var decryptCipher : Cipher?    = null

    // ─────────────────────────────────────────────────────────────────────
    //  DECODE: ham 0xFE payload → iç paketler listesi
    // ─────────────────────────────────────────────────────────────────────

    /**
     * @param payload   0xFE sonrasındaki ham baytlar (şifrelenmiş olabilir)
     * @return  İçindeki Bedrock paketlerinin ham baytları (her biri VarInt-uzunluklu)
     */
    fun decode(payload: ByteArray): List<ByteArray> {
        var data = payload

        // 1. AES decrypt
        if (encryptionEnabled && decryptCipher != null) {
            data = decryptCipher!!.update(data) ?: return emptyList()
        }

        // 2. Sıkıştırma tipini oku (protokol 649+)
        if (data.isEmpty()) return emptyList()
        val compressType = data[0]
        val compressed   = data.copyOfRange(1, data.size)

        // 3. Decompress
        val decompressed = when (compressType) {
            COMPRESS_ZLIB   -> zlibInflate(compressed)
            COMPRESS_NONE   -> compressed
            else            -> compressed   // SNAPPY — gerekirse eklenebilir
        }

        // 4. İç paketleri VarInt-uzunluk önekine göre ayır
        return splitBatchPackets(decompressed)
    }

    /**
     * @param packets   Göndermek istenen iç paket bayt dizileri
     * @return  Hazır 0xFE payload (sıkıştırılmış + şifrelenmiş)
     */
    fun encode(packets: List<ByteArray>, compress: Boolean = true): ByteArray {
        // 1. VarInt-length prefix ile birleştir
        val combined = buildBatch(packets)

        // 2. Sıkıştır
        val (compressType, compressed) = if (compress && combined.size >= 512) {
            COMPRESS_ZLIB to zlibDeflate(combined)
        } else {
            COMPRESS_NONE to combined
        }

        // 3. Tip byte + payload
        val withType = ByteArray(1 + compressed.size)
        withType[0]  = compressType
        compressed.copyInto(withType, 1)

        // 4. AES encrypt
        return if (encryptionEnabled && encryptCipher != null) {
            encryptCipher!!.update(withType) ?: withType
        } else withType
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ŞİFRELEME KURULUMU (ServerToClientHandshake)
    // ─────────────────────────────────────────────────────────────────────

    fun enableEncryption(key: SecretKey, iv: ByteArray) {
        val spec = IvParameterSpec(iv)
        encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, key, spec) }
        decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding").also { it.init(Cipher.DECRYPT_MODE, key, spec) }
        encryptionEnabled = true
    }

    fun disableEncryption() {
        encryptionEnabled = false
        encryptCipher = null
        decryptCipher = null
    }

    val isEncrypted get() = encryptionEnabled

    // ─────────────────────────────────────────────────────────────────────
    //  ZLIB
    // ─────────────────────────────────────────────────────────────────────

    private fun zlibDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(data.size + 64)
        val len = deflater.deflate(buf)
        deflater.end()
        return buf.copyOf(len)
    }

    private fun zlibInflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = mutableListOf<Byte>()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count == 0) break
            repeat(count) { out.add(buf[it]) }
        }
        inflater.end()
        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BATCH YÖNETİMİ
    // ─────────────────────────────────────────────────────────────────────

    private fun buildBatch(packets: List<ByteArray>): ByteArray {
        val buf = Unpooled.buffer()
        for (pkt in packets) {
            writeVarInt(buf, pkt.size)
            buf.writeBytes(pkt)
        }
        val bytes = ByteArray(buf.readableBytes())
        buf.readBytes(bytes)
        buf.release()
        return bytes
    }

    private fun splitBatchPackets(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        val buf = Unpooled.wrappedBuffer(data)
        try {
            while (buf.isReadable) {
                val len = readVarInt(buf)
                if (len <= 0 || len > buf.readableBytes()) break
                val pkt = ByteArray(len)
                buf.readBytes(pkt)
                result.add(pkt)
            }
        } catch (_: Exception) { /* kısmi son paket */ }
        finally { buf.release() }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────
    //  VARINT YARDIMCILARI
    // ─────────────────────────────────────────────────────────────────────

    private fun writeVarInt(buf: ByteBuf, value: Int) {
        var v = value
        while (v and -0x80 != 0) {
            buf.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        buf.writeByte(v)
    }

    private fun readVarInt(buf: ByteBuf): Int {
        var result = 0
        var shift = 0
        while (buf.isReadable) {
            val b = buf.readByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 35) throw IllegalStateException("VarInt too large")
        }
        return result
    }
}
