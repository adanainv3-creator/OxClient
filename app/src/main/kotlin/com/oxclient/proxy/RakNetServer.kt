package com.oxclient.proxy

import android.util.Log
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import java.net.InetSocketAddress
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * RakNetServer
 *
 * 0.0.0.0:19132 üzerinde gerçek bir UDP sunucu açar.
 * Minecraft istemcisinden gelen RakNet paketlerini işler ve
 * BedrockRelay'e iletir (MITM ortası).
 *
 * El sıkışma akışı:
 *   MC → UnconnectedPing        → Pong (MOTD)
 *   MC → OCRequest1             → OCReply1
 *   MC → OCRequest2             → OCReply2
 *   MC → ConnectionRequest      → ConnectionRequestAccepted
 *   MC → NewIncomingConnection  → [oturum açıldı, relay'e bağlan]
 *   MC → 0xFE (GamePacket)      → BedrockPipeline.decode → PacketProcessor → relay
 */
class RakNetServer(
    private val relay: BedrockRelay,
    private val bindPort: Int = RakNetConstants.LOCAL_BIND_PORT
) {
    private val TAG = "RakNetServer"

    private lateinit var group  : NioEventLoopGroup
    private lateinit var channel: Channel

    // Aktif istemci oturumları (birden fazla istemci destekli)
    private val sessions = ConcurrentHashMap<InetSocketAddress, RakNetSession>()

    // ─────────────────────────────────────────────────────────────────────
    //  BAŞLATMA / DURDURMA
    // ─────────────────────────────────────────────────────────────────────

    fun start() {
        group = NioEventLoopGroup(2)
        val bootstrap = Bootstrap()
            .group(group)
            .channel(NioDatagramChannel::class.java)
            .option(ChannelOption.SO_BROADCAST, true)
            .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
            .option(ChannelOption.SO_SNDBUF, 1024 * 1024)
            .handler(ServerHandler())

        val future = bootstrap.bind("0.0.0.0", bindPort).sync()
        channel = future.channel()
        Log.i(TAG, "RakNet sunucu başlatıldı: 0.0.0.0:$bindPort")
    }

    fun stop() {
        sessions.values.forEach { it.disconnect() }
        sessions.clear()
        if (::channel.isInitialized) channel.close().sync()
        if (::group.isInitialized)   group.shutdownGracefully().sync()
        Log.i(TAG, "RakNet sunucu durduruldu")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SUNUCU → MC gönderimi
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Relay'den gelen sunucu paketini ilgili MC istemcisine ilet.
     */
    fun sendToClient(address: InetSocketAddress, data: ByteArray) {
        val session = sessions[address] ?: return
        val payload = buildGamePacket(data)
        session.sendReliableOrdered(payload)
        session.flushAck()
    }

    private fun buildGamePacket(innerPayload: ByteArray): ByteArray {
        // innerPayload: zaten encode/sıkıştırılmış batch payload
        val buf = Unpooled.buffer()
        buf.writeByte(RakNetConstants.GAME_PACKET_ID.toInt())
        buf.writeBytes(innerPayload)
        return buf.array().copyOf(buf.writerIndex()).also { buf.release() }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  NETTY HANDLER
    // ─────────────────────────────────────────────────────────────────────

    private inner class ServerHandler : SimpleChannelInboundHandler<DatagramPacket>() {

        override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
            val sender = msg.sender()
            val buf    = msg.content()
            if (!buf.isReadable) return

            val id = buf.getByte(0)
            when {
                id == RakNetConstants.ID_UNCONNECTED_PING   -> handleUnconnectedPing(ctx, sender, buf)
                id == RakNetConstants.ID_OPEN_CONNECTION_REQUEST_1 -> handleOCR1(ctx, sender, buf)
                id == RakNetConstants.ID_OPEN_CONNECTION_REQUEST_2 -> handleOCR2(ctx, sender, buf)
                id in (RakNetConstants.FRAME_SET_PACKET_BEGIN..RakNetConstants.FRAME_SET_PACKET_END).map { it.toByte() } ->
                    handleFrameSet(ctx, sender, buf)
                else -> { /* ignore unknown offline packets */ }
            }
        }

        // ── Ping / Pong ───────────────────────────────────────────────────

        private fun handleUnconnectedPing(ctx: ChannelHandlerContext, sender: InetSocketAddress, buf: ByteBuf) {
            buf.readByte()                          // ID
            val pingTime = buf.readLong()           // client timestamp
            // 16-byte magic
            if (buf.readableBytes() >= 16) buf.skipBytes(16)
            val clientGuid = if (buf.readableBytes() >= 8) buf.readLong() else 0L

            val motd = buildMotd()
            val reply = Unpooled.buffer()
            reply.writeByte(RakNetConstants.ID_UNCONNECTED_PONG.toInt())
            reply.writeLong(pingTime)
            reply.writeLong(System.currentTimeMillis())
            reply.writeBytes(RakNetConstants.OFFLINE_MESSAGE_ID)
            reply.writeShort(motd.length)
            reply.writeBytes(motd.toByteArray(Charsets.UTF_8))

            ctx.writeAndFlush(DatagramPacket(reply, sender))
        }

        private fun buildMotd(): String {
            val targetConfig = relay.targetServer
            val name = targetConfig?.name ?: "OxClient Proxy"
            // MCPE;MOTD;ProtoVersion;Version;Players;MaxPlayers;GUID;SubMOTD;GameMode;GameModeNum;IPv4Port;IPv6Port
            return "MCPE;$name;" +
                "${RakNetConstants.BEDROCK_PROTOCOL_VERSION};" +
                "${RakNetConstants.BEDROCK_VERSION_STRING};" +
                "1;20;${System.currentTimeMillis()};" +
                "OxClient;Survival;1;${RakNetConstants.LOCAL_BIND_PORT};19133;"
        }

        // ── Open Connection Request 1 ─────────────────────────────────────

        private fun handleOCR1(ctx: ChannelHandlerContext, sender: InetSocketAddress, buf: ByteBuf) {
            buf.readByte()                          // ID
            if (buf.readableBytes() >= 16) buf.skipBytes(16) // magic
            val proto = buf.readByte()
            val mtuPayloadSize = buf.readableBytes() + 46   // padding trick

            if (proto != RakNetConstants.RAKNET_PROTOCOL_VERSION) {
                // Incompatible protocol
                val reply = Unpooled.buffer()
                reply.writeByte(RakNetConstants.ID_INCOMPATIBLE_PROTOCOL_VERSION.toInt())
                reply.writeByte(RakNetConstants.RAKNET_PROTOCOL_VERSION.toInt())
                reply.writeBytes(RakNetConstants.OFFLINE_MESSAGE_ID)
                reply.writeLong(relay.serverGuid)
                ctx.writeAndFlush(DatagramPacket(reply, sender))
                return
            }

            val reply = Unpooled.buffer()
            reply.writeByte(RakNetConstants.ID_OPEN_CONNECTION_REPLY_1.toInt())
            reply.writeBytes(RakNetConstants.OFFLINE_MESSAGE_ID)
            reply.writeLong(relay.serverGuid)
            reply.writeBoolean(false)               // security = false
            reply.writeShort(mtuPayloadSize.coerceIn(RakNetConstants.MTU_MIN, RakNetConstants.MTU_MAX))
            ctx.writeAndFlush(DatagramPacket(reply, sender))
        }

        // ── Open Connection Request 2 ─────────────────────────────────────

        private fun handleOCR2(ctx: ChannelHandlerContext, sender: InetSocketAddress, buf: ByteBuf) {
            buf.readByte()
            if (buf.readableBytes() >= 16) buf.skipBytes(16)
            // Server address (skip)
            val addrType = buf.readByte().toInt()
            if (addrType == 4) buf.skipBytes(4 + 2) else buf.skipBytes(16 + 2)
            val mtu        = buf.readShort().toInt()
            val clientGuid = buf.readLong()

            val session = RakNetSession(channel, sender, clientGuid, mtu)
            session.state = RakNetSession.State.HANDSHAKING
            sessions[sender] = session

            val reply = Unpooled.buffer()
            reply.writeByte(RakNetConstants.ID_OPEN_CONNECTION_REPLY_2.toInt())
            reply.writeBytes(RakNetConstants.OFFLINE_MESSAGE_ID)
            reply.writeLong(relay.serverGuid)
            writeAddress(reply, sender)
            reply.writeShort(mtu)
            reply.writeBoolean(false)               // encryption = false
            ctx.writeAndFlush(DatagramPacket(reply, sender))

            Log.d(TAG, "OCR2 → oturum oluşturuldu: $sender, mtu=$mtu")
        }

        // ── Frame Set Packet ──────────────────────────────────────────────

        private fun handleFrameSet(ctx: ChannelHandlerContext, sender: InetSocketAddress, buf: ByteBuf) {
            val session = sessions[sender] ?: return
            val copy = buf.copy()   // handle() buf'u tüketir, kopyala
            copy.readByte()         // frame set ID (0x80..0x8F)

            val payload = session.handleFrame(copy) ?: run { copy.release(); return }
            copy.release()
            session.flushAck()
            session.flushNack()

            if (payload.isEmpty()) return
            val innerBuf = Unpooled.wrappedBuffer(payload)
            val innerType = innerBuf.readByte()
            innerBuf.release()

            when (innerType) {
                RakNetConstants.ID_CONNECTION_REQUEST     -> handleConnectionRequest(ctx, session, payload)
                RakNetConstants.ID_NEW_INCOMING_CONNECTION -> handleNewIncoming(session)
                RakNetConstants.ID_CONNECTED_PING         -> handleConnectedPing(session, payload)
                RakNetConstants.ID_DISCONNECT_NOTIFICATION -> {
                    session.state = RakNetSession.State.DISCONNECTED
                    sessions.remove(sender)
                    relay.onClientDisconnected(sender)
                    Log.i(TAG, "İstemci bağlantısı kesildi: $sender")
                }
                RakNetConstants.GAME_PACKET_ID            -> handleGamePacket(session, payload)
                else -> { /* ignore */ }
            }
        }

        private fun handleConnectionRequest(ctx: ChannelHandlerContext, session: RakNetSession, payload: ByteArray) {
            val buf = Unpooled.wrappedBuffer(payload)
            buf.readByte()
            val clientGuid = buf.readLong()
            val time       = buf.readLong()
            buf.release()

            val now = System.currentTimeMillis()
            val reply = Unpooled.buffer()
            reply.writeByte(RakNetConstants.ID_CONNECTION_REQUEST_ACCEPTED.toInt())
            writeAddress(reply, session.remoteAddress)
            reply.writeShort(0)
            // 20 system addresses
            repeat(20) { writeAddress(reply, InetSocketAddress("255.255.255.255", 0)) }
            reply.writeLong(time)
            reply.writeLong(now)

            session.sendReliableOrdered(reply.array().copyOf(reply.writerIndex()))
            reply.release()
            Log.d(TAG, "ConnectionRequest kabul edildi: ${session.remoteAddress}")
        }

        private fun handleNewIncoming(session: RakNetSession) {
            session.state = RakNetSession.State.CONNECTED
            Log.i(TAG, "Yeni bağlantı: ${session.remoteAddress}")
            // Relay'e bildir, gerçek sunucuya upstream bağlantı kur
            relay.onClientConnected(session)
        }

        private fun handleConnectedPing(session: RakNetSession, payload: ByteArray) {
            val buf  = Unpooled.wrappedBuffer(payload)
            buf.readByte()
            val time = buf.readLong()
            buf.release()

            val pong = Unpooled.buffer()
            pong.writeByte(RakNetConstants.ID_CONNECTED_PONG.toInt())
            pong.writeLong(time)
            pong.writeLong(System.currentTimeMillis())
            session.sendUnreliable(pong.array().copyOf(pong.writerIndex()))
            pong.release()
        }

        private fun handleGamePacket(session: RakNetSession, payload: ByteArray) {
            // 0xFE'den sonraki baytlar = sıkıştırılmış batch
            val batchPayload = payload.copyOfRange(1, payload.size)
            val innerPackets = BedrockPipeline.decode(batchPayload)

            for (rawPkt in innerPackets) {
                val result = PacketProcessor.process(rawPkt, com.oxclient.events.PacketDirection.SERVER_BOUND)
                if (result != null) {
                    relay.forwardToServer(session.remoteAddress, result)
                }
            }

            // Enjekte edilecek paketleri gönder
            val injected = PacketProcessor.drainInjected()
            val toServer = injected.filter { it.direction == com.oxclient.events.PacketDirection.SERVER_BOUND }
            if (toServer.isNotEmpty()) {
                val encoded = BedrockPipeline.encode(toServer.map { it.data })
                relay.forwardRawToServer(session.remoteAddress, encoded)
            }
            val toClient = injected.filter { it.direction == com.oxclient.events.PacketDirection.CLIENT_BOUND }
            if (toClient.isNotEmpty()) {
                val encoded = BedrockPipeline.encode(toClient.map { it.data })
                sendToClient(session.remoteAddress, encoded)
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e(TAG, "Sunucu handler hatası", cause)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  YARDIMCILAR
    // ─────────────────────────────────────────────────────────────────────

    private fun writeAddress(buf: ByteBuf, addr: InetSocketAddress) {
        val ip = addr.address.address
        if (ip.size == 4) {
            buf.writeByte(4)
            buf.writeBytes(ip.map { (it.toInt() xor 0xFF).toByte() }.toByteArray())
            buf.writeShort(addr.port)
        } else {
            buf.writeByte(6)
            buf.writeShortLE(10)        // AF_INET6
            buf.writeShort(addr.port)
            buf.writeInt(0)
            buf.writeBytes(ip)
            buf.writeInt(0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private operator fun IntRange.contains(b: Byte): Boolean = b.toInt() in this
}
