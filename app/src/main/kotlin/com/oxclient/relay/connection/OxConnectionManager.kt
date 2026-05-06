package com.oxclient.relay.connection

import com.oxclient.relay.OxAddress
import com.oxclient.relay.OxRelay
import com.oxclient.relay.session.OxRelaySession
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import timber.log.Timber
import kotlin.random.Random

/**
 * OxConnectionManager — OxRelaySession için gerçek MC sunucusuna çıkan bağlantıyı kurar.
 */
class OxConnectionManager(
    private val session: OxRelaySession
) {
    private var group  : NioEventLoopGroup? = null
    private var channel: Channel?           = null

    suspend fun connect(
        address    : OxAddress,
        onConnected: OxRelaySession.ClientSide.() -> Unit
    ): Result<OxRelaySession.ClientSide> = withContext(Dispatchers.IO) {
        try {
            val g = NioEventLoopGroup(1)
            group = g

            var connectedSide: OxRelaySession.ClientSide? = null

            val bootstrap = Bootstrap()
                .group(g)
                .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_CONNECT_TIMEOUT, 10_000L)
                .option(RakChannelOption.RAK_GUID, Random.nextLong())
                .handler(object : BedrockChannelInitializer<OxRelaySession.ClientSide>() {

                    override fun createSession0(peer: BedrockPeer, subClientId: Int): OxRelaySession.ClientSide {
                        val clientSide = session.ClientSide(peer, subClientId)
                        session.clientSide = clientSide
                        connectedSide      = clientSide
                        clientSide.onConnected()
                        return clientSide
                    }

                    override fun initSession(session: OxRelaySession.ClientSide) {}

                    override fun preInitChannel(ch: Channel) {
                        ch.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND)
                        super.preInitChannel(ch)
                    }
                })

            Timber.i("[ConnManager] Sunucuya bağlanılıyor: $address")

            val future = bootstrap.connect(address.toInetSocketAddress()).awaitUninterruptibly()
            channel    = future.channel()

            if (!future.isSuccess) {
                cleanup()
                return@withContext Result.failure(
                    future.cause() ?: RuntimeException("Bağlantı başarısız: $address")
                )
            }

            val cs = connectedSide
            if (cs != null) {
                Timber.i("[ConnManager] ✓ Bağlandı: $address")
                Result.success(cs)
            } else {
                Result.failure(RuntimeException("ClientSide oluşturulamadı"))
            }

        } catch (e: Exception) {
            Timber.e(e, "[ConnManager] Bağlantı hatası")
            cleanup()
            Result.failure(e)
        }
    }

    fun cleanup() {
        runCatching { channel?.close()?.sync() }
        runCatching { group?.shutdownGracefully() }
        channel = null
        group   = null
    }
}
