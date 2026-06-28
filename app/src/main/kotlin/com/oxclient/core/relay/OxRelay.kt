package com.oxclient.core.relay

import android.util.Log
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

/**
 * OxRelay — CloudburstMC tabanlı Bedrock MITM relay.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * DEĞİŞENLER (WRelay entegrasyonu):
 *   1. RELAY_CODEC artık CodecRegistry.getLatestCodec() kullanıyor
 *      (önceki sabit 15-candidate listesi yerine — bkz. CodecRegistry.kt)
 *   2. childHandler artık BedrockServerInitializer DEĞİL,
 *      BedrockChannelInitializer<OxRelaySession.ServerSession> —
 *      bu, OxRelaySession.kt'deki onPacket() fix'inin çalışabilmesi için
 *      ZORUNLU (createSession0() olmadan custom session subclass'ı
 *      kullanılamıyor).
 *
 * Session yaratma sırası (KRİTİK — değişmedi):
 *   1. OxRelaySession oluştur (içinde ServerSession otomatik kurulur)
 *   2. sessions.add()
 *   3. ConnectionManager.setupSession(session, this)  ← listener'lar eklenir
 *   4. session.init()                                 ← codec + dc handler kurulur
 *   5. onSessionCreated callback
 *
 *   Server bağlantısı LoginPacketListener.onClientPacket(LoginPacket) içinde
 *   session.connectToServer() ile başlar — init()'de değil.
 * ═══════════════════════════════════════════════════════════════════════
 */
class OxRelay(
    private val localPort: Int = 19150
) {
    companion object {
        private const val TAG           = "OxRelay"
        private const val PONG_MOTD     = "oxse"
        private const val PONG_SUB_MOTD = "OxClient"

        val RELAY_CODEC: BedrockCodec by lazy { CodecRegistry.getLatestCodec() }
    }

    // ── State ─────────────────────────────────────────────────────────────

    @Volatile private var running      = false
    private var bossGroup    : NioEventLoopGroup? = null
    private var workerGroup  : NioEventLoopGroup? = null
    private var serverChannel: Channel?           = null

    val sessions = CopyOnWriteArrayList<OxRelaySession>()

    // internal set — TransferPacket interception bunu güncelleyebilsin diye
    // (bkz. GamingPacketListener.kt, sonraki bağlantı bu yeni hedefi kullanır)
    @Volatile var remoteHost: String = ""
        internal set
    @Volatile var remotePort: Int = 19132
        internal set

    /** Şu anki relay'in dışarıdan görünen local portu. TransferPacket geri-yönlendirme için. */
    val boundLocalPort: Int get() = localPort

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
                .childHandler(object : BedrockChannelInitializer<OxRelaySession.ServerSession>() {

                    override fun createSession0(peer: BedrockPeer, subClientId: Int): OxRelaySession.ServerSession {
                        Log.i(TAG, "Client bağlandı: ${peer.socketAddress}")

                        val session = OxRelaySession(
                            peer        = peer,
                            subClientId = subClientId,
                            remoteHost  = this@OxRelay.remoteHost,
                            remotePort  = this@OxRelay.remotePort,
                            relay       = this@OxRelay
                        )

                        // ── SIRALAMA KRİTİK ──────────────────────────────
                        sessions.add(session)

                        // 1. Listener'ları ekle (relay referansı ile)
                        ConnectionManager.setupSession(session, this@OxRelay)

                        // 2. Codec + disconnect handler kur
                        session.init()

                        // 3. Dış callback
                        onSessionCreated?.invoke(session)
                        // ─────────────────────────────────────────────────

                        return session.clientSession
                    }

                    override fun initSession(session: OxRelaySession.ServerSession) {
                        // Kurulum createSession0() içinde tamamlandı, burada ek iş yok.
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

    /**
     * TransferPacket geldiğinde yeni hedefi kaydeder — relay AYNI local porttan
     * dinlemeye devam eder, sadece sonraki gelen bağlantı (client'ın transfer
     * sonrası relay'e geri reconnect'i) bu yeni host:port'a yönlendirilir.
     * Bkz. GamingPacketListener.kt — TransferPacket case.
     */
    fun updateRemoteTarget(host: String, port: Int) {
        Log.i(TAG, "Remote target güncellendi: $host:$port (önceki: $remoteHost:$remotePort)")
        remoteHost = host
        remotePort = port
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
