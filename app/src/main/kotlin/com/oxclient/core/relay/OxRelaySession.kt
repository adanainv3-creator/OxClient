package com.oxclient.core.relay

import android.util.Log
import com.oxclient.core.relay.listener.OxPacketListener
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import org.cloudburstmc.protocol.common.PacketSignal
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OxRelaySession — WRelay mimarisiyle birebir uyumlu relay session.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * DOĞRU PAKET AKIŞI (WRelay ile aynı):
 *
 *   [CLIENT]                  [RELAY]                    [SERVER]
 *      │                        │                            │
 *      │── RequestNetSettings ──►│                            │
 *      │                        │ AutoCodecListener:          │
 *      │                        │  codec seç                 │
 *      │                        │  EncodingSettings ayarla   │
 *      │◄── NetworkSettings ────│                            │
 *      │   (ZLIB açıldı)        │                            │
 *      │── Login ───────────────►│                            │
 *      │                        │ LoginPacketListener:        │
 *      │                        │  chain inject               │
 *      │                        │  connectToServer() ─────────►│
 *      │                        │                  ◄── connect ┘
 *      │                        │── RequestNetSettings ───────►│
 *      │                        │◄── NetworkSettings ──────────│
 *      │                        │   server ZLIB açıldı         │
 *      │                        │── Login (injected) ──────────►│
 *      │                        │◄── ServerToClientHandshake ──│
 *      │◄── ServerToClientHandshake ─│                         │
 *      │── ClientToServerHandshake ──►│                        │
 *      │                        │── ClientToServerHandshake ───►│
 *      │                        │◄── PlayStatus(LOGIN_SUCCESS) ─│
 *      │◄── PlayStatus ─────────│                              │
 *      │                        │◄── StartGame ────────────────│
 *      │◄── StartGame ──────────│  (definitions set edildi)    │
 *      │  OYUN İÇİ              │                              │
 *
 * KRİTİK FARK (eski koddan):
 *   Eski: init() → connectToServer() ANINDA (listeners henüz boş!)
 *   Yeni: init() sadece client handler kurar.
 *         connectToServer() → LoginPacketListener çağırır (Login gelince).
 *         onSessionStart() → server bağlantısı kurulunca çağrılır.
 * ═══════════════════════════════════════════════════════════════════════
 */
