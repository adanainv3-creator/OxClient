package com.oxclient.core.relay

import android.util.Log
import com.oxclient.core.relay.listener.OxPacketListener
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import io.netty.bootstrap.Bootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class OxRelaySession(
    val clientSession: BedrockServerSession,
    val remoteHost   : String,
    val remotePort   : Int,
    private val relay: OxRelay
) {
    companion object {
        private const val TAG = "OxRelaySession"
    }

    @Volatile var serverSession: BedrockClientSession? = null
        private set

    private val closed = AtomicBoolean(false)

    val listeners = CopyOnWriteArrayList<OxPacketListener>()

    @Volatile var activeCodec: BedrockCodec = OxRelay.RELAY_CODEC
        internal set

    private val clientEventLoop = NioEventLoopGroup(2)

    fun init() {
        clientSession.setPacketHandler(ClientPacketHandler())
        clientSession.addDisconnectHandler { reason ->
            Log.i(TAG, "Client bağlantısı kesildi: $reason")
            onClientDisconnect(reason.toString())
        }
        Log.i(TAG, "OxRelaySession init: ${clientSession.socketAddress} → $remoteHost:$remotePort")
        connectToServer()
    }

    private fun connectToServer() {
        val remoteAddress = InetSocketAddress(remoteHost, remotePort)
        Bootstrap()
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .group(clientEventLoop)
            .handler(object : BedrockClientInitializer() {
                override fun initSession(session: BedrockClientSession) {
                    serverSession = session
                    session.setCodec(activeCodec)
                    session.setPacketHandler(ServerPacketHandler())
                    session.addDisconnectHandler { reason ->
                        Log.i(TAG, "Server bağlantısı kesildi: $reason")
                        onServerDisconnect(reason.toString())
                    }
                    Log.i(TAG, "Server'a bağlandı: $remoteAddress")
                    notifyListenersConnected()
                }
            })
            .connect(remoteAddress)
            .addListener { future ->
                if (!future.isSuccess) {
                    Log.e(TAG, "Server'a bağlanılamadı: ${future.cause()?.message}")
                    disconnect("Server'a bağlanılamadı")
                }
            }
    }

    fun handleClientPacket(packet: BedrockPacket): Boolean {
        val event = PacketEvent(packet, PacketEvent.Direction.CLIENT_TO_SERVER, this)
        PacketEventBus.publish(event)

        val effective = event.replacementPacket ?: packet

        if (event.isCancelled) return false

        for (listener in listeners) {
            if (!listener.onClientPacket(effective, this)) return false
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

        val effective = event.replacementPacket ?: packet

        if (event.isCancelled) return false

        for (listener in listeners) {
            if (!listener.onServerPacket(effective, this)) return false
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
        catch (e: Exception) { Log.w(TAG, "Client'a paket gönderilemedi: ${e.message}") }
    }

    fun sendToServer(packet: BedrockPacket) {
        if (closed.get()) return
        val srv = serverSession ?: return
        try { srv.sendPacketImmediately(packet) }
        catch (e: Exception) { Log.w(TAG, "Server'a paket gönderilemedi: ${e.message}") }
    }

    fun clientBound(packet: BedrockPacket) = sendToClient(packet)

    fun serverBound(packet: BedrockPacket) = sendToServer(packet)

    fun disconnect(reason: String = "Relay kapatıldı") {
        if (!closed.compareAndSet(false, true)) return
        Log.i(TAG, "Session kapatılıyor: $reason")
        try {
            val pkt = DisconnectPacket()
            pkt.kickMessage     = reason
            pkt.isMessageSkipped = false
            clientSession.sendPacketImmediately(pkt)
        } catch (_: Exception) {}
        try { clientSession.disconnect() }           catch (_: Exception) {}
        try { serverSession?.disconnect() }          catch (_: Exception) {}
        try { clientEventLoop.shutdownGracefully() } catch (_: Exception) {}
        relay.removeSession(this)
        listeners.forEach { it.onSessionEnd(this) }
        listeners.clear()
    }

    private fun onClientDisconnect(reason: String) {
        try { serverSession?.disconnect() } catch (_: Exception) {}
        relay.removeSession(this)
        listeners.forEach { it.onSessionEnd(this) }
    }

    private fun onServerDisconnect(reason: String) { disconnect(reason) }

    private fun notifyListenersConnected() {
        listeners.forEach { it.onSessionStart(this) }
    }

    inner class ClientPacketHandler : org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler {
        override fun handlePacket(packet: BedrockPacket): Boolean {
            if (handleClientPacket(packet)) sendToServer(packet)
            return true
        }
    }

    inner class ServerPacketHandler : org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler {
        override fun handlePacket(packet: BedrockPacket): Boolean {
            if (handleServerPacket(packet)) sendToClient(packet)
            return true
        }
    }

    val isClosed: Boolean get() = closed.get()
    val clientAddress: String get() = clientSession.socketAddress?.toString() ?: "unknown"
}
