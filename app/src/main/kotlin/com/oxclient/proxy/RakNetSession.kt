package com.oxclient.proxy

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.Channel
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * RakNetSession
 *
 * Tek bir UDP bağlantısının tüm durumunu yönetir:
 * - Frame fragmentasyon / birleştirme
 * - Güvenilir sıralı mesaj tamponu
 * - ACK / NACK gönderimi
 * - Bağlantı el sıkışma durumu makinesi
 */
class RakNetSession(
    val channel: Channel,
    val remoteAddress: InetSocketAddress,
    val guid: Long,
    var mtu: Int = RakNetConstants.MTU_DEFAULT
) {
    // ── Durum makinesi ────────────────────────────────────────────────────
    enum class State { UNCONNECTED, HANDSHAKING, CONNECTED, DISCONNECTING, DISCONNECTED }
    @Volatile var state: State = State.UNCONNECTED

    // ── Sıra numaraları ───────────────────────────────────────────────────
    private val sendSeqNum     = AtomicInteger(0)
    private val sendReliableNum = AtomicInteger(0)
    private val sendOrderIndex  = AtomicInteger(0)
    private val receiveOrderIndex = AtomicInteger(-1)

    // ── Zamanlama ─────────────────────────────────────────────────────────
    val lastActivity = AtomicLong(System.currentTimeMillis())
    val pingTime     = AtomicLong(0)
    val pongTime     = AtomicLong(0)

    // ── Fragment buffer ───────────────────────────────────────────────────
    private val fragmentMap = ConcurrentHashMap<Int, MutableMap<Int, ByteArray>>()
    private val fragmentSizes = ConcurrentHashMap<Int, Int>()
    private val splitId = AtomicInteger(0)

    // ── ACK tampon ────────────────────────────────────────────────────────
    private val pendingAck = mutableListOf<Int>()
    private val pendingNack = mutableListOf<Int>()
    private val receivedSeqNums = mutableSetOf<Int>()
    private var lastReceivedSeqNum = -1

    // ── Güvenilir mesaj koruması ──────────────────────────────────────────
    private val receivedReliableNums = mutableSetOf<Int>()

    // ─────────────────────────────────────────────────────────────────────
    //  GÖNDERME
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ham baytları güvenilir-sıralı frame olarak gönderir.
     * Eğer veri MTU'dan büyükse fragment'lara böler.
     */
    fun sendReliableOrdered(data: ByteArray) {
        val maxPayload = mtu - 60   // Frame overhead
        if (data.size <= maxPayload) {
            sendFrame(data, RakNetConstants.RELIABLE_ORDERED, false, 0, 0, 0)
        } else {
            // Fragmentasyon
            val sid = splitId.getAndIncrement()
            val chunks = data.toList().chunked(maxPayload)
            chunks.forEachIndexed { idx, chunk ->
                sendFrame(chunk.toByteArray(), RakNetConstants.RELIABLE_ORDERED, true, sid, idx, chunks.size)
            }
        }
    }

    /** Güvenilirlik gerektirmeyen anlık gönderim (ping/pong) */
    fun sendUnreliable(data: ByteArray) {
        sendFrame(data, RakNetConstants.UNRELIABLE, false, 0, 0, 0)
    }

    private fun sendFrame(
        data: ByteArray,
        reliability: Byte,
        isSplit: Boolean,
        splitId: Int,
        splitIndex: Int,
        splitCount: Int
    ) {
        val buf = Unpooled.buffer()
        try {
            val seqNum = sendSeqNum.getAndIncrement()
            buf.writeMediumLE(seqNum)        // 3-byte sequence

            // Frame header
            var flags = (reliability.toInt() shl 5).toByte()
            if (isSplit) flags = (flags.toInt() or 0x10).toByte()
            buf.writeByte(flags.toInt())
            buf.writeShort(data.size * 8)    // bit length

            val rel = reliability.toInt()
            if (rel == 2 || rel == 3 || rel == 4 || rel == 6 || rel == 7) {
                buf.writeMediumLE(sendReliableNum.getAndIncrement())
            }
            if (rel == 1 || rel == 4) {
                buf.writeMediumLE(0)         // sequencing index
                buf.writeByte(0)             // order channel
            }
            if (rel == 3 || rel == 7) {
                buf.writeMediumLE(sendOrderIndex.getAndIncrement())
                buf.writeByte(0)             // order channel
            }

            if (isSplit) {
                buf.writeInt(splitCount)
                buf.writeShort(splitId)
                buf.writeInt(splitIndex)
            }

            buf.writeBytes(data)

            val packet = buf.copy()
            val finalBuf = Unpooled.buffer(1 + packet.readableBytes())
            finalBuf.writeByte(0x80)         // Frame Set Packet ID
            finalBuf.writeBytes(packet)

            sendRaw(finalBuf)
        } finally {
            buf.release()
        }
    }

    fun sendRaw(buf: ByteBuf) {
        channel.writeAndFlush(DatagramPacket(buf, remoteAddress))
        lastActivity.set(System.currentTimeMillis())
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ALMA & FRAGMENT BİRLEŞTİRME
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Gelen Frame Set Packet'ı işler, fragment'ları birleştirir.
     * @return Tam payload baytları ya da null (henüz tamamlanmadı)
     */
    fun handleFrame(buf: ByteBuf): ByteArray? {
        val seqNum = buf.readUnsignedMediumLE()
        synchronized(receivedSeqNums) {
            receivedSeqNums.add(seqNum)
            // Kaçırılan dizileri NACK listesine al
            for (i in (lastReceivedSeqNum + 1) until seqNum) {
                if (!receivedSeqNums.contains(i)) pendingNack.add(i)
            }
            if (seqNum > lastReceivedSeqNum) lastReceivedSeqNum = seqNum
            pendingAck.add(seqNum)
        }
        lastActivity.set(System.currentTimeMillis())

        if (!buf.isReadable) return null

        val flags     = buf.readByte().toInt()
        val reliability = ((flags and 0xE0) shr 5).toByte()
        val isSplit   = (flags and 0x10) != 0
        val bitLength = buf.readUnsignedShort()
        val byteLength = (bitLength + 7) / 8

        // Güvenilirlik alanlarını atla
        val rel = reliability.toInt()
        if (rel == 2 || rel == 3 || rel == 4 || rel == 6 || rel == 7) buf.readUnsignedMediumLE() // reliableMessageNum
        if (rel == 1 || rel == 4) { buf.readUnsignedMediumLE(); buf.readByte() }                 // seqIndex + channel
        if (rel == 3 || rel == 7) { buf.readUnsignedMediumLE(); buf.readByte() }                 // orderIndex + channel

        return if (isSplit) {
            val splitCount = buf.readInt()
            val splitId    = buf.readUnsignedShort()
            val splitIndex = buf.readInt()

            val chunk = ByteArray(byteLength)
            buf.readBytes(chunk)

            val map = fragmentMap.getOrPut(splitId) { ConcurrentHashMap() }
            map[splitIndex] = chunk
            fragmentSizes[splitId] = splitCount

            if (map.size == splitCount) {
                fragmentMap.remove(splitId)
                fragmentSizes.remove(splitId)
                (0 until splitCount).flatMap { map[it]!!.toList() }.toByteArray()
            } else null
        } else {
            val payload = ByteArray(byteLength)
            buf.readBytes(payload)
            payload
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACK / NACK
    // ─────────────────────────────────────────────────────────────────────

    fun flushAck() {
        synchronized(pendingAck) {
            if (pendingAck.isEmpty()) return
            sendAckNack(RakNetConstants.ID_ACK, pendingAck.toList())
            pendingAck.clear()
        }
    }

    fun flushNack() {
        synchronized(pendingNack) {
            if (pendingNack.isEmpty()) return
            sendAckNack(RakNetConstants.ID_NACK, pendingNack.toList())
            pendingNack.clear()
        }
    }

    private fun sendAckNack(id: Byte, seqNums: List<Int>) {
        if (seqNums.isEmpty()) return
        val sorted = seqNums.sorted()
        val buf = Unpooled.buffer()
        buf.writeByte(id.toInt())

        // Run-length encode ranges
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = sorted[0]; var end = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) end = sorted[i]
            else { ranges.add(start to end); start = sorted[i]; end = sorted[i] }
        }
        ranges.add(start to end)

        buf.writeShort(ranges.size)
        for ((s, e) in ranges) {
            if (s == e) {
                buf.writeByte(1)             // single
                buf.writeMediumLE(s)
            } else {
                buf.writeByte(0)             // range
                buf.writeMediumLE(s)
                buf.writeMediumLE(e)
            }
        }
        sendRaw(buf)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  YAŞAM DÖNGÜSÜ
    // ─────────────────────────────────────────────────────────────────────

    fun isTimedOut(): Boolean =
        System.currentTimeMillis() - lastActivity.get() > RakNetConstants.SESSION_TIMEOUT_MS

    fun disconnect() {
        state = State.DISCONNECTING
        val buf = Unpooled.buffer(1)
        buf.writeByte(RakNetConstants.ID_DISCONNECT_NOTIFICATION.toInt())
        sendUnreliable(buf.array())
        buf.release()
        state = State.DISCONNECTED
    }
}