class OxRelaySession(
    val clientSession : BedrockServerSession,
    val remoteHost    : String,
    val remotePort    : Int,
    private val relay : OxRelay
) {
    companion object {
        private const val TAG          = "OxRelaySession"
        private const val MAX_QUEUE    = 1024
    }

    // ── State ─────────────────────────────────────────────────────────────

    @Volatile var serverSession: BedrockClientSession? = null
        private set

    @Volatile var activeCodec: BedrockCodec = OxRelay.RELAY_CODEC
        internal set

    val listeners = CopyOnWriteArrayList<OxPacketListener>()

    private val closed           = AtomicBoolean(false)
    private val serverConnecting = AtomicBoolean(false)
    private val serverConnected  = AtomicBoolean(false)

    // Server bağlanana kadar server'a gidecek paketler kuyrukta bekler
    private val pendingServerQueue = ConcurrentLinkedQueue<BedrockPacket>()

    private val serverEventLoop = NioEventLoopGroup(2)

    // ── Init ──────────────────────────────────────────────────────────────

    /**
     * Sadece client tarafını başlatır.
     * Server bağlantısı [connectToServer] çağrılana kadar AÇILMAZ.
     * Listeners bu noktada zaten eklenmiş olmalı (ConnectionManager.setupSession sonrası).
     */
    fun init() {
        clientSession.codec = activeCodec
        clientSession.setPacketHandler(ClientPacketHandler())
        installClientDisconnectHandler()
        Log.i(TAG, "Session init: ${clientSession.socketAddress} → $remoteHost:$remotePort")
    }

    // ── Server Bağlantısı ─────────────────────────────────────────────────

    /**
     * LoginPacketListener tarafından çağrılır — Login paketi alındığında.
     * Server bağlantısı kurulunca [onConnected] callback'i tetiklenir.
     */
    fun connectToServer(onConnected: (() -> Unit)? = null) {
        if (!serverConnecting.compareAndSet(false, true)) {
            Log.w(TAG, "Server bağlantısı zaten başlatıldı, atlanıyor")
            return
        }

        val addr = InetSocketAddress(remoteHost, remotePort)
        Log.i(TAG, "Server'a bağlanılıyor: $addr | codec=${activeCodec.protocolVersion}")

        Bootstrap()
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .group(serverEventLoop)
            .handler(object : BedrockClientInitializer() {
                override fun initSession(session: BedrockClientSession) {
                    session.codec = activeCodec
                    session.setPacketHandler(ServerPacketHandler())
                    installServerDisconnectHandler(session)

                    serverSession = session
                    serverConnected.set(true)

                    Log.i(TAG, "Server bağlantısı kuruldu: $addr")

                    // Listeners'a bildir — onSessionStart burada çağrılır
                    listeners.forEach { l ->
                        try { l.onSessionStart(this@OxRelaySession) }
                        catch (e: Exception) { Log.w(TAG, "onSessionStart hatası [${l::class.simpleName}]: ${e.message}") }
                    }

                    // Callback (LoginPacketListener → RequestNetworkSettings gönder)
                    try { onConnected?.invoke() }
                    catch (e: Exception) { Log.w(TAG, "onConnected hatası: ${e.message}") }

                    // Kuyrukta bekleyen paketleri gönder
                    flushPendingQueue(session)
                }
            })
            .connect(addr)
            .addListener { future ->
                if (!future.isSuccess) {
                    Log.e(TAG, "Server bağlantısı başarısız: ${future.cause()?.message}")
                    disconnect("Server bağlantısı kurulamadı: ${future.cause()?.message}")
                }
            }
    }

    private fun flushPendingQueue(session: BedrockClientSession) {
        var count = 0
        while (true) {
            val pkt = pendingServerQueue.poll() ?: break
            try { session.sendPacketImmediately(pkt); count++ }
            catch (e: Exception) { Log.w(TAG, "Kuyruk paketi gönderilemedi: ${e.message}") }
        }
        if (count > 0) Log.d(TAG, "Kuyruk flush: $count paket gönderildi")
    }

    // ── Paket İşleme ──────────────────────────────────────────────────────

    fun handleClientPacket(packet: BedrockPacket): Boolean {
        val event = PacketEvent(packet, PacketEvent.Direction.CLIENT_TO_SERVER, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false

        val effective = event.replacementPacket ?: packet
        for (listener in listeners) {
            if (!listener.onClientPacket(effective, this)) {
                // false döndü → paketi engelle, ama replacement varsa onu gönder
                event.replacementPacket?.let { sendToServer(it) }
                return false
            }
        }
        if (event.replacementPacket != null) {
            sendToServer(event.replacementPacket!!)
            return false
        }
        return true
    }

    fun handleServerPacket(packet: BedrockPacket): Boolean {
        val event = PacketEvent(packet, PacketEvent.Direction.SERVER_TO_CLIENT, this)
        PacketEventBus.publish(event)
        if (event.isCancelled) return false

        val effective = event.replacementPacket ?: packet
        for (listener in listeners) {
            if (!listener.onServerPacket(effective, this)) {
                event.replacementPacket?.let { sendToClient(it) }
                return false
            }
        }
        if (event.replacementPacket != null) {
            sendToClient(event.replacementPacket!!)
            return false
        }
        return true
    }

    // ── Gönderme ──────────────────────────────────────────────────────────

    fun sendToClient(packet: BedrockPacket) {
        if (closed.get()) return
        try { clientSession.sendPacketImmediately(packet) }
        catch (e: Exception) { Log.w(TAG, "Client'a paket gönderilemedi [${packet::class.simpleName}]: ${e.message}") }
    }

    fun sendToServer(packet: BedrockPacket) {
        if (closed.get()) return
        val srv = serverSession
        if (srv != null && serverConnected.get()) {
            try { srv.sendPacketImmediately(packet) }
            catch (e: Exception) { Log.w(TAG, "Server'a paket gönderilemedi [${packet::class.simpleName}]: ${e.message}") }
        } else {
            // Server henüz hazır değil — kuyruğa al
            if (pendingServerQueue.size < MAX_QUEUE) {
                pendingServerQueue.add(packet)
                Log.v(TAG, "Kuyruklandı [${packet::class.simpleName}] — kuyruk: ${pendingServerQueue.size}")
            } else {
                Log.w(TAG, "Kuyruk dolu! Paket düşürüldü: ${packet::class.simpleName}")
            }
        }
    }

    // Alias'lar (eski kodla uyumluluk)
    fun clientBound(packet: BedrockPacket) = sendToClient(packet)
    fun serverBound(packet: BedrockPacket) = sendToServer(packet)

    // ── Disconnect ────────────────────────────────────────────────────────

    fun disconnect(reason: String = "Relay kapatıldı") {
        if (!closed.compareAndSet(false, true)) return
        Log.i(TAG, "Session kapatılıyor: $reason")

        pendingServerQueue.clear()

        // Client'a disconnect paketi gönder
        try {
            clientSession.sendPacketImmediately(DisconnectPacket().apply {
                kickMessage      = reason
                isMessageSkipped = false
            })
        } catch (_: Exception) {}

        try { clientSession.disconnect() }           catch (_: Exception) {}
        try { serverSession?.disconnect() }          catch (_: Exception) {}
        try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}

        relay.removeSession(this)

        listeners.forEach { l ->
            try { l.onSessionEnd(this) }
            catch (e: Exception) { Log.w(TAG, "onSessionEnd hatası [${l::class.simpleName}]: ${e.message}") }
        }
        listeners.clear()
    }

    // ── Disconnect Handler'lar ────────────────────────────────────────────

    private fun installClientDisconnectHandler() {
        try {
            clientSession.peer.channel.pipeline()
                .addLast("ox-client-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (!closed.get()) {
                            Log.i(TAG, "Client bağlantısı kesildi")
                            // Server'ı da kapat ama listeners'a bildir
                            if (closed.compareAndSet(false, true)) {
                                pendingServerQueue.clear()
                                try { serverSession?.disconnect() } catch (_: Exception) {}
                                try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}
                                relay.removeSession(this@OxRelaySession)
                                listeners.forEach { try { it.onSessionEnd(this@OxRelaySession) } catch (_: Exception) {} }
                                listeners.clear()
                            }
                        }
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason)
                            Log.i(TAG, "Client RakNet ayrıldı: $evt")
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) {
            Log.w(TAG, "Client DC handler kurulamadı: ${e.message}")
        }
    }

    private fun installServerDisconnectHandler(session: BedrockClientSession) {
        try {
            session.peer.channel.pipeline()
                .addLast("ox-server-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (!closed.get()) {
                            Log.i(TAG, "Server bağlantısı kesildi")
                            disconnect("Server bağlantısı kesildi")
                        }
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason) {
                            Log.i(TAG, "Server RakNet ayrıldı: $evt")
                            disconnect(evt.toString())
                        }
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) {
            Log.w(TAG, "Server DC handler kurulamadı: ${e.message}")
        }
    }

    // ── Packet Handler Inner Classes ──────────────────────────────────────

    inner class ClientPacketHandler : BedrockPacketHandler {
        override fun handlePacket(packet: BedrockPacket): PacketSignal {
            val forward = handleClientPacket(packet)
            if (forward) sendToServer(packet)
            return PacketSignal.HANDLED
        }
    }

    inner class ServerPacketHandler : BedrockPacketHandler {
        override fun handlePacket(packet: BedrockPacket): PacketSignal {
            val forward = handleServerPacket(packet)
            if (forward) sendToClient(packet)
            return PacketSignal.HANDLED
        }
    }

    // ── Properties ───────────────────────────────────────────────────────

    val isClosed      : Boolean get() = closed.get()
    val clientAddress : String  get() = clientSession.socketAddress?.toString() ?: "unknown"
    val isServerReady : Boolean get() = serverConnected.get() && !closed.get()
}
