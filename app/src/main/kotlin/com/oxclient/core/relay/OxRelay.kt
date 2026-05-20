package com.oxclient.core.relay

import android.util.Log
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * OxRelay — CloudburstMC tabanlı Bedrock MITM relay.
 *
 * Session yaratma sırası (KRİTİK):
 *   1. OxRelaySession oluştur
 *   2. sessions.add()
 *   3. ConnectionManager.setupSession(session, this)  ← listener'lar eklenir
 *   4. session.init()                                 ← client handler kurulur
 *   5. onSessionCreated callback
 *
 *   Server bağlantısı LoginPacketListener.onClientPacket(LoginPacket) içinde
 *   session.connectToServer() ile başlar — init()'de değil.
 */
class OxRelay(
    private val localPort: Int = 19150
) {
    companion object {
        private const val TAG           = "OxRelay"
        private const val PONG_MOTD     = "oxse"
        private const val PONG_SUB_MOTD = "OxClient"

        val RELAY_CODEC: BedrockCodec by lazy { resolveLatestCodec() }

        private val CODEC_CANDIDATES = listOf(
            "org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975",
            "org.cloudburstmc.protocol.bedrock.codec.v948.Bedrock_v948",
            "org.cloudburstmc.protocol.bedrock.codec.v935.Bedrock_v935",
            "org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924",
            "org.cloudburstmc.protocol.bedrock.codec.v818.Bedrock_v818",
            "org.cloudburstmc.protocol.bedrock.codec.v800.Bedrock_v800",
            "org.cloudburstmc.protocol.bedrock.codec.v786.Bedrock_v786",
            "org.cloudburstmc.protocol.bedrock.codec.v748.Bedrock_v748",
            "org.cloudburstmc.protocol.bedrock.codec.v729.Bedrock_v729",
            "org.cloudburstmc.protocol.bedrock.codec.v712.Bedrock_v712",
            "org.cloudburstmc.protocol.bedrock.codec.v686.Bedrock_v686",
            "org.cloudburstmc.protocol.bedrock.codec.v671.Bedrock_v671",
            "org.cloudburstmc.protocol.bedrock.codec.v662.Bedrock_v662",
            "org.cloudburstmc.protocol.bedrock.codec.v649.Bedrock_v649",
            "org.cloudburstmc.protocol.bedrock.codec.v630.Bedrock_v630",
        )

        private fun resolveLatestCodec(): BedrockCodec {
            for (cls in CODEC_CANDIDATES) {
                try {
                    val codec = Class.forName(cls).getField("CODEC").get(null) as? BedrockCodec
                    if (codec != null) {
                        Log.i(TAG, "RELAY_CODEC: $cls | protocol=${codec.protocolVersion} mc=${codec.minecraftVersion}")
                        return codec
                    }
                } catch (_: Exception) {}
            }
            Log.w(TAG, "Codec bulunamadı — fallback protocol=748 mc=1.21.60")
            return BedrockCodec.builder().protocolVersion(748).minecraftVersion("1.21.60").build()
        }
    }

    // ── State ─────────────────────────────────────────────────────────────

    @Volatile private var running      = false
    private var bossGroup    : NioEventLoopGroup? = null
    private var workerGroup  : NioEventLoopGroup? = null
    private var serverChannel: Channel?           = null

    val sessions = CopyOnWriteArrayList<OxRelaySession>()

    @Volatile private var remoteHost = ""
    @Volatile private var remotePort = 19132

    // ── capture() ────────────────────────────────────────────────────────

    fun capture(
        remoteHost       : String,
        remotePort       : Int = 19132,
        onSessionCreated : ((OxRelaySession) -> Unit)? = null
    ) {
        if (running) { Log.w(TAG, "Relay zaten çalışıyor"); return }

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
                .childHandler(object : BedrockServerInitializer() {
                    override fun initSession(clientSession: BedrockServerSession) {
                        Log.i(TAG, "Client bağlandı: ${clientSession.socketAddress}")

                        val session = OxRelaySession(
                            clientSession = clientSession,
                            remoteHost    = this@OxRelay.remoteHost,
                            remotePort    = this@OxRelay.remotePort,
                            relay         = this@OxRelay
                        )

                        // ── SIRALAMA KRİTİK ──────────────────────────────
                        sessions.add(session)

                        // 1. Listener'ları ekle (relay referansı ile)
                        ConnectionManager.setupSession(session, this@OxRelay)

                        // 2. Client handler'ı kur (server bağlantısı yok henüz)
                        session.init()

                        // 3. Dış callback
                        onSessionCreated?.invoke(session)
                        // ─────────────────────────────────────────────────
                    }
                })
                .bind(InetSocketAddress("0.0.0.0", localPort))
                .syncUninterruptibly()

            serverChannel = future.channel()
            running = true

            Log.i(TAG, "OxRelay aktif → 0.0.0.0:$localPort ↔ $remoteHost:$remotePort | codec=${RELAY_CODEC.protocolVersion}")

            // LAN Discovery
            LanBroadcaster.start(
                relayPort       = localPort,
                motd            = PONG_MOTD,
                subMotd         = PONG_SUB_MOTD,
                protocolVersion = RELAY_CODEC.protocolVersion,
                mcVersion       = RELAY_CODEC.minecraftVersion ?: "1.21.60",
                maxPlayers      = 10
            )

        } catch (e: Exception) {
            Log.e(TAG, "OxRelay başlatılamadı", e)
            shutdownGroups()
            throw e
        }
    }

    // ── Pong / LAN güncelleme ─────────────────────────────────────────────

    /**
     * AutoCodecListener client protokolünü öğrenince çağırır.
     * RakNet advertisement ve LanBroadcaster güncellenir.
     */
    fun updatePong(protocolVersion: Int, minecraftVersion: String) {
        if (!running) return
        val pong = buildPong(protocolVersion, minecraftVersion)
        try {
            serverChannel?.config()?.setOption(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
            Log.i(TAG, "Pong güncellendi: protocol=$protocolVersion mc=$minecraftVersion")
        } catch (e: Exception) {
            Log.w(TAG, "Pong güncelleme hatası: ${e.message}")
        }
        LanBroadcaster.updateInfo(
            protocolVersion = protocolVersion,
            mcVersion       = minecraftVersion,
            motd            = PONG_MOTD
        )
    }

    // ── stop() ───────────────────────────────────────────────────────────

    fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "OxRelay durduruluyor…")
        LanBroadcaster.stop()
        sessions.toList().forEach { it.disconnect("Relay kapatıldı") }
        sessions.clear()
        shutdownGroups()
        Log.i(TAG, "OxRelay durduruldu")
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

    // ── Pong builder ─────────────────────────────────────────────────────

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
