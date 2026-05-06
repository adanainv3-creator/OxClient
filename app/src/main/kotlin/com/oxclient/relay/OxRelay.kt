package com.oxclient.relay

import android.util.Log
import com.oxclient.relay.listener.AutoCodecPacketListener
import com.oxclient.relay.listener.GamingPacketHandler
import com.oxclient.relay.listener.OnlineLoginPacketListener
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer
import java.net.InetSocketAddress

/**
 * OxRelay — Bedrock Man-in-the-Middle relay.
 *
 * Local UDP :19132 üzerinde bir RakNet sunucusu açar.
 * Minecraft bu relay'e bağlanır, relay gerçek sunucuya bağlanır.
 * Her bağlantı için bir [OxRelaySession] oluşturulur.
 */
class OxRelay(
    private val targetHost: String,
    private val targetPort: Int
) {
    companion object {
        private const val TAG       = "OxRelay"
        const val  LOCAL_PORT       = 19132
        private const val RAKNET_ID = 0x4f78436c69656e74L // "OxClient"
    }

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bossGroup  = NioEventLoopGroup(1)
    private val workerGroup= NioEventLoopGroup(4)
    private var running    = false

    /**
     * Relay sunucusunu başlatır.
     * [thread] ile ayrı bir thread'de çağrılmalı.
     */
    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "Relay başlatılıyor → $targetHost:$targetPort")

        try {
            ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
                .option(RakChannelOption.RAK_ADVERTISEMENT, buildPong())
                .option(RakChannelOption.RAK_GUID, RAKNET_ID)
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, 10)
                .childHandler(object : BedrockServerInitializer() {
                    override fun initSession(session: BedrockServerSession) {
                        Log.d(TAG, "Yeni bağlantı: ${session.socketAddress}")
                        capture(session)
                    }
                })
                .bind(InetSocketAddress("0.0.0.0", LOCAL_PORT))
                .sync()
                .channel()

            Log.i(TAG, "Relay dinliyor — 0.0.0.0:$LOCAL_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Relay başlatma hatası", e)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "Relay durduruluyor")
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
    }

    val isRunning get() = running

    /**
     * Minecraft → relay bağlantısı kurulduğunda çağrılır.
     * Relay → gerçek sunucu bağlantısı async olarak kurulur.
     */
    private fun capture(clientSession: BedrockServerSession) {
        scope.launch {
            val relaySession = OxRelaySession(
                serverSession = clientSession,
                targetHost    = targetHost,
                targetPort    = targetPort
            )

            // Listener'ları kaydet
            relaySession.addListener(AutoCodecPacketListener(relaySession))
            relaySession.addListener(OnlineLoginPacketListener(relaySession))
            relaySession.addListener(GamingPacketHandler(relaySession))

            relaySession.init()
        }
    }

    // MOTD / Pong paketi
    private fun buildPong(): ByteArray {
        val motd = "OxClient Relay;Minecraft Bedrock;1;1.21.0;0;10"
        return motd.toByteArray(Charsets.UTF_8)
    }
}
