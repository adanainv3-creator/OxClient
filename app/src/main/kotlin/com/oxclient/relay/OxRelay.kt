package com.oxclient.relay

import com.oxclient.relay.codec.OxCodecRegistry
import com.oxclient.relay.connection.OxConnectionManager
import com.oxclient.relay.session.OxRelaySession
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
import timber.log.Timber
import kotlin.random.Random

class OxRelay {

    companion object {
        val DEFAULT_CODEC: BedrockCodec get() = OxCodecRegistry.latestCodec
        const val LOCAL_PORT = 19132
    }

    val localAddress: OxAddress = OxAddress("0.0.0.0", LOCAL_PORT)

    private var channelFuture    : ChannelFuture?       = null
    private var bossGroup        : NioEventLoopGroup?   = null
    private var activeSession    : OxRelaySession?      = null
    var connectionManager        : OxConnectionManager? = null
        internal set

    val isRunning: Boolean get() = channelFuture != null

    fun start(
        remoteAddress : OxAddress,
        mcToken       : String,
        gamertag      : String,
        onSessionReady: ((OxRelaySession) -> Unit)? = null
    ) {
        if (isRunning) { Timber.w("[OxRelay] Zaten çalışıyor"); return }

        val codec = DEFAULT_CODEC
        bossGroup = NioEventLoopGroup(2)
        Timber.i("[OxRelay] Başlatılıyor → local=$localAddress  remote=$remoteAddress  codec=${codec.minecraftVersion}")

        try {
            val pong = BedrockPong()
                .edition("MCPE")
                .gameType("Survival")
                .version(codec.minecraftVersion)
                .protocolVersion(codec.protocolVersion)
                .motd("§aOxClient §7Relay")
                .playerCount(0)
                .maximumPlayerCount(1)
                .subMotd("§7v2.1.0")
                .nintendoLimited(false)
                .ipv4Port(LOCAL_PORT)
                .ipv6Port(LOCAL_PORT)

            ServerBootstrap()
                .group(bossGroup!!)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
                .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
                .option(RakChannelOption.RAK_GUID, Random.nextLong())
                .childHandler(object : BedrockChannelInitializer<OxRelaySession.ServerSide>() {

                    override fun createSession0(peer: BedrockPeer, sub: Int): OxRelaySession.ServerSide {
                        val session = OxRelaySession(peer, sub, this@OxRelay)
                        activeSession     = session
                        connectionManager = OxConnectionManager(session)
                        session.addDefaultListeners(remoteAddress, mcToken, gamertag)
                        onSessionReady?.invoke(session)
                        return session.serverSide
                    }

                    override fun initSession(session: OxRelaySession.ServerSide) {}

                    override fun preInitChannel(channel: Channel) {
                        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND)
                        super.preInitChannel(channel)
                    }
                })
                .localAddress(localAddress.toInetSocketAddress())
                .bind()
                .awaitUninterruptibly()
                .also { future ->
                    if (!future.isSuccess) {
                        Timber.e(future.cause(), "[OxRelay] Bind başarısız")
                        cleanup(); return
                    }
                    runCatching { future.channel().pipeline().remove(RakServerRateLimiter.NAME) }
                    channelFuture = future
                    Timber.i("[OxRelay] ✓ Dinleniyor: $localAddress")
                }
        } catch (e: Exception) {
            Timber.e(e, "[OxRelay] Başlatma hatası")
            cleanup()
        }
    }

    fun stop() {
        Timber.i("[OxRelay] Durduruluyor…")
        runCatching { activeSession?.serverSide?.disconnect("OxClient durduruldu") }
        runCatching { activeSession?.clientSide?.disconnect("OxClient durduruldu") }
        cleanup()
    }

    private fun cleanup() {
        runCatching { channelFuture?.channel()?.close()?.sync() }
        runCatching { bossGroup?.shutdownGracefully()?.sync() }
        runCatching { connectionManager?.cleanup() }
        channelFuture     = null
        bossGroup         = null
        activeSession     = null
        connectionManager = null
    }

    internal fun connectToServer(address: OxAddress, onConnected: OxRelaySession.ClientSide.() -> Unit) {
        val manager = connectionManager ?: return
        CoroutineScope(Dispatchers.IO).launch {
            manager.connect(address, onConnected).onFailure { e ->
                Timber.e(e, "[OxRelay] Sunucuya bağlanılamadı")
                activeSession?.serverSide?.disconnect("Sunucu bağlantısı başarısız: ${e.message}")
            }
        }
    }
}
