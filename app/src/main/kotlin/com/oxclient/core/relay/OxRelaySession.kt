package com.oxclient.core.relay

import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.core.relay.listener.OxPacketListener
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import io.netty.buffer.Unpooled
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OxRelaySession — WRelaySession mimarisiyle birebir uyumlu (GERÇEKTEN).
 *
 * ═══════════════════════════════════════════════════════════════════════
 * BU SÜRÜMDE DÜZELEN KRİTİK HATA:
 * ──────────────────────────────
 * Önceki sürümde handleClientPacket()/handleServerPacket() hiçbir yerden
 * çağrılmıyordu — çünkü clientSession/serverSession standart
 * BedrockServerInitializer/BedrockClientInitializer ile kuruluyordu ve
 * bunlar BedrockSession.onPacket()'i override etmeye izin vermiyor.
 *
 * WRelay'in çözümü: BedrockServerSession/BedrockClientSession'ı INNER
 * CLASS olarak subclass'lamak ve onPacket(wrapper) override etmek.
 * Bu da BedrockChannelInitializer<T> + createSession0() gerektiriyor
 * (BedrockServerInitializer/BedrockClientInitializer bunu desteklemiyor).
 *
 * Bu dosyada ServerSession/ClientSession inner class'ları eklendi ve
 * onPacket() içinde handleClientPacket()/handleServerPacket() çağrılıp
 * sonucuna göre RAW BUFFER (UnknownPacket) olarak iletim yapılıyor.
 *
 * DİKKAT — geriye dönük uyumluluk:
 *   clientSession, serverSession, activeCodec, listeners, sendToServer(),
 *   sendToClient(), serverBound(), clientBound(), disconnect(),
 *   connectToServer(), isClosed, clientAddress, isServerReady
 *   — HİÇBİRİNİN ADI/İMZASI DEĞİŞMEDİ. AutoCodecListener, LoginPacketListener,
 *   GamingPacketListener, CrystalAura, Criticals, AutoTotem, ConnectionManager,
 *   SessionManager bu sınıfı önceki haliyle aynı şekilde kullanmaya devam eder.
 * ═══════════════════════════════════════════════════════════════════════
 */
