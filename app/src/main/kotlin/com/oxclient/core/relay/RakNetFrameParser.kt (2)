package com.oxclient.core.relay

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * RakNetFrameParser
 *
 * RakNet FrameSet (0x80..0x8F) paketlerini parse eder ve yeniden oluşturur.
 *
 * ── NEDEN ESKİ KOD SORUNLUYDU ────────────────────────────────────────────
 * Eski MITMProxy.processFrameSet() reliability header byte'larını
 * hesaplarken `headerExtra` değişkenini yanlış yönetiyordu:
 *   - headerStart = pos (reliability byte'larından SONRA)
 *   - headerExtra = pos - headerStart (her zaman 0 çıkar!)
 * Bu yüzden reliable sequence numaraları ve ordering bilgisi hiç yazılmıyordu.
 * Sonuç: FrameSet yeniden oluşturulduğunda header byte'ları eksik → paket bozuk.
 *
 * ── RakNet Frame Formatı ─────────────────────────────────────────────────
 * [1 byte reliability flags]
 * [2 byte bit length, big-endian]
 * --- reliability'e göre opsiyonel headerlar: ---
 * RELIABLE (2,3,4,6,7):   [3 byte reliableFrameIndex, LE]
 * SEQUENCED (1,4):        [3 byte sequencedFrameIndex, LE]
 * ORDERED (3,4,7):        [3 byte orderedFrameIndex, LE] + [1 byte orderChannel]
 * --- split paket bayrağı: ---
 * isSplit (bit 4 of flags): [4 byte splitCount] + [2 byte splitId] + [4 byte splitIndex]
 * --- payload ---
 * [N bytes payload (bitLen+7)/8]
 */
object RakNetFrameParser {

    private const val TAG = "RakNetFrameParser"

    data class Frame(
        val flags     : Int,        // reliability + isSplit flags byte
        val reliability: Int,       // (flags shr 5) and 0x07
        val isSplit   : Boolean,    // (flags and 0x10) != 0
        val reliableHeader: ByteArray?,   // 3 bytes if reliable
        val sequencedHeader: ByteArray?,  // 3 bytes if sequenced
        val orderedHeader: ByteArray?,    // 4 bytes if ordered (3 + channel)
        val splitHeader: ByteArray?,      // 10 bytes if split
        val payload   : ByteArray
    )

    /**
     * FrameSet içindeki tüm frame'leri parse et.
     * @return frame listesi veya parse edilemezse null
     */
    fun parseFrameSet(raw: ByteArray): List<Frame>? {
        if (raw.size < 4) return null
        // byte 0: frameSet ID (0x80..0x8F)
        // bytes 1-3: sequence number (LE 24-bit)
        var pos = 4
        val frames = mutableListOf<Frame>()

        while (pos < raw.size) {
            val frameStart = pos
            if (pos >= raw.size) break

            val flags       = raw[pos].toInt() and 0xFF; pos++
            val reliability = (flags shr 5) and 0x07
            val isSplit     = (flags and 0x10) != 0

            if (pos + 2 > raw.size) break
            val bitLen  = ((raw[pos].toInt() and 0xFF) shl 8) or (raw[pos + 1].toInt() and 0xFF)
            pos += 2
            val byteLen = (bitLen + 7) / 8

            // Reliable header (3 bytes)
            val reliableHeader: ByteArray? = if (reliability in intArrayOf(2, 3, 4, 6, 7)) {
                if (pos + 3 > raw.size) break
                raw.copyOfRange(pos, pos + 3).also { pos += 3 }
            } else null

            // Sequenced header (3 bytes)
            val sequencedHeader: ByteArray? = if (reliability in intArrayOf(1, 4)) {
                if (pos + 3 > raw.size) break
                raw.copyOfRange(pos, pos + 3).also { pos += 3 }
            } else null

            // Ordered header (3 + 1 = 4 bytes: orderedFrameIndex + orderChannel)
            val orderedHeader: ByteArray? = if (reliability in intArrayOf(3, 4, 7)) {
                if (pos + 4 > raw.size) break
                raw.copyOfRange(pos, pos + 4).also { pos += 4 }
            } else null

            // Split header (10 bytes: splitCount 4B + splitId 2B + splitIndex 4B)
            val splitHeader: ByteArray? = if (isSplit) {
                if (pos + 10 > raw.size) break
                raw.copyOfRange(pos, pos + 10).also { pos += 10 }
            } else null

            // Payload
            if (pos + byteLen > raw.size) {
                Log.w(TAG, "Frame payload sınır dışı: pos=$pos byteLen=$byteLen size=${raw.size}")
                break
            }
            val payload = raw.copyOfRange(pos, pos + byteLen)
            pos += byteLen

            frames.add(Frame(flags, reliability, isSplit, reliableHeader, sequencedHeader, orderedHeader, splitHeader, payload))
        }

        return frames
    }

    /**
     * Frame listesini orijinal FrameSet header ile yeniden oluştur.
     * @param frameSetHeader raw[0..3] — ID + sequence number
     * @param frames modifiye edilmiş frame listesi
     */
    fun buildFrameSet(frameSetHeader: ByteArray, frames: List<Frame>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(frameSetHeader)
        for (frame in frames) {
            writeFrame(out, frame)
        }
        return out.toByteArray()
    }

    private fun writeFrame(out: ByteArrayOutputStream, frame: Frame) {
        out.write(frame.flags)
        val bitLen = frame.payload.size * 8
        out.write((bitLen shr 8) and 0xFF)
        out.write(bitLen and 0xFF)
        frame.reliableHeader?.let  { out.write(it) }
        frame.sequencedHeader?.let { out.write(it) }
        frame.orderedHeader?.let   { out.write(it) }
        frame.splitHeader?.let     { out.write(it) }
        out.write(frame.payload)
    }

    /** Frame'i aynı header'larla ama farklı payload ile kopyala */
    fun Frame.withPayload(newPayload: ByteArray) = Frame(
        flags, reliability, isSplit,
        reliableHeader, sequencedHeader, orderedHeader, splitHeader,
        newPayload
    )
}
