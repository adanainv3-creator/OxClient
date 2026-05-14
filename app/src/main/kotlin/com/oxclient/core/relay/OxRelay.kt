package com.oxclient.core.relay

import android.util.Log
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * OxRelay — CloudburstMC tabanlı MITM Bedrock relay sunucusu.
 *
 * Akış:
 *   Minecraft Client → [OxRelay / BedrockServer:localPort] → [OxRelaySession] → Gerçek Sunucu
 *
 * Kullanım:
 *   val relay = OxRelay(localPort = 19150)
 *   relay.capture(remoteHost = "play.example.com", remotePort = 19132)
 *   ...
 *   relay.stop()
 */
class OxRelay(
    private val localPort: Int = 19150
) {
    companion object {
        private const val TAG = "OxRelay"

        /**
         * Relay'in hangi Bedrock codec versiyonunu kullanacağı.
         * Client'ın gönderdiği LoginPacket'ten otomatik algılanır (AutoCodecListener),
         * bu değer sadece pong advertise için kullanılır.
         */
        val RELAY_CODEC: BedrockCodec = resolveLatestCodec()

        private fun resolveLatestCodec(): BedrockCodec {
            // CloudburstMC Protocol 3.x — codec listesi dinamik; en son versiyon alınır.
            return try {
                val clazz = Class.forName("org.cloudburstmc.protocol.bedrock.codec.v786.Bedrock_v786")
                clazz.getField("CODEC").get(null) as BedrockCodec
            } catch (_: Exception) {
                try {
                    val clazz = Class.forName("org.cloudburstmc.protocol.bedrock.codec.v748.Bedrock_v748")
                    clazz.getField("CODEC").get(null) as BedrockCodec
                } catch (_: Exception) {
                    // Son çare: ilk kayıtlı codec
                    org.cloudburstmc.protocol.bedrock.codec.BedrockCodec.builder()
                        .protocolVersion(748)
                        .build()
                }
            }
        }
    }

    // ── Durum ─────────────────────────────────────────────────────────────

    @Volatile private var running = false
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null

    /** Aktif tüm MITM oturumları */
    val sessions = CopyOnWriteArrayList<OxRelaySession>()

    private var remoteHost: String = ""
    private var remotePort: Int    = 19132

    // ── API ───────────────────────────────────────────────────────────────

    /**
     * Relay'i başlatır ve gelen her bağlantıyı [remoteHost]:[remotePort]'a köprüler.
     * @param onSessionCreated  Her yeni MITM oturumu oluşturulduğunda çağrılır.
     */
    fun capture(
        remoteHost: String,
        remotePort: Int = 19132,
        onSessionCreated: ((OxRelaySession) -> Unit)? = null
    ) {
        if (running) {
            Log.w(TAG, "Relay zaten çalışıyor — önce stop() çağırın")
            return
        }

        this.remoteHost = remoteHost
        this.remotePort = remotePort

        val bindAddress = InetSocketAddress("0.0.0.0", localPort)

        val pong = BedrockPong()
            .edition("MCPE")
            .motd("OxClient Relay")
            .subMotd("MITM Active")
            .playerCount(0)
            .maximumPlayerCount(1)
            .gameType("Survival")
            .protocolVersion(RELAY_CODEC.protocolVersion)
            .version(RELAY_CODEC.minecraftVersion)
            .ipv4Port(localPort)

        bossGroup  = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup(4)

        try {
            ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
                .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
                .group(bossGroup, workerGroup)
                .childHandler(object : BedrockServerInitializer() {
                    override fun initSession(clientSession: BedrockServerSession) {
                        Log.i(TAG, "Yeni client bağlantısı: ${clientSession.socketAddress}")
                        val relaySession = OxRelaySession(
                            clientSession = clientSession,
                            remoteHost    = this@OxRelay.remoteHost,
                            remotePort    = this@OxRelay.remotePort,
                            relay         = this@OxRelay
                        )
                        sessions.add(relaySession)
                        relaySession.init()
                        onSessionCreated?.invoke(relaySession)
                    }
                })
                .bind(bindAddress)
                .syncUninterruptibly()

            running = true
            Log.i(TAG, "OxRelay başlatıldı → 0.0.0.0:$localPort ↔ $remoteHost:$remotePort")

        } catch (e: Exception) {
            Log.e(TAG, "OxRelay başlatılamadı", e)
            shutdownGroups()
            throw e
        }
    }

    /** Relay'i ve tüm aktif oturumları durdurur. */
    fun stop() {
        if (!running) return
        running = false

        Log.i(TAG, "OxRelay durduruluyor…")
        sessions.forEach { it.disconnect("Relay kapatıldı") }
        sessions.clear()
        shutdownGroups()
        Log.i(TAG, "OxRelay durduruldu")
    }

    internal fun removeSession(session: OxRelaySession) {
        sessions.remove(session)
    }

    private fun shutdownGroups() {
        try { bossGroup?.shutdownGracefully()?.sync() }  catch (_: Exception) {}
        try { workerGroup?.shutdownGracefully()?.sync() } catch (_: Exception) {}
        bossGroup  = null
        workerGroup = null
    }

    val isRunning: Boolean get() = running
}