class OxRelaySession internal constructor(
    peer: BedrockPeer,
    subClientId: Int,
    val remoteHost: String,
    val remotePort: Int,
    internal val relay: OxRelay
) {
    companion object {
        private const val TAG       = "OxRelaySession"
        private const val MAX_QUEUE = 1024

        // Standart RakNet "offline message" magic byte dizisi — bu RakNet
        // protokolünün sabit kısmı, "spoofing" değil; her uyumlu RakNet
        // implementasyonu (sunucu dahil) bunu bekler.
        private fun minecraftUnconnectedMagic() = Unpooled.wrappedBuffer(
            byteArrayOf(
                0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(),
                0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
                0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
                0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()
            )
        )

        private fun generateClientGuid(): Long {
            val timestamp = System.currentTimeMillis()
            val random    = kotlin.random.Random.nextLong(0, 0xFFFFFF)
            return (timestamp shl 24) or random
        }
    }

    // ServerSession, OxRelaySession'ın kendi inner class'ı — constructor'da
    // hemen kuruluyor (WRelaySession'ın `server = ServerSession(...)` ile aynı).
    val clientSession: ServerSession = ServerSession(peer, subClientId)

    @Volatile var serverSession: ClientSession? = null
        internal set

    @Volatile var activeCodec: BedrockCodec = OxRelay.RELAY_CODEC
        internal set

    val listeners = CopyOnWriteArrayList<OxPacketListener>()

    private val closed           = AtomicBoolean(false)
    private val serverConnecting = AtomicBoolean(false)
    private val serverConnected  = AtomicBoolean(false)

    // Server bağlanana kadar bekleyen paketler
    private val pendingQueue = ConcurrentLinkedQueue<Pair<BedrockPacket, Boolean>>()

    private val serverEventLoop = NioEventLoopGroup(2)

    // ── Init ──────────────────────────────────────────────────────────────

    fun init() {
        clientSession.codec = activeCodec
        installClientDisconnectHandler()
        OverlayLogger.i(TAG, "Session init: ${clientSession.socketAddress} → $remoteHost:$remotePort")
    }

    // ── Server Bağlantısı ─────────────────────────────────────────────────

    fun connectToServer(onConnected: (() -> Unit)? = null) {
        if (!serverConnecting.compareAndSet(false, true)) {
            OverlayLogger.w(TAG, "Server bağlantısı zaten başlatıldı")
            return
        }

        val addr = InetSocketAddress(remoteHost, remotePort)
        OverlayLogger.i(TAG, "Server'a bağlanılıyor: $addr | codec=${activeCodec.protocolVersion}")

        Bootstrap()
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .group(serverEventLoop)
            // ── RakNet handshake ayarları — wclient'in ClientIdentification.kt'sinden ──
            // Bunlar olmadan gerçek sunucu bizim OPEN_CONNECTION_REQUEST paketlerimizi
            // geçersiz/tanınmayan RakNet trafiği sayıp sessizce yok sayıyordu (76s
            // sessizlik + timeout — RAK_PROTOCOL_VERSION ayarlanmadığı için).
            .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
            .option(RakChannelOption.RAK_GUID, generateClientGuid())
            .option(RakChannelOption.RAK_MTU, 1400)
            .option(RakChannelOption.RAK_UNCONNECTED_MAGIC, minecraftUnconnectedMagic())
            .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
            .option(RakChannelOption.RAK_CONNECT_TIMEOUT, 10_000L)
            .option(RakChannelOption.RAK_SESSION_TIMEOUT, 20_000L)
            .handler(object : BedrockChannelInitializer<ClientSession>() {

                override fun createSession0(peer: BedrockPeer, subClientId: Int): ClientSession {
                    return this@OxRelaySession.ClientSession(peer, subClientId)
                }

                override fun initSession(session: ClientSession) {
                    // WRelaySession.client setter gibi: codec + definitions kopyala
                    session.codec = activeCodec
                    session.peer.codecHelper.apply {
                        blockDefinitions        = clientSession.peer.codecHelper.blockDefinitions
                        itemDefinitions         = clientSession.peer.codecHelper.itemDefinitions
                        cameraPresetDefinitions = clientSession.peer.codecHelper.cameraPresetDefinitions
                        encodingSettings        = clientSession.peer.codecHelper.encodingSettings
                    }
                    installServerDisconnectHandler(session)

                    serverSession = session
                    serverConnected.set(true)
                    OverlayLogger.i(TAG, "Server bağlandı: $addr")

                    // Listener'lara bildir
                    listeners.forEach { l ->
                        try { l.onSessionStart(this@OxRelaySession) }
                        catch (e: Exception) { OverlayLogger.w(TAG, "onSessionStart [${l::class.simpleName}]: ${e.message}") }
                    }

                    // Callback
                    try { onConnected?.invoke() }
                    catch (e: Exception) { OverlayLogger.w(TAG, "onConnected hatası: ${e.message}") }

                    // Kuyruğu boşalt
                    flushQueue(session)
                }

                override fun preInitChannel(channel: Channel) {
                    channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND)
                    super.preInitChannel(channel)
                }
            })
            .connect(addr)
            .addListener { future ->
                if (!future.isSuccess) {
                    OverlayLogger.e(TAG, "Server bağlantısı başarısız: ${future.cause()?.message}")
                    disconnect("Server bağlantısı kurulamadı")
                }
            }
    }

    private fun flushQueue(session: ClientSession) {
        var n = 0
        while (true) {
            val (pkt, immediate) = pendingQueue.poll() ?: break
            try {
                if (immediate) session.sendPacketImmediately(pkt)
                else session.sendPacket(pkt)
                n++
            } catch (e: Exception) { OverlayLogger.w(TAG, "Kuyruk paketi gönderilemedi: ${e.message}") }
        }
        if (n > 0) OverlayLogger.d(TAG, "Kuyruk flush: $n paket")
    }

    // ── Paket İşleme ─────────────────────────────────────────────────────
    //
    // Bu iki fonksiyon DEĞİŞMEDİ — sadece artık gerçekten çağrılıyorlar
    // (ServerSession.onPacket / ClientSession.onPacket içinden, en aşağıda).
    //
    //   1. Listener'lar paketi decode edilmiş hâlde görür (intercept/modify için)
    //   2. Listener engellemediyse → orijinal raw buffer UnknownPacket olarak iletilir
    //   3. Listener replacement döndürdüyse → sadece o encode edilir

    fun handleClientPacket(wrapper: BedrockPacketWrapper): Boolean {
        val packet = wrapper.packet
        val event  = PacketEvent(packet, PacketEvent.Direction.CLIENT_TO_SERVER, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false

        for (listener in listeners) {
            val effective = event.replacementPacket ?: packet
            if (!listener.onClientPacket(effective, this)) {
                event.replacementPacket?.let { sendToServer(it) }
                return false
            }
        }
        if (event.replacementPacket != null) {
            sendToServer(event.replacementPacket!!)
            return false
        }
        return true // raw buffer ilet
    }

    fun handleServerPacket(wrapper: BedrockPacketWrapper): Boolean {
        val packet = wrapper.packet
        val event  = PacketEvent(packet, PacketEvent.Direction.SERVER_TO_CLIENT, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false

        for (listener in listeners) {
            val effective = event.replacementPacket ?: packet
            if (!listener.onServerPacket(effective, this)) {
                event.replacementPacket?.let { sendToClient(it) }
                return false
            }
        }
        if (event.replacementPacket != null) {
            sendToClient(event.replacementPacket!!)
            return false
        }
        return true // raw buffer ilet
    }

    // ── Gönderme ─────────────────────────────────────────────────────────

    fun sendToClient(packet: BedrockPacket) {
        if (closed.get()) return
        try { clientSession.sendPacketImmediately(packet) }
        catch (e: Exception) { OverlayLogger.w(TAG, "Client'a gönderilemedi [${packet::class.simpleName}]: ${e.message}") }
    }

    fun sendToServer(packet: BedrockPacket, immediate: Boolean = true) {
        if (closed.get()) return
        val srv = serverSession
        if (srv != null && serverConnected.get()) {
            try {
                if (immediate) srv.sendPacketImmediately(packet)
                else srv.sendPacket(packet)
            } catch (e: Exception) { OverlayLogger.w(TAG, "Server'a gönderilemedi [${packet::class.simpleName}]: ${e.message}") }
        } else {
            if (pendingQueue.size < MAX_QUEUE) pendingQueue.add(packet to immediate)
            else OverlayLogger.w(TAG, "Kuyruk dolu, düşürüldü: ${packet::class.simpleName}")
        }
    }

    fun clientBound(packet: BedrockPacket)  = sendToClient(packet)
    fun serverBound(packet: BedrockPacket)  = sendToServer(packet)

    // ── Disconnect ────────────────────────────────────────────────────────

    fun disconnect(reason: String = "Relay kapatıldı") {
        if (!closed.compareAndSet(false, true)) return
        OverlayLogger.i(TAG, "Session kapatılıyor: $reason")
        pendingQueue.clear()
        // TODO: DisconnectPacket artık immutable (kickMessage val) — gerçek
        // constructor/builder'ı görene kadar geçici olarak kaldırıldı.
        // Relay tarafından disconnect halinde client'a özel bir kick mesajı
        // gitmiyor, ama bağlantı düzgün kapanıyor (kritik değil).
        try { clientSession.disconnect() }           catch (_: Exception) {}
        try { serverSession?.disconnect() }          catch (_: Exception) {}
        try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}
        relay.removeSession(this)
        listeners.forEach { try { it.onSessionEnd(this) } catch (_: Exception) {} }
        listeners.clear()
    }

    // ── Disconnect Handler'lar (Netty pipeline tabanlı — değişmedi) ───────

    private fun installClientDisconnectHandler() {
        try {
            clientSession.peer.channel.pipeline()
                .addLast("ox-client-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (closed.compareAndSet(false, true)) {
                            OverlayLogger.i(TAG, "Client kesildi")
                            pendingQueue.clear()
                            try { serverSession?.disconnect() }          catch (_: Exception) {}
                            try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}
                            relay.removeSession(this@OxRelaySession)
                            listeners.forEach { try { it.onSessionEnd(this@OxRelaySession) } catch (_: Exception) {} }
                            listeners.clear()
                        }
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason) OverlayLogger.i(TAG, "Client RakNet: $evt")
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) { OverlayLogger.w(TAG, "Client DC handler: ${e.message}") }
    }

    private fun installServerDisconnectHandler(session: ClientSession) {
        try {
            session.peer.channel.pipeline()
                .addLast("ox-server-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (!closed.get()) disconnect("Server kesildi")
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason) { OverlayLogger.i(TAG, "Server RakNet: $evt"); disconnect(evt.toString()) }
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) { OverlayLogger.w(TAG, "Server DC handler: ${e.message}") }
    }

    // ── Session Sınıfları — ASIL FİX BURADA ───────────────────────────────
    //
    // BedrockServerSession/BedrockClientSession'ı subclass ederek onPacket()
    // override ediliyor. Bu, WRelaySession.ServerSession/ClientSession ile
    // birebir aynı mekanizma: paket decode edilir (wrapper.packet ile
    // listener'lara gösterilir), ama iletim RAW BUFFER (UnknownPacket) ile
    // yapılır — definitions eksik/yanlış olsa bile re-encode hatası olmaz.

    inner class ServerSession(peer: BedrockPeer, subClientId: Int) :
        BedrockServerSession(peer, subClientId) {

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                if (!handleClientPacket(wrapper)) return // listener kendi gönderimini yaptı veya engelledi

                val buffer = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                sendToServer(UnknownPacket().apply {
                    payload  = buffer
                    packetId = wrapper.packetId
                }, immediate = false)
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "Client paket işleme hatası: ${e.message}", e)
            }
        }
    }

    inner class ClientSession(peer: BedrockPeer, subClientId: Int) :
        BedrockClientSession(peer, subClientId) {

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                if (!handleServerPacket(wrapper)) return

                val buffer = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                sendToClient(UnknownPacket().apply {
                    payload  = buffer
                    packetId = wrapper.packetId
                })
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "Server paket işleme hatası: ${e.message}", e)
            }
        }
    }

    val isClosed      : Boolean get() = closed.get()
    val clientAddress : String  get() = clientSession.socketAddress?.toString() ?: "unknown"
    val isServerReady : Boolean get() = serverConnected.get() && !closed.get()
}
