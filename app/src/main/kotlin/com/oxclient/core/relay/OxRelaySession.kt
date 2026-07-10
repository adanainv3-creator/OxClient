package com.oxclient.core.relay

import com.oxclient.core.relay.listener.OxPacketListener
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import io.netty.buffer.Unpooled
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class OxRelaySession internal constructor(
    peer: BedrockPeer,
    subClientId: Int,
    val remoteHost: String,
    val remotePort: Int,
    internal val relay: OxRelay
) {
    companion object {
        private const val TAG       = "OxRelaySession"
        private const val MAX_QUEUE = 1024

        private fun minecraftUnconnectedMagic() = Unpooled.wrappedBuffer(
            byteArrayOf(
                0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(),
                0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
                0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
                0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()
            )
        )

        private fun generateClientGuid(): Long {
            val timestamp = System.currentTimeMillis()
            val random    = kotlin.random.Random.nextLong(0, 0xFFFFFF)
            return (timestamp shl 24) or random
        }
    }

    val clientSession: ServerSession = ServerSession(peer, subClientId)

    @Volatile var serverSession: ClientSession? = null
        internal set

    @Volatile var activeCodec: BedrockCodec = OxRelay.RELAY_CODEC
        internal set

    val listeners = CopyOnWriteArrayList<OxPacketListener>()

    private val closed           = AtomicBoolean(false)
    private val serverConnecting = AtomicBoolean(false)
    private val serverConnected  = AtomicBoolean(false)

    private val pendingQueue = ConcurrentLinkedQueue<Pair<BedrockPacket, Boolean>>()

    private val serverEventLoop = NioEventLoopGroup(2)

    fun init() {
        clientSession.codec = activeCodec
        installClientDisconnectHandler()
    }

    fun connectToServer(onConnected: (() -> Unit)? = null) {
        if (!serverConnecting.compareAndSet(false, true)) {
            return
        }

        val addr = InetSocketAddress(remoteHost, remotePort)

        Bootstrap()
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .group(serverEventLoop)
            .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
            .option(RakChannelOption.RAK_GUID, generateClientGuid())
            .option(RakChannelOption.RAK_MTU, 1400)
            .option(RakChannelOption.RAK_UNCONNECTED_MAGIC, minecraftUnconnectedMagic())
            .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
            .option(RakChannelOption.RAK_CONNECT_TIMEOUT, 10_000L)
            .option(RakChannelOption.RAK_SESSION_TIMEOUT, 20_000L)
            .handler(object : BedrockChannelInitializer<ClientSession>() {

                override fun createSession0(peer: BedrockPeer, subClientId: Int): ClientSession {
                    return this@OxRelaySession.ClientSession(peer, subClientId)
                }

                override fun initSession(session: ClientSession) {
                    session.codec = activeCodec
                    session.peer.codecHelper.apply {
                        blockDefinitions        = clientSession.peer.codecHelper.blockDefinitions
                        itemDefinitions         = clientSession.peer.codecHelper.itemDefinitions
                        cameraPresetDefinitions = clientSession.peer.codecHelper.cameraPresetDefinitions
                        encodingSettings        = clientSession.peer.codecHelper.encodingSettings
                    }
                    installServerDisconnectHandler(session)

                    serverSession = session
                    serverConnected.set(true)

                    listeners.forEach { l ->
                        try { l.onSessionStart(this@OxRelaySession) }
                        catch (e: Exception) { }
                    }

                    try { onConnected?.invoke() }
                    catch (e: Exception) { }

                    flushQueue(session)
                }

                override fun preInitChannel(channel: Channel) {
                    channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND)
                    super.preInitChannel(channel)
                }
            })
            .connect(addr)
            .addListener { future ->
                if (!future.isSuccess) {
                    disconnect("Server bağlantısı kurulamadı")
                }
            }
    }

    private fun flushQueue(session: ClientSession) {
        var n = 0
        while (true) {
            val (pkt, immediate) = pendingQueue.poll() ?: break
            try {
                if (immediate) session.sendPacketImmediately(pkt)
                else session.sendPacket(pkt)
                n++
            } catch (e: Exception) { }
        }
    }

    fun handleClientPacket(wrapper: BedrockPacketWrapper): Boolean {
        val packet = wrapper.packet
        val event  = PacketEvent(packet, PacketEvent.Direction.CLIENT_TO_SERVER, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false

        for (listener in listeners) {
            val effective = event.replacementPacket ?: packet
            if (!listener.onClientPacket(effective, this)) {
                event.replacementPacket?.let { sendToServer(it) }
                return false
            }
        }
        if (event.replacementPacket != null) {
            sendToServer(event.replacementPacket!!)
            return false
        }
        return true
    }

    fun handleServerPacket(wrapper: BedrockPacketWrapper): Boolean {
        val packet = wrapper.packet
        val event  = PacketEvent(packet, PacketEvent.Direction.SERVER_TO_CLIENT, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false

        for (listener in listeners) {
            val effective = event.replacementPacket ?: packet
            if (!listener.onServerPacket(effective, this)) {
                event.replacementPacket?.let { sendToClient(it) }
                return false
            }
        }
        if (event.replacementPacket != null) {
            sendToClient(event.replacementPacket!!)
            return false
        }
        return true
    }

    fun sendToClient(packet: BedrockPacket) {
        if (closed.get()) return
        try { clientSession.sendPacketImmediately(packet) }
        catch (e: Exception) { }
    }

    fun sendToServer(packet: BedrockPacket, immediate: Boolean = true) {
        if (closed.get()) return
        val srv = serverSession
        if (srv != null && serverConnected.get()) {
            try {
                if (immediate) srv.sendPacketImmediately(packet)
                else srv.sendPacket(packet)
            } catch (e: Exception) { }
        } else {
            if (pendingQueue.size < MAX_QUEUE) pendingQueue.add(packet to immediate)
        }
    }

    fun clientBound(packet: BedrockPacket)  = sendToClient(packet)
    fun serverBound(packet: BedrockPacket)  = sendToServer(packet)

    fun disconnect(reason: String = "Relay kapatıldı") {
        if (!closed.compareAndSet(false, true)) return
        pendingQueue.clear()
        try { clientSession.disconnect() }           catch (_: Exception) {}
        try { serverSession?.disconnect() }          catch (_: Exception) {}
        try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}
        relay.removeSession(this)
        listeners.forEach { try { it.onSessionEnd(this) } catch (_: Exception) {} }
        listeners.clear()
    }

    private fun installClientDisconnectHandler() {
        try {
            clientSession.peer.channel.pipeline()
                .addLast("ox-client-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (closed.compareAndSet(false, true)) {
                            pendingQueue.clear()
                            try { serverSession?.disconnect() }          catch (_: Exception) {}
                            try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}
                            relay.removeSession(this@OxRelaySession)
                            listeners.forEach { try { it.onSessionEnd(this@OxRelaySession) } catch (_: Exception) {} }
                            listeners.clear()
                        }
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) { }
    }

    private fun installServerDisconnectHandler(session: ClientSession) {
        try {
            session.peer.channel.pipeline()
                .addLast("ox-server-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (!closed.get()) disconnect("Server kesildi")
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason) { disconnect(evt.toString()) }
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) { }
    }

    inner class ServerSession(peer: BedrockPeer, subClientId: Int) :
        BedrockServerSession(peer, subClientId) {

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                if (!handleClientPacket(wrapper)) return

                val buffer = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                sendToServer(UnknownPacket().apply {
                    payload  = buffer
                    packetId = wrapper.packetId
                }, immediate = true)
            } catch (e: Exception) {
            }
        }
    }

    inner class ClientSession(peer: BedrockPeer, subClientId: Int) :
        BedrockClientSession(peer, subClientId) {

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                if (!handleServerPacket(wrapper)) return

                val buffer = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                sendToClient(UnknownPacket().apply {
                    payload  = buffer
                    packetId = wrapper.packetId
                })
            } catch (e: Exception) {
            }
        }
    }

    val isClosed      : Boolean get() = closed.get()
    val clientAddress : String  get() = clientSession.socketAddress?.toString() ?: "unknown"
    val isServerReady : Boolean get() = serverConnected.get() && !closed.get()
}
