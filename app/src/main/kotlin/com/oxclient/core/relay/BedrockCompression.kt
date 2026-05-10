package com.oxclient.core.relay

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * BedrockCompression
 *
 * Bedrock 1.16.220+ sıkıştırma katmanı.
 *
 * ── FORMAT (Bedrock 1.20+ batch body) ────────────────────────────────────
 * Şifreli değilse:
 *   [1 byte algorithm header] [sıkıştırılmış veri]
 *
 * Algorithm header:
 *   0x00 = raw deflate (zlib nowrap=true)
 *   0x01 = Snappy
 *   0xFF = sıkıştırma yok (raw batch body direkt gelir)
 *
 * Şifreliyse: header yok, direkt sıkıştırılmış veri.
 *
 * ── NEDEN ESKİ KOD SORUNLUYDU ────────────────────────────────────────────
 * PacketHelper.wrapBatch() sıkıştırılmış veriye algorithm header eklemiyor.
 * Sunucu 1.20+ beklediğinde header olmayan paketi reddediyor.
 * Ayrıca eski kod nowrap=false (zlib wrapper 0x78) kullanıyordu;
 * Bedrock raw deflate (nowrap=true) bekler.
 *
 * ── WClient/CloudburstMC Referans ────────────────────────────────────────
 * BatchHandler.java → CompressionConfig → ZlibCompressor/SnappyCompressor
 */
object BedrockCompression {

    private const val TAG = "BedrockCompression"

    const val ALG_ZLIB   = 0x00
    const val ALG_SNAPPY = 0x01
    const val ALG_NONE   = 0xFF

    /**
     * Batch body'yi decompress et.
     * [algorithm byte][compressed data] formatını bekler.
     *
     * @param encrypted Şifreleme aktifse true — o zaman algorithm header YOK.
     */
    fun decompress(body: ByteArray, algorithm: Int, encrypted: Boolean): ByteArray {
        if (body.isEmpty()) return body

        if (encrypted) {
            // Şifreli: header yok, direkt sıkıştırılmış veri
            return inflateRaw(body, algorithm)
        }

        if (body.size < 1) return body
        val headerByte = body[0].toInt() and 0xFF

        return when (headerByte) {
            ALG_NONE -> {
                if (body.size > 1) body.copyOfRange(1, body.size) else ByteArray(0)
            }
            ALG_ZLIB -> {
                val data = body.copyOfRange(1, body.size)
                inflateRawDeflate(data)
            }
            ALG_SNAPPY -> {
                val data = body.copyOfRange(1, body.size)
                snappyDecompress(data)
            }
            else -> {
                // Eski format veya header yok — tüm body'yi dene
                inflateRawDeflate(body)
            }
        }
    }

    /**
     * Batch body'yi compress et.
     * Sonuç: [algorithm byte][compressed data]
     *
     * @param encrypted Şifreleme aktifse true — algorithm header EKLEME.
     */
    fun compress(body: ByteArray, algorithm: Int, encrypted: Boolean): ByteArray {
        if (body.isEmpty()) return body

        val compressed = when (algorithm) {
            ALG_SNAPPY -> try {
                snappyCompress(body)
            } catch (e: Exception) {
                Log.w(TAG, "Snappy compress başarısız → zlib: ${e.message}")
                deflateRaw(body)
            }
            else -> deflateRaw(body)
        }

        return if (encrypted) {
            // Şifreli: header ekleme
            compressed
        } else {
            // Header byte ekle
            val algByte = if (algorithm == ALG_SNAPPY) ALG_SNAPPY else ALG_ZLIB
            byteArrayOf(algByte.toByte()) + compressed
        }
    }

    // ── Raw deflate (nowrap=true) — Bedrock standardı ────────────────────

    fun deflateRaw(input: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true) // nowrap=true
        def.setInput(input)
        def.finish()
        val out = ByteArrayOutputStream(input.size)
        val buf = ByteArray(8192)
        while (!def.finished()) {
            val n = def.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    fun inflateRawDeflate(input: ByteArray): ByteArray {
        // 1. nowrap=true (raw deflate)
        try {
            val inf = Inflater(true)
            inf.setInput(input)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inf.end()
            val result = out.toByteArray()
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {}

        // 2. nowrap=false (zlib wrapper — eski sunucular)
        try {
            val inf = Inflater(false)
            inf.setInput(input)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inf.end()
            val result = out.toByteArray()
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {}

        Log.w(TAG, "inflate başarısız — ham veri döndürülüyor (${input.size}B)")
        return input
    }

    private fun inflateRaw(input: ByteArray, algorithm: Int): ByteArray {
        return when (algorithm) {
            ALG_SNAPPY -> snappyDecompress(input)
            else -> inflateRawDeflate(input)
        }
    }

    // ── Snappy ───────────────────────────────────────────────────────────

    private fun snappyDecompress(input: ByteArray): ByteArray = try {
        org.iq80.snappy.Snappy.uncompress(input, 0, input.size)
    } catch (e: Exception) {
        Log.w(TAG, "Snappy decompress başarısız → zlib: ${e.message}")
        inflateRawDeflate(input)
    }

    private fun snappyCompress(input: ByteArray): ByteArray =
        org.iq80.snappy.Snappy.compress(input)
}
