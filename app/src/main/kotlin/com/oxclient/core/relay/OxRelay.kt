package com.oxclient.core.relay

import android.util.Log
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * OxRelay — CloudburstMC tabanlı MITM Bedrock relay sunucusu.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * Codec Çözümü (v2):
 *  - RELAY_CODEC: kütüphanede bulunan en yüksek versiyonlu codec seçilir.
 *  - Codec listesi v975'e (MC 26.20) kadar genişletildi.
 *  - updatePong(): AutoCodecListener client versiyonunu öğrenince pong'u
 *    dinamik olarak günceller → MC o anda doğru sürümü görür.
 *  - Fallback codec bulunamazsa protocolVersion=935, version="26.10" kullanılır.
 *
 * LAN Discovery (v3):
 *  - capture() sonrasında LanBroadcaster otomatik başlar.
 *  - MC'nin LAN sekmesinde "oxse" adıyla görünür, tıklanınca relay'e bağlanır.
 *  - updatePong() çağrıldığında LanBroadcaster da güncellenir.
 *  - stop() çağrıldığında LanBroadcaster da durur.
 * ─────────────────────────────────────────────────────────────────────────
 */
class OxRelay(
    private val localPort: Int = 19150
) {
    companion object {
        private const val TAG = "OxRelay"

        /**
         * Kütüphanede mevcut olan en yüksek codec'i döndürür.
         * Sırası önemli: en yeni → en eski.
         */
        val RELAY_CODEC: BedrockCodec by lazy { resolveLatestCodec() }

        private val CODEC_CANDIDATES = listOf(
            // MC 26.x serisi (2025-2026)
            "org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975",  // 26.20
            "org.cloudburstmc.protocol.bedrock.codec.v948.Bedrock_v948",  // 26.10 release
            "org.cloudburstmc.protocol.bedrock.codec.v935.Bedrock_v935",  // 26.10 preview
            "org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924",  // 26.0
            // MC 1.21.x serisi
            "org.cloudburstmc.protocol.bedrock.codec.v818.Bedrock_v818",  // 1.21.93 edu
            "org.cloudburstmc.protocol.bedrock.codec.v800.Bedrock_v800",
            "org.cloudburstmc.protocol.bedrock.codec.v786.Bedrock_v786",  // 1.21.80
            "org.cloudburstmc.protocol.bedrock.codec.v748.Bedrock_v748",  // 1.21.60
            "org.cloudburstmc.protocol.bedrock.codec.v729.Bedrock_v729",  // 1.21.50
            "org.cloudburstmc.protocol.bedrock.codec.v712.Bedrock_v712",
            "org.cloudburstmc.protocol.bedrock.codec.v686.Bedrock_v686",
            "org.cloudburstmc.protocol.bedrock.codec.v671.Bedrock_v671",
            "org.cloudburstmc.protocol.bedrock.codec.v662.Bedrock_v662",
            "org.cloudburstmc.protocol.bedrock.codec.v649.Bedrock_v649",
            "org.cloudburstmc.protocol.bedrock.codec.v630.Bedrock_v630",
        )

        private fun resolveLatestCodec(): BedrockCodec {
            for (className in CODEC_CANDIDATES) {
                try {
                    val codec = Class.forName(className)
                        .getField("CODEC")
                        .get(null) as? BedrockCodec
                    if (codec != null) {
                        Log.i(TAG, "Codec bulundu: $className (protocol=${codec.protocolVersion}, mc=${codec.minecraftVersion})")
                        return codec
                    }
                } catch (_: Exception) {}
            }

            Log.w(TAG, "Hiçbir codec bulunamadı! Fallback: protocol=935, mc=26.10")
            return BedrockCodec.builder()
                .protocolVersion(935)
                .minecraftVersion("26.10")
                .build()
        }
    }

    // ── Durum ─────────────────────────────────────────────────────────────

    @Volatile private var running = false
    private var bossGroup  : NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel?         = null

    val sessions = CopyOnWriteArrayList<OxRelaySession>()

    private var remoteHost: String = ""
    private var remotePort: Int    = 19132

    @Volatile private var currentPong: BedrockPong =
        buildPong(RELAY_CODEC.protocolVersion, RELAY_CODEC.minecraftVersion ?: "26.10")

    // ── API ───────────────────────────────────────────────────────────────

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

        bossGroup   = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup(4)

        try {
            val future = ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
                .option(RakChannelOption.RAK_ADVERTISEMENT, currentPong.toByteBuf())
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
                        ConnectionManager.setupSession(relaySession)
                        relaySession.init()
                        onSessionCreated?.invoke(relaySession)
                    }
                })
                .bind(bindAddress)
                .syncUninterruptibly()

            serverChannel = future.channel()
            running = true

            Log.i(TAG, "OxRelay başlatıldı → 0.0.0.0:$localPort ↔ $remoteHost:$remotePort  codec=${RELAY_CODEC.protocolVersion}")

            // ── LAN Discovery başlat ──────────────────────────────────────
            LanBroadcaster.start(
                relayPort       = localPort,
                motd            = currentPong.motd            ?: "OxClient",
                subMotd         = currentPong.subMotd         ?: "OxClient",
                protocolVersion = RELAY_CODEC.protocolVersion,
                mcVersion       = RELAY_CODEC.minecraftVersion ?: "26.10",
                maxPlayers      = 10
            )

        } catch (e: Exception) {
            Log.e(TAG, "OxRelay başlatılamadı", e)
            shutdownGroups()
            throw e
        }
    }

    /**
     * AutoCodecListener tarafından çağrılır: MC client'ın gerçek protokol versiyonu
     * öğrenilince hem RakNet pong hem de LanBroadcaster güncellenir.
     */
    fun updatePong(protocolVersion: Int, minecraftVersion: String) {
        if (!running) return
        currentPong = buildPong(protocolVersion, minecraftVersion)

        // RakNet advertisement güncelle
        try {
            serverChannel?.config()?.setOption(
                RakChannelOption.RAK_ADVERTISEMENT, currentPong.toByteBuf()
            )
            Log.i(TAG, "RakNet pong güncellendi: protocol=$protocolVersion  mc=$minecraftVersion")
        } catch (e: Exception) {
            Log.w(TAG, "RakNet pong güncellenemedi: ${e.message}")
        }

        // LanBroadcaster da güncelle — artık doğru version yayımlanır
        LanBroadcaster.updateInfo(
            protocolVersion = protocolVersion,
            mcVersion       = minecraftVersion,
            motd            = currentPong.motd ?: "OxClient"
        )
    }

    fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "OxRelay durduruluyor…")

        // LAN discovery durdur
        LanBroadcaster.stop()

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
        bossGroup     = null
        workerGroup   = null
        serverChannel = null
    }

    val isRunning: Boolean get() = running

    // ── Pong ─────────────────────────────────────────────────────────────

    private fun buildPong(protocolVersion: Int, minecraftVersion: String) =
        BedrockPong()
            .edition("MCPE")
            .motd("oxse")
            .subMotd("OxClient")
            .playerCount(0)
            .maximumPlayerCount(10)
            .gameType("Survival")
            .nintendoLimited(false)
            .protocolVersion(protocolVersion)
            .version(minecraftVersion)
            .ipv4Port(localPort)
            .ipv6Port(localPort)
}
