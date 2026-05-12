package com.oxclient.core.relay

import com.oxclient.core.relay.listener.OxPacketListener
import com.oxclient.core.relay.listener.AutoCodecListener
import com.oxclient.core.relay.listener.LoginPacketListener
import com.oxclient.core.relay.listener.GamingPacketListener
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import kotlinx.coroutines.*
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRateLimiter
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import kotlin.random.Random

/**
 * OxRelay — CloudburstMC Protocol tabanlı MITM relay.
 * WRelay'den adapte edildi, OxClient'a özgü entegrasyon eklendi.
 *
 * Kullanım:
 *   OxRelay(localPort = 19132)
 *     .capture(remoteHost = "2b2tpe.org", remotePort = 19132) { session ->
 *         // session üzerinden listener ekle vb.
 *     }
 */
class OxRelay(
    val localPort: Int = 19132,
    val advertisement: BedrockPong = buildDefaultPong()
) {

    companion object {
        fun buildDefaultPong(): BedrockPong = BedrockPong()
            .edition("MCPE")
            .gameType("Survival")
            .version(OxCodecRegistry.latestCodec.minecraftVersion)
            .protocolVersion(OxCodecRegistry.latestCodec.protocolVersion)
            .motd("OxClient")
            .playerCount(0)
            .maximumPlayerCount(1)
            .subMotd("OxClient Relay")
            .nintendoLimited(false)
    }

    val isRunning: Boolean get() = channelFuture != null

    private var channelFuture: ChannelFuture? = null
    private var eventLoopGroup: NioEventLoopGroup? = null

    var session: OxRelaySession? = null
        internal set

    internal var connectionManager: ConnectionManager? = null

    var remoteHost: String = ""
        internal set
    var remotePort: Int = 19132
        internal set

    // ── Başlatma ──────────────────────────────────────────────────────────

    fun capture(
        remoteHost: String,
        remotePort: Int = 19132,
        onSessionCreated: OxRelaySession.() -> Unit
    ): OxRelay {
        if (isRunning) return this

        this.remoteHost = remoteHost
        this.remotePort = remotePort

        advertisement
            .ipv4Port(localPort)
            .ipv6Port(localPort)

        eventLoopGroup = NioEventLoopGroup()

        ServerBootstrap()
            .group(eventLoopGroup)
            .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
            .option(RakChannelOption.RAK_ADVERTISEMENT, advertisement.toByteBuf())
            .option(RakChannelOption.RAK_GUID, Random.nextLong())
            .childHandler(object : BedrockChannelInitializer<OxRelaySession.ServerSession>() {

                override fun createSession0(peer: BedrockPeer, subClientId: Int): OxRelaySession.ServerSession {
                    return OxRelaySession(peer, subClientId, this@OxRelay)
                        .also { s ->
                            session = s
                            connectionManager = ConnectionManager(s)
                            // Standart listener'ları ekle
                            s.listeners.add(AutoCodecListener(s))
                            s.listeners.add(LoginPacketListener(s))
                            s.listeners.add(GamingPacketListener(s))
                            // Kullanıcı callback'i
                            s.onSessionCreated()
                        }
                        .server
                }

                override fun initSession(session: OxRelaySession.ServerSession) {}

                override fun preInitChannel(channel: Channel) {
                    channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND)
                    super.preInitChannel(channel)
                }
            })
            .localAddress("0.0.0.0", localPort)
            .bind()
            .awaitUninterruptibly()
            .also { f ->
                f.channel().pipeline().remove(RakServerRateLimiter.NAME)
                channelFuture = f
            }

        return this
    }

    // ── Sunucuya bağlan ───────────────────────────────────────────────────

    internal fun connectToServer(onConnected: OxRelaySession.ClientSession.() -> Unit) {
        val mgr  = connectionManager ?: error("ConnectionManager hazır değil")
        val host = remoteHost
        val port = remotePort

        CoroutineScope(Dispatchers.IO).launch {
            val result = mgr.connectToServer(host, port, onConnected)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "bilinmeyen hata"
                android.util.Log.e("OxRelay", "Sunucuya bağlanılamadı: $msg")
                session?.server?.disconnect("Sunucuya bağlanılamadı: $msg")
            }
        }
    }

    // ── Durdur ────────────────────────────────────────────────────────────

    fun stop() {
        try {
            session?.client?.disconnect()
            session?.server?.disconnect()
            connectionManager?.cleanup()
            channelFuture?.channel()?.close()?.sync()
        } catch (e: Exception) {
            android.util.Log.e("OxRelay", "Stop hatası: ${e.message}")
        } finally {
            eventLoopGroup?.shutdownGracefully()
            eventLoopGroup  = null
            channelFuture   = null
            session         = null
            connectionManager = null
            PacketEventBus.clear()
        }
    }
}
