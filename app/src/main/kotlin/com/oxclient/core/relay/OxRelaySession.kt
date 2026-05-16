package com.oxclient.core.relay

import android.util.Log
import com.oxclient.core.relay.listener.OxPacketListener
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import org.cloudburstmc.protocol.common.PacketSignal
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class OxRelaySession(
    val clientSession : BedrockServerSession,
    val remoteHost    : String,
    val remotePort    : Int,
    private val relay : OxRelay
) {
    companion object { private const val TAG = "OxRelaySession" }

    @Volatile var serverSession: BedrockClientSession? = null
        private set

    private val closed = AtomicBoolean(false)

    val listeners = CopyOnWriteArrayList<OxPacketListener>()

    @Volatile var activeCodec: BedrockCodec = OxRelay.RELAY_CODEC
        internal set

    private val clientEventLoop = NioEventLoopGroup(2)

    fun init() {
        clientSession.setPacketHandler(ClientPacketHandler())
        installClientDisconnectHandler()
        Log.i(TAG, "Session init: ${clientSession.socketAddress} → $remoteHost:$remotePort")
        connectToServer()
    }

    private fun installClientDisconnectHandler() {
        try {
            clientSession.peer.channel
                .pipeline()
                .addLast("ox-client-disconnect", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        Log.i(TAG, "Client bağlantısı kesildi (channelInactive)")
                        onClientDisconnect("channelInactive")
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason) {
                            Log.i(TAG, "Client RakNet disconnect: $evt")
                            onClientDisconnect(evt.toString())
                        }
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) {
            Log.w(TAG, "Client disconnect handler eklenemedi: ${e.message}")
        }
    }

    private fun installServerDisconnectHandler(session: BedrockClientSession) {
        try {
            session.peer.channel
                .pipeline()
                .addLast("ox-server-disconnect", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        Log.i(TAG, "Server bağlantısı kesildi (channelInactive)")
                        onServerDisconnect("channelInactive")
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason) {
                            Log.i(TAG, "Server RakNet disconnect: $evt")
                            onServerDisconnect(evt.toString())
                        }
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) {
            Log.w(TAG, "Server disconnect handler eklenemedi: ${e.message}")
        }
    }

    private fun connectToServer() {
        val addr = InetSocketAddress(remoteHost, remotePort)
        Bootstrap()
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .group(clientEventLoop)
            .handler(object : BedrockClientInitializer() {
                override fun initSession(session: BedrockClientSession) {
                    serverSession = session
                    session.setCodec(activeCodec)
                    session.setPacketHandler(ServerPacketHandler())
                    installServerDisconnectHandler(session)
                    Log.i(TAG, "Server'a bağlandı: $addr")
                    listeners.forEach { it.onSessionStart(this@OxRelaySession) }
                }
            })
            .connect(addr)
            .addListener { future ->
                if (!future.isSuccess) {
                    Log.e(TAG, "Bağlanamadı: ${future.cause()?.message}")
                    disconnect("Server'a bağlanılamadı")
                }
            }
    }

    fun handleClientPacket(packet: BedrockPacket): Boolean {
        val event = PacketEvent(packet, PacketEvent.Direction.CLIENT_TO_SERVER, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false
        for (listener in listeners) {
            if (!listener.onClientPacket(event.replacementPacket ?: packet, this)) return false
        }
        if (event.replacementPacket != null) {
            sendToServer(event.replacementPacket!!)
            return false
        }
        return true
    }

    fun handleServerPacket(packet: BedrockPacket): Boolean {
        val event = PacketEvent(packet, PacketEvent.Direction.SERVER_TO_CLIENT, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false
        for (listener in listeners) {
            if (!listener.onServerPacket(event.replacementPacket ?: packet, this)) return false
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
        catch (e: Exception) { Log.w(TAG, "Client paket hatası: ${e.message}") }
    }

    fun sendToServer(packet: BedrockPacket) {
        if (closed.get()) return
        try { serverSession?.sendPacketImmediately(packet) }
        catch (e: Exception) { Log.w(TAG, "Server paket hatası: ${e.message}") }
    }

    fun clientBound(packet: BedrockPacket) = sendToClient(packet)
    fun serverBound(packet: BedrockPacket) = sendToServer(packet)

    fun disconnect(reason: String = "Relay kapatıldı") {
        if (!closed.compareAndSet(false, true)) return
        Log.i(TAG, "Session kapatılıyor: $reason")
        try {
            clientSession.sendPacketImmediately(DisconnectPacket().apply {
                kickMessage      = reason
                isMessageSkipped = false
            })
        } catch (_: Exception) {}
        try { clientSession.disconnect() }           catch (_: Exception) {}
        try { serverSession?.disconnect() }          catch (_: Exception) {}
        try { clientEventLoop.shutdownGracefully() } catch (_: Exception) {}
        relay.removeSession(this)
        listeners.forEach { it.onSessionEnd(this) }
        listeners.clear()
    }

    private fun onClientDisconnect(reason: String) {
        if (closed.get()) return
        try { serverSession?.disconnect() } catch (_: Exception) {}
        relay.removeSession(this)
        listeners.forEach { it.onSessionEnd(this) }
    }

    private fun onServerDisconnect(reason: String) = disconnect(reason)

    inner class ClientPacketHandler : BedrockPacketHandler {
        override fun handlePacket(packet: BedrockPacket): PacketSignal {
            if (handleClientPacket(packet)) sendToServer(packet)
            return PacketSignal.HANDLED
        }
    }

    inner class ServerPacketHandler : BedrockPacketHandler {
        override fun handlePacket(packet: BedrockPacket): PacketSignal {
            if (handleServerPacket(packet)) sendToClient(packet)
            return PacketSignal.HANDLED
        }
    }

    val isClosed      : Boolean get() = closed.get()
    val clientAddress : String  get() = clientSession.socketAddress?.toString() ?: "unknown"
}
