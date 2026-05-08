package com.oxclient.proxy

import android.util.Log
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import kotlinx.coroutines.*
import java.net.InetSocketAddress

/**
 * RakNetClient
 *
 * Gerçek Bedrock sunucusuna (Hive, CubeCraft, vb.) bağlanan upstream istemcisi.
 * Her MC istemci oturumu için ayrı bir RakNetClient örneği oluşturulur.
 *
 * El sıkışma akışı:
 *   Client → UnconnectedPing    → (cevap bekleme, isteğe bağlı)
 *   Client → OCRequest1         → OCReply1
 *   Client → OCRequest2         → OCReply2
 *   Client → ConnectionRequest  → ConnectionRequestAccepted
 *   Client → NewIncomingConn    → [bağlantı hazır]
 *   Client → 0xFE paketleri     → relay üzerinden MC'ye
 */
class RakNetClient(
    private val relay: BedrockRelay,
    private val clientAddress: InetSocketAddress,   // MC istemcisinin adresi (geri yönlendirme için)
    private val serverAddress: InetSocketAddress,
    private val clientGuid  : Long = System.nanoTime()
) {
    private val TAG = "RakNetClient[$clientAddress]"

    private lateinit var group  : NioEventLoopGroup
    private lateinit var channel: Channel
    private val session = RakNetSession(
        channel = Unpooled.EMPTY_BUFFER.alloc().let { throw IllegalStateException("init after start") },
        remoteAddress = serverAddress,
        guid = clientGuid
    )

    // Bağlantı durumu
    @Volatile var connected = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickJob: Job? = null

    // Bağlantı callback'i
    var onConnected   : (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────
    //  BAŞLATMA
    // ─────────────────────────────────────────────────────────────────────

    suspend fun connect(): Boolean {
        group = NioEventLoopGroup(2)
        val future = Bootstrap()
            .group(group)
            .channel(NioDatagramChannel::class.java)
            .option(ChannelOption.SO_BROADCAST, true)
            .option(ChannelOption.SO_RCVBUF, 512 * 1024)
            .option(ChannelOption.SO_SNDBUF, 512 * 1024)
            .handler(UpstreamHandler())
            .bind(0)
            .sync()

        val ch = future.channel()

        // ← Bu noktada channel hazır, session'ı oluştur
        val upstreamSession = RakNetSession(ch, serverAddress, clientGuid, RakNetConstants.MTU_DEFAULT)
        // Session referansını relay'e kaydet
        relay.registerUpstreamSession(clientAddress, upstreamSession)

        // El sıkışma başlat
        sendOCR1(ch)

        // Timeout kontrolü
        return withTimeoutOrNull(RakNetConstants.CONNECTION_TIMEOUT_MS) {
            while (!connected) delay(50)
            true
        } ?: false
    }

    fun disconnect() {
        connected = false
        tickJob?.cancel()
        relay.getUpstreamSession(clientAddress)?.disconnect()
        if (::channel.isInitialized) channel.close()
        if (::group.isInitialized)   group.shutdownGracefully()
        scope.cancel()
        onDisconnected?.invoke()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SUNUCUYA GÖNDER
    // ─────────────────────────────────────────────────────────────────────

    fun sendToServer(data: ByteArray) {
        val s = relay.getUpstreamSession(clientAddress) ?: return
        val payload = buildGamePacket(data)
        s.sendReliableOrdered(payload)
        s.flushAck()
    }

    private fun buildGamePacket(innerPayload: ByteArray): ByteArray {
        val buf = Unpooled.buffer()
        buf.writeByte(RakNetConstants.GAME_PACKET_ID.toInt())
        buf.writeBytes(innerPayload)
        return buf.array().copyOf(buf.writerIndex()).also { buf.release() }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EL SIKAŞMA GÖNDERİMLERİ
    // ─────────────────────────────────────────────────────────────────────

    private fun sendOCR1(ch: Channel) {
        val mtu = RakNetConstants.MTU_MAX
        val buf = Unpooled.buffer()
        buf.writeByte(RakNetConstants.ID_OPEN_CONNECTION_REQUEST_1.toInt())
        buf.writeBytes(RakNetConstants.OFFLINE_MESSAGE_ID)
        buf.writeByte(RakNetConstants.RAKNET_PROTOCOL_VERSION.toInt())
        // Padding to test MTU size
        val paddingSize = mtu - 1 - 16 - 1 - 1
        buf.writeZero(paddingSize.coerceAtLeast(0))
        ch.writeAndFlush(DatagramPacket(buf, serverAddress))
        Log.d(TAG, "OCR1 gönderildi → $serverAddress")
    }

    private fun sendOCR2(ch: Channel, serverMtu: Int) {
        val buf = Unpooled.buffer()
        buf.writeByte(RakNetConstants.ID_OPEN_CONNECTION_REQUEST_2.toInt())
        buf.writeBytes(RakNetConstants.OFFLINE_MESSAGE_ID)
        writeAddress(buf, serverAddress)
        buf.writeShort(serverMtu)
        buf.writeLong(clientGuid)
        ch.writeAndFlush(DatagramPacket(buf, serverAddress))
        relay.getUpstreamSession(clientAddress)?.mtu = serverMtu
        Log.d(TAG, "OCR2 gönderildi → $serverAddress, mtu=$serverMtu")
    }

    private fun sendConnectionRequest(ch: Channel) {
        val buf = Unpooled.buffer()
        buf.writeByte(RakNetConstants.ID_CONNECTION_REQUEST.toInt())
        buf.writeLong(clientGuid)
        buf.writeLong(System.currentTimeMillis())
        buf.writeBoolean(false)
        relay.getUpstreamSession(clientAddress)?.sendReliableOrdered(
            buf.array().copyOf(buf.writerIndex())
        )
        buf.release()
        Log.d(TAG, "ConnectionRequest gönderildi")
    }

    private fun sendNewIncomingConnection(serverAddr: InetSocketAddress) {
        val buf = Unpooled.buffer()
        buf.writeByte(RakNetConstants.ID_NEW_INCOMING_CONNECTION.toInt())
        writeAddress(buf, serverAddr)
        repeat(20) { writeAddress(buf, InetSocketAddress("255.255.255.255", 0)) }
        buf.writeLong(System.currentTimeMillis())
        buf.writeLong(System.currentTimeMillis())
        relay.getUpstreamSession(clientAddress)?.sendReliableOrdered(
            buf.array().copyOf(buf.writerIndex())
        )
        buf.release()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  NETTY HANDLER
    // ─────────────────────────────────────────────────────────────────────

    private inner class UpstreamHandler : SimpleChannelInboundHandler<DatagramPacket>() {

        override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
            val buf = msg.content()
            if (!buf.isReadable) return
            val id = buf.getByte(0)

            when (id) {
                RakNetConstants.ID_OPEN_CONNECTION_REPLY_1 -> {
                    buf.readByte()
                    if (buf.readableBytes() >= 16) buf.skipBytes(16) // magic
                    val guid     = buf.readLong()
                    val security = buf.readBoolean()
                    val mtu      = buf.readShort().toInt()
                    Log.d(TAG, "OCR Reply1 alındı, mtu=$mtu")
                    sendOCR2(ctx.channel(), mtu)
                }

                RakNetConstants.ID_OPEN_CONNECTION_REPLY_2 -> {
                    buf.readByte()
                    if (buf.readableBytes() >= 16) buf.skipBytes(16)
                    val guid = buf.readLong()
                    skipAddress(buf)
                    val mtu  = buf.readShort().toInt()
                    Log.d(TAG, "OCR Reply2 alındı, mtu=$mtu")
                    relay.getUpstreamSession(clientAddress)?.mtu = mtu
                    relay.getUpstreamSession(clientAddress)?.state = RakNetSession.State.HANDSHAKING
                    sendConnectionRequest(ctx.channel())
                }

                RakNetConstants.ID_INCOMPATIBLE_PROTOCOL_VERSION -> {
                    Log.e(TAG, "Uyumsuz protokol versiyonu!")
                    disconnect()
                }

                in (0x80..0x8F).map { it.toByte() } -> {
                    handleFrameSet(ctx, buf)
                }
            }
        }

        private fun handleFrameSet(ctx: ChannelHandlerContext, buf: ByteBuf) {
            val copy = buf.copy()
            copy.readByte()  // frame set ID

            val session = relay.getUpstreamSession(clientAddress) ?: run { copy.release(); return }
            val payload = session.handleFrame(copy) ?: run { copy.release(); return }
            copy.release()
            session.flushAck()

            if (payload.isEmpty()) return
            val innerBuf = Unpooled.wrappedBuffer(payload)
            val innerType = innerBuf.readByte()
            innerBuf.release()

            when (innerType) {
                RakNetConstants.ID_CONNECTION_REQUEST_ACCEPTED -> {
                    val pkt = Unpooled.wrappedBuffer(payload)
                    pkt.readByte()
                    skipAddress(pkt)
                    pkt.readShort()
                    pkt.release()
                    sendNewIncomingConnection(serverAddress)
                    Log.d(TAG, "ConnectionRequest kabul edildi")
                }

                RakNetConstants.ID_NEW_INCOMING_CONNECTION -> {
                    session.state = RakNetSession.State.CONNECTED
                    connected = true
                    Log.i(TAG, "Sunucuya bağlantı tamamlandı: $serverAddress")
                    onConnected?.invoke()
                    startTickLoop()
                }

                RakNetConstants.ID_DISCONNECT_NOTIFICATION -> {
                    Log.i(TAG, "Sunucu bağlantıyı kesti")
                    connected = false
                    onDisconnected?.invoke()
                    relay.onServerDisconnected(clientAddress)
                }

                RakNetConstants.ID_CONNECTED_PING -> {
                    val pkt = Unpooled.wrappedBuffer(payload)
                    pkt.readByte()
                    val time = pkt.readLong()
                    pkt.release()
                    val pong = Unpooled.buffer()
                    pong.writeByte(RakNetConstants.ID_CONNECTED_PONG.toInt())
                    pong.writeLong(time)
                    pong.writeLong(System.currentTimeMillis())
                    session.sendUnreliable(pong.array().copyOf(pong.writerIndex()))
                    pong.release()
                }

                RakNetConstants.GAME_PACKET_ID -> {
                    // Sunucudan gelen oyun paketi → MC'ye ilet
                    val batchPayload = payload.copyOfRange(1, payload.size)
                    val innerPackets = BedrockPipeline.decode(batchPayload)

                    val forwarded = mutableListOf<ByteArray>()
                    for (rawPkt in innerPackets) {
                        val result = PacketProcessor.process(rawPkt, com.oxclient.events.PacketDirection.CLIENT_BOUND)
                        if (result != null) forwarded.add(result)
                    }

                    // Enjekte edilecek paketleri de ekle
                    val injected = PacketProcessor.drainInjected()
                    val toClient = injected.filter { it.direction == com.oxclient.events.PacketDirection.CLIENT_BOUND }
                    forwarded.addAll(toClient.map { it.data })

                    if (forwarded.isNotEmpty()) {
                        val encoded = BedrockPipeline.encode(forwarded)
                        relay.localServer.sendToClient(clientAddress, encoded)
                    }

                    // SERVER_BOUND enjeksiyonlar
                    val toServer = injected.filter { it.direction == com.oxclient.events.PacketDirection.SERVER_BOUND }
                    if (toServer.isNotEmpty()) {
                        val encoded = BedrockPipeline.encode(toServer.map { it.data })
                        sendToServer(encoded)
                    }
                }
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e(TAG, "Upstream handler hatası", cause)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TICK DÖNGÜSÜ (ACK/NACK flush + ping)
    // ─────────────────────────────────────────────────────────────────────

    private fun startTickLoop() {
        tickJob = scope.launch {
            var pingTick = 0
            while (isActive && connected) {
                val s = relay.getUpstreamSession(clientAddress) ?: break
                s.flushAck()
                s.flushNack()

                // Her 5 saniyede bir ping
                if (pingTick++ >= 100) {
                    pingTick = 0
                    val ping = Unpooled.buffer()
                    ping.writeByte(RakNetConstants.ID_CONNECTED_PING.toInt())
                    ping.writeLong(System.currentTimeMillis())
                    s.sendUnreliable(ping.array().copyOf(ping.writerIndex()))
                    ping.release()
                }

                if (s.isTimedOut()) {
                    Log.w(TAG, "Upstream timeout!")
                    disconnect()
                    break
                }

                delay(50) // 20 TPS
            }
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
            buf.writeShortLE(10)
            buf.writeShort(addr.port)
            buf.writeInt(0)
            buf.writeBytes(ip)
            buf.writeInt(0)
        }
    }

    private fun skipAddress(buf: ByteBuf) {
        val type = buf.readByte().toInt()
        if (type == 4) buf.skipBytes(4 + 2) else buf.skipBytes(2 + 2 + 4 + 16 + 4)
    }

    @Suppress("UNCHECKED_CAST")
    private operator fun IntRange.contains(b: Byte): Boolean = b.toInt() in this
}
