package com.oxclient.relay.connection

import com.oxclient.relay.OxAddress
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
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import timber.log.Timber
import kotlin.random.Random

class OxConnectionManager(private val session: OxRelaySession) {

    private var group  : NioEventLoopGroup? = null
    private var channel: Channel?           = null

    suspend fun connect(
        address    : OxAddress,
        onConnected: OxRelaySession.ClientSide.() -> Unit
    ): Result<OxRelaySession.ClientSide> = withContext(Dispatchers.IO) {
        try {
            val g = NioEventLoopGroup(1)
            group = g
            var connected: OxRelaySession.ClientSide? = null

            val future = Bootstrap()
                .group(g)
                .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_CONNECT_TIMEOUT, 10_000L)
                .option(RakChannelOption.RAK_GUID, Random.nextLong())
                .handler(object : BedrockChannelInitializer<OxRelaySession.ClientSide>() {

                    override fun createSession0(peer: BedrockPeer, sub: Int): OxRelaySession.ClientSide {
                        val cs = session.ClientSide(peer, sub)
                        session.clientSide = cs
                        connected = cs
                        cs.onConnected()
                        return cs
                    }

                    override fun initSession(s: OxRelaySession.ClientSide) {}

                    // PacketDirection 3.0.0.Beta1'de kaldırıldı — preInitChannel override gerekmiyor
                })
                .connect(address.toInetSocketAddress())
                .awaitUninterruptibly()

            channel = future.channel()

            if (!future.isSuccess) {
                cleanup()
                return@withContext Result.failure(
                    future.cause() ?: RuntimeException("Bağlantı başarısız: $address")
                )
            }

            val cs = connected
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
