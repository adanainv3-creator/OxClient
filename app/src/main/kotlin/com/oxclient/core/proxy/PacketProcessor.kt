package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    fun process(raw: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (raw.isEmpty()) return raw

        val firstByte = raw[0].toInt() and 0xFF

        return when {
            firstByte == BedrockPacketIds.RAKNET_ACK ||
            firstByte == BedrockPacketIds.RAKNET_NACK -> raw

            firstByte in 0x80..0x8F -> processFrameSet(raw, direction)

            else -> raw
        }
    }

    // ── Frame Set İşleme ──────────────────────────────────────────────────

    private fun processFrameSet(raw: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (raw.size < 4) return raw

        val flags       = raw[0]
        val seqNumBytes = raw.copyOfRange(1, 4)
        val buf         = ByteBuffer.wrap(raw, 4, raw.size - 4).order(ByteOrder.BIG_ENDIAN)

        val newFrames = mutableListOf<ByteArray>()
        var anyChanged = false

        while (buf.hasRemaining()) {
            if (buf.remaining() < 3) break

            val frameStart  = buf.position() // buf içindeki pozisyon (0-bazlı)
            val reliability = buf.get().toInt() and 0xFF
            val oldLengthBits = ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
            val oldPayloadLen = oldLengthBits / 8

            val headersStart = buf.position() // reliability header'larının başlangıcı
            skipReliabilityHeaders(buf, reliability)
            val headersEnd    = buf.position()
            val headersLen    = headersEnd - frameStart // reliability byte + length bytes + extra headers

            if (buf.remaining() < oldPayloadLen) {
                buf.position(frameStart)
                break
            }

            val payload = ByteArray(oldPayloadLen)
            buf.get(payload)

            // Minecraft batch payload'unu işle
            val processed = processBatchPayload(payload, direction)

            if (processed == null) {
                // Paket iptal edildi → frame'i tamamen atla
                anyChanged = true
                Log.d(TAG, "Frame iptal edildi")
                continue
            }

            val newPayloadLen = processed.size
            val newLengthBits = newPayloadLen * 8

            if (!processed.contentEquals(payload) || newPayloadLen != oldPayloadLen) {
                anyChanged = true
            }

            // Frame'i yeniden inşa et: değişmişse yeni length, aynı header'lar, yeni payload
            val frameOut = ByteArrayOutputStream()
            frameOut.write(reliability)
            frameOut.write((newLengthBits shr 8) and 0xFF)
            frameOut.write(newLengthBits and 0xFF)
            // Orijinal ek header'ları kopyala (reliable/sequenced/ordered/fragment)
            if (headersLen > 3) {
                frameOut.write(raw, 4 + frameStart + 3, headersLen - 3)
            }
            frameOut.write(processed)
            newFrames.add(frameOut.toByteArray())
        }

        if (newFrames.isEmpty()) return null
        if (!anyChanged) return raw

        val out = ByteArrayOutputStream()
        out.write(flags.toInt())
        out.write(seqNumBytes)
        newFrames.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun skipReliabilityHeaders(buf: ByteBuffer, reliability: Int) {
        val relType = reliability shr 5

        if (relType in intArrayOf(2, 3, 4, 6, 7)) {
            if (buf.remaining() >= 3) buf.position(buf.position() + 3)
        }
        if (relType in intArrayOf(1, 4)) {
            if (buf.remaining() >= 3) buf.position(buf.position() + 3)
        }
        if (relType in intArrayOf(3, 4, 5, 6, 7)) {
            if (buf.remaining() >= 4) buf.position(buf.position() + 4)
        }
        if ((reliability and 0x10) != 0 && buf.remaining() >= 10) {
            buf.position(buf.position() + 10)
        }
    }

    // ── Batch Payload İşleme ──────────────────────────────────────────────

    private fun processBatchPayload(payload: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (payload.isEmpty()) return payload

        if (payload[0] != 0xFE.toByte()) return payload

        val inner = payload.copyOfRange(1, payload.size)
        val stream    = ByteArrayInputStream(inner)
        val outParts  = mutableListOf<ByteArray>()
        var anyModified = false

        while (stream.available() > 0) {
            val pktLen = readVarInt(stream)
            if (pktLen <= 0 || stream.available() < pktLen) break

            val pktData = ByteArray(pktLen)
            stream.read(pktData)

            val pktStream = ByteArrayInputStream(pktData)
            val packetId  = readVarInt(pktStream)

            val event = PacketEvent(packetId, pktData, direction)

            try {
                PacketEventBus.publish(event)
            } catch (e: Exception) {
                Log.e(TAG, "PacketEventBus publish hatası: ${e.message}")
            }

            when {
                event.isCancelled -> {
                    anyModified = true
                }
                event.modifiedData != null -> {
                    anyModified = true
                    outParts.add(encodeSubPacket(event.modifiedData!!))
                }
                else -> {
                    outParts.add(encodeSubPacket(pktData))
                }
            }
        }

        if (outParts.isEmpty()) return null
        if (!anyModified) return payload

        val newInner = outParts.fold(ByteArray(0)) { acc, b -> acc + b }
        return byteArrayOf(0xFE.toByte()) + newInner
    }

    // ── VarInt ────────────────────────────────────────────────────────────

    fun readVarInt(stream: ByteArrayInputStream): Int {
        var result = 0
        var shift  = 0
        for (i in 0 until 5) {
            val b = stream.read()
            if (b == -1) break
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        return result
    }

    fun writeVarInt(value: Int): ByteArray {
        val buf = mutableListOf<Byte>()
        var v = value
        do {
            var b = (v and 0x7F)
            v = v ushr 7
            if (v != 0) b = b or 0x80
            buf.add(b.toByte())
        } while (v != 0)
        return buf.toByteArray()
    }

    private fun encodeSubPacket(data: ByteArray): ByteArray =
        writeVarInt(data.size) + data
}