package com.oxclient.proxy

import android.util.Log
import com.oxclient.session.ServerConfig
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * BedrockRelay — Ana MITM Motoru
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  Minecraft App                                               │
 * │      │  UDP :19132                                           │
 * │      ▼                                                       │
 * │  RakNetServer  ←──────────────────── yerel proxy            │
 * │      │                                                       │
 * │      ▼                                                       │
 * │  BedrockRelay  ←─── PacketProcessor ←─── Modüller           │
 * │      │                                                       │
 * │      ▼                                                       │
 * │  RakNetClient  ──────────────────────► Hive / CubeCraft      │
 * └──────────────────────────────────────────────────────────────┘
 *
 * Her MC istemcisi → 1 RakNetServer oturumu + 1 RakNetClient (upstream).
 */
class BedrockRelay {
    private val TAG = "BedrockRelay"

    // ── Sunucu kimliği (MOTD'da kullanılır) ──────────────────────────────
    val serverGuid: Long = System.nanoTime()

    // ── Hedef sunucu ──────────────────────────────────────────────────────
    @Volatile var targetServer: ServerConfig? = null

    // ── Bileşenler ────────────────────────────────────────────────────────
    lateinit var localServer: RakNetServer
        private set

    // clientAddress → upstream client
    private val upstreamClients  = ConcurrentHashMap<InetSocketAddress, RakNetClient>()
    // clientAddress → upstream session
    private val upstreamSessions = ConcurrentHashMap<InetSocketAddress, RakNetSession>()

    // İstatistik
    @Volatile var totalBytesIn  = 0L
    @Volatile var totalBytesOut = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────
    //  BAŞLATMA / DURDURMA
    // ─────────────────────────────────────────────────────────────────────

    fun start(target: ServerConfig) {
        targetServer = target
        localServer  = RakNetServer(this, RakNetConstants.LOCAL_BIND_PORT)
        localServer.start()
        Log.i(TAG, "BedrockRelay başlatıldı → ${target.host}:${target.port}")
    }

    fun stop() {
        upstreamClients.values.forEach { it.disconnect() }
        upstreamClients.clear()
        upstreamSessions.clear()

        if (::localServer.isInitialized) localServer.stop()
        scope.cancel()

        PacketProcessor.resetStats()
        Log.i(TAG, "BedrockRelay durduruldu")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  İSEMCİ OLAYLAR
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Yerel RakNetServer bir MC istemcisi bağlandığında çağırır.
     * Bu noktada gerçek sunucuya upstream bağlantı kurulur.
     */
    fun onClientConnected(clientSession: RakNetSession) {
        val target = targetServer ?: run {
            Log.e(TAG, "Hedef sunucu yapılandırılmamış!")
            clientSession.disconnect()
            return
        }

        Log.i(TAG, "İstemci bağlandı: ${clientSession.remoteAddress} → ${target.host}:${target.port}")

        scope.launch {
            val serverAddr = InetSocketAddress(target.host, target.port)
            val upClient   = RakNetClient(
                relay         = this@BedrockRelay,
                clientAddress = clientSession.remoteAddress,
                serverAddress = serverAddr,
                clientGuid    = clientSession.guid
            )

            upClient.onConnected = {
                Log.i(TAG, "Upstream bağlantı hazır: ${clientSession.remoteAddress} ↔ ${target.host}:${target.port}")
            }

            upClient.onDisconnected = {
                onServerDisconnected(clientSession.remoteAddress)
            }

            upstreamClients[clientSession.remoteAddress] = upClient

            val ok = upClient.connect()
            if (!ok) {
                Log.e(TAG, "Upstream bağlantı zaman aşımına uğradı!")
                clientSession.disconnect()
                upstreamClients.remove(clientSession.remoteAddress)
            }
        }
    }

    fun onClientDisconnected(clientAddress: InetSocketAddress) {
        Log.i(TAG, "İstemci bağlantısı kesildi: $clientAddress")
        upstreamClients[clientAddress]?.disconnect()
        upstreamClients.remove(clientAddress)
        upstreamSessions.remove(clientAddress)
    }

    fun onServerDisconnected(clientAddress: InetSocketAddress) {
        Log.i(TAG, "Sunucu bağlantısı kesildi, relay temizleniyor: $clientAddress")
        upstreamClients.remove(clientAddress)
        upstreamSessions.remove(clientAddress)
        // MC istemcisine disconnect paketi gönder
        scope.launch {
            val buf = io.netty.buffer.Unpooled.buffer()
            buf.writeByte(RakNetConstants.ID_DISCONNECT_NOTIFICATION.toInt())
            val session = localServer.let { null } // localServer'dan session al (gerekirse genişlet)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  YÖNLENDİRME
    // ─────────────────────────────────────────────────────────────────────

    /**
     * MC istemcisinden gelen işlenmiş paketi sunucuya ilet.
     */
    fun forwardToServer(clientAddress: InetSocketAddress, processedPacket: ByteArray) {
        val client = upstreamClients[clientAddress] ?: return
        val encoded = BedrockPipeline.encode(listOf(processedPacket))
        client.sendToServer(encoded)
        totalBytesOut += processedPacket.size.toLong()
    }

    /**
     * Zaten encode edilmiş (batch) veriyi sunucuya ilet.
     */
    fun forwardRawToServer(clientAddress: InetSocketAddress, batchPayload: ByteArray) {
        val client = upstreamClients[clientAddress] ?: return
        client.sendToServer(batchPayload)
        totalBytesOut += batchPayload.size.toLong()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  UPSTREAM SESSION YÖNETİMİ
    // ─────────────────────────────────────────────────────────────────────

    fun registerUpstreamSession(clientAddress: InetSocketAddress, session: RakNetSession) {
        upstreamSessions[clientAddress] = session
    }

    fun getUpstreamSession(clientAddress: InetSocketAddress): RakNetSession? =
        upstreamSessions[clientAddress]

    // ─────────────────────────────────────────────────────────────────────
    //  İSTATİSTİK
    // ─────────────────────────────────────────────────────────────────────

    val connectedClients: Int get() = upstreamClients.size

    data class RelayStats(
        val connectedClients : Int,
        val bytesIn          : Long,
        val bytesOut         : Long,
        val packetsIntercepted: Long,
        val packetsInjected  : Long,
        val packetsCancelled : Long
    )

    fun getStats() = RelayStats(
        connectedClients  = connectedClients,
        bytesIn           = totalBytesIn,
        bytesOut          = totalBytesOut,
        packetsIntercepted= PacketProcessor.packetsIntercepted,
        packetsInjected   = PacketProcessor.packetsInjected,
        packetsCancelled  = PacketProcessor.packetsCancelled
    )
}
