package com.oxclient.relay

import android.util.Log
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * OxRelaySession
 *
 * Tek bir Minecraft bağlantısı için MITM oturumu.
 *
 * ┌─────────────┐  serverSession  ┌─────────────┐  clientSession  ┌─────────────┐
 * │  Minecraft  │ ◄──────────────►│  OxRelay    │ ◄──────────────►│ Gerçek Sunucu│
 * └─────────────┘                 └─────────────┘                 └─────────────┘
 */
class OxRelaySession(
    val serverSession: BedrockServerSession,
    private val targetHost: String,
    private val targetPort: Int
) {
    companion object {
        private const val TAG = "OxRelaySession"
    }

    /** relay → gerçek sunucu bağlantısı */
    var clientSession: BedrockClientSession? = null
        private set

    private val listeners    = CopyOnWriteArrayList<RelayPacketListener>()
    private val packetQueue  = ConcurrentLinkedQueue<BedrockPacket>()  // client hazır olmadan gelenler
    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioGroup      = NioEventLoopGroup(2)

    var protocolVersion: Int = 0
    var disconnected: Boolean = false
        private set

    // ── Init ──────────────────────────────────────────────────────────────

    fun init() {
        setupServerHandlers()
        Log.d(TAG, "Session hazır — ${serverSession.socketAddress}")
    }

    private fun setupServerHandlers() {
        // Minecraft → relay: her paketi listener'lardan geçir
        serverSession.addDisconnectHandler {
            Log.d(TAG, "Minecraft bağlantısı kesildi")
            onDisconnect()
        }
    }

    /** Gerçek sunucuya bağlan (OnlineLoginPacketListener login parse edince çağırır) */
    fun connectToServer(onConnected: (BedrockClientSession) -> Unit) {
        scope.launch {
            try {
                val bootstrap = Bootstrap()
                    .group(ioGroup)
                    .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
                    .handler(object : BedrockClientInitializer() {
                        override fun initSession(session: BedrockClientSession) {
                            clientSession = session
                            Log.d(TAG, "Gerçek sunucuya bağlandı: $targetHost:$targetPort")
                            setupClientHandlers(session)
                            onConnected(session)
                            flushQueue(session)
                        }
                    })

                bootstrap.connect(InetSocketAddress(targetHost, targetPort)).sync()
            } catch (e: Exception) {
                Log.e(TAG, "Sunucu bağlantısı başarısız", e)
                disconnect("Sunucu bağlantısı kurulamadı: ${e.message}")
            }
        }
    }

    private fun setupClientHandlers(session: BedrockClientSession) {
        session.addDisconnectHandler {
            Log.d(TAG, "Sunucu bağlantısı kesildi")
            onDisconnect()
        }
    }

    // ── Paket yönlendirme ────────────────────────────────────────────────

    /**
     * Sunucudan (Gerçek MC server) gelen paketi Minecraft'a ilet.
     * Listener'lar [RelayPacketListener.beforeClientBound] ile intercept edebilir.
     */
    fun clientBound(packet: BedrockPacket) {
        if (disconnected) return
        var intercepted = false
        for (l in listeners) {
            if (l.beforeClientBound(packet)) {
                intercepted = true
                break
            }
        }
        if (!intercepted) {
            serverSession.sendPacketImmediately(packet)
        }
        listeners.forEach { it.afterClientBound(packet) }
    }

    /**
     * Minecraft'tan gelen paketi gerçek sunucuya ilet.
     * Listener'lar [RelayPacketListener.beforeServerBound] ile intercept edebilir.
     */
    fun serverBound(packet: BedrockPacket) {
        if (disconnected) return
        val client = clientSession
        if (client == null) {
            packetQueue.offer(packet)
            return
        }
        var intercepted = false
        for (l in listeners) {
            if (l.beforeServerBound(packet)) {
                intercepted = true
                break
            }
        }
        if (!intercepted) {
            client.sendPacketImmediately(packet)
        }
        listeners.forEach { it.afterServerBound(packet) }
    }

    private fun flushQueue(client: BedrockClientSession) {
        var packet = packetQueue.poll()
        while (packet != null) {
            client.sendPacketImmediately(packet)
            packet = packetQueue.poll()
        }
    }

    // ── Listener yönetimi ────────────────────────────────────────────────

    fun addListener(listener: RelayPacketListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: RelayPacketListener) {
        listeners.remove(listener)
    }

    // ── Bağlantı kesme ───────────────────────────────────────────────────

    fun disconnect(reason: String = "Relay kapatıldı") {
        if (disconnected) return
        disconnected = true
        Log.i(TAG, "Disconnect: $reason")
        runCatching { serverSession.disconnect(reason) }
        runCatching { clientSession?.disconnect() }
        ioGroup.shutdownGracefully()
    }

    private fun onDisconnect() {
        if (disconnected) return
        disconnected = true
        listeners.forEach {
            runCatching { it.onDisconnect() }
        }
        runCatching { ioGroup.shutdownGracefully() }
    }
}
