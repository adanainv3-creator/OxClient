package com.oxclient.core.relay

import com.oxclient.core.relay.codec.CodecRegistry
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class OxRelay(
    private val localPort: Int = 19150
) {
    companion object {
        private const val TAG           = "OxRelay"
        private const val PONG_MOTD     = "oxse"
        private const val PONG_SUB_MOTD = "OxClient"

        val RELAY_CODEC: BedrockCodec by lazy { CodecRegistry.getLatestCodec() }
    }

    @Volatile private var running      = false
    private var bossGroup    : NioEventLoopGroup? = null
    private var workerGroup  : NioEventLoopGroup? = null
    private var serverChannel: Channel?           = null

    val sessions = CopyOnWriteArrayList<OxRelaySession>()

    @Volatile var remoteHost: String = ""
        internal set
    @Volatile var remotePort: Int = 19132
        internal set

    val boundLocalPort: Int get() = localPort

    fun capture(
        remoteHost       : String,
        remotePort       : Int = 19132,
        onSessionCreated : ((OxRelaySession) -> Unit)? = null
    ) {
        if (running) { return }

        this.remoteHost = remoteHost
        this.remotePort = remotePort

        bossGroup   = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup(4)

        try {
            val pong = buildPong(RELAY_CODEC.protocolVersion, RELAY_CODEC.minecraftVersion ?: "1.21.60")

            val future = ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
                .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
                .group(bossGroup, workerGroup)
                .childHandler(object : BedrockChannelInitializer<OxRelaySession.ServerSession>() {

                    override fun createSession0(peer: BedrockPeer, subClientId: Int): OxRelaySession.ServerSession {
                        val session = OxRelaySession(
                            peer        = peer,
                            subClientId = subClientId,
                            remoteHost  = this@OxRelay.remoteHost,
                            remotePort  = this@OxRelay.remotePort,
                            relay       = this@OxRelay
                        )

                        sessions.add(session)

                        ConnectionManager.setupSession(session, this@OxRelay)

                        session.init()

                        onSessionCreated?.invoke(session)

                        return session.clientSession
                    }

                    override fun initSession(session: OxRelaySession.ServerSession) {
                    }

                    override fun preInitChannel(channel: Channel) {
                        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND)
                        super.preInitChannel(channel)
                    }
                })
                .bind(InetSocketAddress("0.0.0.0", localPort))
                .syncUninterruptibly()

            serverChannel = future.channel()
            running = true

            LanBroadcaster.start(
                relayPort       = localPort,
                motd            = PONG_MOTD,
                subMotd         = PONG_SUB_MOTD,
                protocolVersion = RELAY_CODEC.protocolVersion,
                mcVersion       = RELAY_CODEC.minecraftVersion ?: "1.21.60",
                maxPlayers      = 10
            )

        } catch (e: Exception) {
            shutdownGroups()
            throw e
        }
    }

    fun updateRemoteTarget(host: String, port: Int) {
        remoteHost = host
        remotePort = port
    }

    fun updatePong(protocolVersion: Int, minecraftVersion: String) {
        if (!running) return
        val pong = buildPong(protocolVersion, minecraftVersion)
        try {
            serverChannel?.config()?.setOption(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
        } catch (e: Exception) {
        }
        LanBroadcaster.updateInfo(
            protocolVersion = protocolVersion,
            mcVersion       = minecraftVersion,
            motd            = PONG_MOTD
        )
    }

    fun stop() {
        if (!running) return
        running = false
        LanBroadcaster.stop()
        sessions.toList().forEach { it.disconnect("Relay kapatıldı") }
        sessions.clear()
        shutdownGroups()
    }

    internal fun removeSession(session: OxRelaySession) = sessions.remove(session)

    private fun shutdownGroups() {
        try { bossGroup?.shutdownGracefully()?.sync()  } catch (_: Exception) {}
        try { workerGroup?.shutdownGracefully()?.sync() } catch (_: Exception) {}
        bossGroup     = null
        workerGroup   = null
        serverChannel = null
    }

    val isRunning: Boolean get() = running

    private fun buildPong(protocol: Int, mc: String) = BedrockPong()
        .edition("MCPE")
        .motd(PONG_MOTD)
        .subMotd(PONG_SUB_MOTD)
        .playerCount(0)
        .maximumPlayerCount(10)
        .gameType("Survival")
        .nintendoLimited(false)
        .protocolVersion(protocol)
        .version(mc)
        .ipv4Port(localPort)
        .ipv6Port(localPort)
}
