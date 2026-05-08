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

    private fun processFrameSet(raw: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (raw.size < 4) return raw

        val flags       = raw[0]
        val seqNumBytes = raw.copyOfRange(1, 4)
        val buf         = ByteBuffer.wrap(raw, 4, raw.size - 4).order(ByteOrder.BIG_ENDIAN)

        val newFrames = mutableListOf<ByteArray>()
        var anyChanged = false

        while (buf.hasRemaining()) {
            if (buf.remaining() < 3) break

            val frameStart   = buf.position()
            val reliability  = buf.get().toInt() and 0xFF
            val oldBits      = ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
            val oldLen       = oldBits / 8
            val headerPos    = buf.position()
            skipReliabilityHeaders(buf, reliability)
            val headerLen    = buf.position() - frameStart

            if (buf.remaining() < oldLen) { buf.position(frameStart); break }

            val payload = ByteArray(oldLen)
            buf.get(payload)

            // === ENTITY TRACKER === sunucudan gelen veriyi işle
            if (direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
                processForTracker(payload, direction)
            }

            val processed = processBatchPayload(payload, direction)
            if (processed == null) { anyChanged = true; continue }

            val newBits = processed.size * 8
            if (!processed.contentEquals(payload) || newBits != oldBits) anyChanged = true

            val out = ByteArrayOutputStream()
            out.write(reliability)
            out.write((newBits shr 8) and 0xFF)
            out.write(newBits and 0xFF)
            if (headerLen > 3) out.write(raw, 4 + frameStart + 3, headerLen - 3)
            out.write(processed)
            newFrames.add(out.toByteArray())
        }

        if (newFrames.isEmpty()) return null
        if (!anyChanged) return raw

        val out = ByteArrayOutputStream()
        out.write(flags.toInt())
        out.write(seqNumBytes)
        newFrames.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Sadece EntityTracker için batch payload'u işler (publish etmez) */
    private fun processForTracker(payload: ByteArray, direction: PacketEvent.Direction) {
        if (payload.isEmpty() || payload[0] != 0xFE.toByte()) return
        val inner  = payload.copyOfRange(1, payload.size)
        val stream = ByteArrayInputStream(inner)
        while (stream.available() > 0) {
            val len = readVarInt(stream)
            if (len <= 0 || stream.available() < len) break
            val data = ByteArray(len); stream.read(data)
            val idStream = ByteArrayInputStream(data)
            val pktId = readVarInt(idStream)
            // EntityTracker'a bildir
            try {
                EntityTracker.onPacket(PacketEvent(pktId, data, direction))
            } catch (_: Exception) {}
        }
    }

    private fun skipReliabilityHeaders(buf: ByteBuffer, reliability: Int) {
        val rt = reliability shr 5
        if (rt in intArrayOf(2,3,4,6,7) && buf.remaining()>=3) buf.position(buf.position()+3)
        if (rt in intArrayOf(1,4) && buf.remaining()>=3) buf.position(buf.position()+3)
        if (rt in intArrayOf(3,4,5,6,7) && buf.remaining()>=4) buf.position(buf.position()+4)
        if ((reliability and 0x10)!=0 && buf.remaining()>=10) buf.position(buf.position()+10)
    }

    private fun processBatchPayload(payload: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (payload.isEmpty() || payload[0] != 0xFE.toByte()) return payload

        val inner  = payload.copyOfRange(1, payload.size)
        val stream = ByteArrayInputStream(inner)
        val parts  = mutableListOf<ByteArray>()
        var changed = false

        while (stream.available() > 0) {
            val len = readVarInt(stream)
            if (len <= 0 || stream.available() < len) break
            val data = ByteArray(len); stream.read(data)
            val pktId = readVarInt(ByteArrayInputStream(data))
            val event = PacketEvent(pktId, data, direction)

            try { PacketEventBus.publish(event) } catch (e: Exception) { Log.e(TAG, "Bus: ${e.message}") }

            when {
                event.isCancelled        -> changed = true
                event.modifiedData != null -> { changed = true; parts.add(enc(event.modifiedData!!)) }
                else                     -> parts.add(enc(data))
            }
        }

        if (parts.isEmpty()) return null
        if (!changed) return payload

        val merged = parts.fold(ByteArray(0)) { a, b -> a + b }
        return byteArrayOf(0xFE.toByte()) + merged
    }

    private fun enc(data: ByteArray) = writeVarInt(data.size) + data

    fun readVarInt(stream: ByteArrayInputStream): Int {
        var r = 0; var s = 0
        for (i in 0 until 5) {
            val b = stream.read(); if (b == -1) break
            r = r or ((b and 0x7F) shl s)
            if ((b and 0x80) == 0) return r; s += 7
        }
        return r
    }

    fun writeVarInt(v: Int): ByteArray {
        val b = mutableListOf<Byte>(); var x = v
        do { var t = (x and 0x7F); x = x ushr 7; if (x != 0) t = t or 0x80; b.add(t.toByte()) } while (x != 0)
        return b.toByteArray()
    }
}