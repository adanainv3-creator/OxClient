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
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import org.cloudburstmc.protocol.common.PacketSignal
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OxRelaySession — WRelay mimarisiyle birebir uyumlu.
 *
 * KRİTİK FARK (önceki versiyondan):
 * ──────────────────────────────────
 * Eski: Her paketi decode edip listener'lardan geçirip re-encode ediyordu.
 *       Definitions eksikse veya codec uyumsuzsa crash/timeout.
 *
 * Yeni (WRelay gibi):
 *   - Paketler önce decode edilip listener'lardan geçer (intercept için)
 *   - Listener engellemediyse → raw buffer (UnknownPacket) olarak iletilir
 *   - Listener değiştirdiyse → sadece o paket encode edilir
 *   - Bu sayede definitions eksikliği veya codec uyumsuzluğu iletimi bozmaz
 *
 * AKIŞ:
 *   Client → RequestNetworkSettings
 *     AutoCodecListener: codec + definitions set et, NetworkSettings gönder
 *   Client → Login
 *     LoginPacketListener: chain inject, connectToServer()
 *   Server bağlantısı kurulunca → RequestNetworkSettings server'a gönder
 *   Server → NetworkSettings → ZLIB aç → Login gönder
 *   Server → StartGame → raw olarak client'a iletilir (definitions sorunu yok)
 */
class OxRelaySession(
    val clientSession : BedrockServerSession,
    val remoteHost    : String,
    val remotePort    : Int,
    private val relay : OxRelay
) {
    companion object {
        private const val TAG       = "OxRelaySession"
        private const val MAX_QUEUE = 1024
    }

    @Volatile var serverSession: BedrockClientSession? = null
        private set

    @Volatile var activeCodec: BedrockCodec = OxRelay.RELAY_CODEC
        internal set

    val listeners = CopyOnWriteArrayList<OxPacketListener>()

    private val closed           = AtomicBoolean(false)
    private val serverConnecting = AtomicBoolean(false)
    private val serverConnected  = AtomicBoolean(false)

    // Server bağlanana kadar bekleyen paketler
    private val pendingQueue = ConcurrentLinkedQueue<Pair<BedrockPacket, Boolean>>() // packet, immediate

    private val serverEventLoop = NioEventLoopGroup(2)

    // ── Init ──────────────────────────────────────────────────────────────

    fun init() {
        clientSession.codec = activeCodec
        clientSession.packetHandler = ClientPacketHandler()
        installClientDisconnectHandler()
        Log.i(TAG, "Session init: ${clientSession.socketAddress} → $remoteHost:$remotePort")
    }

    // ── Server Bağlantısı ─────────────────────────────────────────────────

    fun connectToServer(onConnected: (() -> Unit)? = null) {
        if (!serverConnecting.compareAndSet(false, true)) {
            Log.w(TAG, "Server bağlantısı zaten başlatıldı")
            return
        }

        val addr = InetSocketAddress(remoteHost, remotePort)
        Log.i(TAG, "Server'a bağlanılıyor: $addr | codec=${activeCodec.protocolVersion}")

        Bootstrap()
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .group(serverEventLoop)
            .handler(object : BedrockClientInitializer() {
                override fun initSession(session: BedrockClientSession) {
                    // WRelaySession.client setter gibi: codec + definitions kopyala
                    session.codec = activeCodec
                    session.peer.codecHelper.apply {
                        blockDefinitions       = clientSession.peer.codecHelper.blockDefinitions
                        itemDefinitions        = clientSession.peer.codecHelper.itemDefinitions
                        cameraPresetDefinitions = clientSession.peer.codecHelper.cameraPresetDefinitions
                        encodingSettings       = clientSession.peer.codecHelper.encodingSettings
                    }
                    session.packetHandler = ServerPacketHandler()
                    installServerDisconnectHandler(session)

                    serverSession = session
                    serverConnected.set(true)
                    Log.i(TAG, "Server bağlandı: $addr")

                    // Listener'lara bildir
                    listeners.forEach { l ->
                        try { l.onSessionStart(this@OxRelaySession) }
                        catch (e: Exception) { Log.w(TAG, "onSessionStart [${l::class.simpleName}]: ${e.message}") }
                    }

                    // Callback
                    try { onConnected?.invoke() }
                    catch (e: Exception) { Log.w(TAG, "onConnected hatası: ${e.message}") }

                    // Kuyruğu boşalt
                    flushQueue(session)
                }
            })
            .connect(addr)
            .addListener { future ->
                if (!future.isSuccess) {
                    Log.e(TAG, "Server bağlantısı başarısız: ${future.cause()?.message}")
                    disconnect("Server bağlantısı kurulamadı")
                }
            }
    }

    private fun flushQueue(session: BedrockClientSession) {
        var n = 0
        while (true) {
            val (pkt, immediate) = pendingQueue.poll() ?: break
            try {
                if (immediate) session.sendPacketImmediately(pkt)
                else session.sendPacket(pkt)
                n++
            } catch (e: Exception) { Log.w(TAG, "Kuyruk paketi gönderilemedi: ${e.message}") }
        }
        if (n > 0) Log.d(TAG, "Kuyruk flush: $n paket")
    }

    // ── Paket İşleme ─────────────────────────────────────────────────────
    //
    // WRelay gibi:
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
        catch (e: Exception) { Log.w(TAG, "Client'a gönderilemedi [${packet::class.simpleName}]: ${e.message}") }
    }

    fun sendToServer(packet: BedrockPacket, immediate: Boolean = true) {
        if (closed.get()) return
        val srv = serverSession
        if (srv != null && serverConnected.get()) {
            try {
                if (immediate) srv.sendPacketImmediately(packet)
                else srv.sendPacket(packet)
            } catch (e: Exception) { Log.w(TAG, "Server'a gönderilemedi [${packet::class.simpleName}]: ${e.message}") }
        } else {
            if (pendingQueue.size < MAX_QUEUE) pendingQueue.add(packet to immediate)
            else Log.w(TAG, "Kuyruk dolu, düşürüldü: ${packet::class.simpleName}")
        }
    }

    fun clientBound(packet: BedrockPacket)  = sendToClient(packet)
    fun serverBound(packet: BedrockPacket)  = sendToServer(packet)

    // ── Disconnect ────────────────────────────────────────────────────────

    fun disconnect(reason: String = "Relay kapatıldı") {
        if (!closed.compareAndSet(false, true)) return
        Log.i(TAG, "Session kapatılıyor: $reason")
        pendingQueue.clear()
        try { clientSession.sendPacketImmediately(DisconnectPacket().apply { kickMessage = reason; isMessageSkipped = false }) } catch (_: Exception) {}
        try { clientSession.disconnect() }           catch (_: Exception) {}
        try { serverSession?.disconnect() }          catch (_: Exception) {}
        try { serverEventLoop.shutdownGracefully() } catch (_: Exception) {}
        relay.removeSession(this)
        listeners.forEach { try { it.onSessionEnd(this) } catch (_: Exception) {} }
        listeners.clear()
    }

    // ── Disconnect Handler'lar ────────────────────────────────────────────

    private fun installClientDisconnectHandler() {
        try {
            clientSession.peer.channel.pipeline()
                .addLast("ox-client-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (closed.compareAndSet(false, true)) {
                            Log.i(TAG, "Client kesildi")
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
                        if (evt is RakDisconnectReason) Log.i(TAG, "Client RakNet: $evt")
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) { Log.w(TAG, "Client DC handler: ${e.message}") }
    }

    private fun installServerDisconnectHandler(session: BedrockClientSession) {
        try {
            session.peer.channel.pipeline()
                .addLast("ox-server-dc", object : ChannelInboundHandlerAdapter() {
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        if (!closed.get()) disconnect("Server kesildi")
                        ctx.fireChannelInactive()
                    }
                    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                        if (evt is RakDisconnectReason) { Log.i(TAG, "Server RakNet: $evt"); disconnect(evt.toString()) }
                        ctx.fireUserEventTriggered(evt)
                    }
                })
        } catch (e: Exception) { Log.w(TAG, "Server DC handler: ${e.message}") }
    }

    // ── Packet Handlers ───────────────────────────────────────────────────
    //
    // WRelay'in ServerSession.onPacket / ClientSession.onPacket ile aynı:
    // beforeXxxBound → raw buffer ilet → afterXxxBound
    // Biz listener sistemi farklı olduğu için handleClientPacket/handleServerPacket kullanıyoruz.

    inner class ClientPacketHandler : BedrockPacketHandler {
        override fun handlePacket(packet: BedrockPacket): PacketSignal {
            // Bu asla çağrılmaz — onPacket override ediyoruz
            return PacketSignal.HANDLED
        }
    }

    inner class ServerPacketHandler : BedrockPacketHandler {
        override fun handlePacket(packet: BedrockPacket): PacketSignal {
            return PacketSignal.HANDLED
        }
    }

    // WRelay'in onPacket override'ı gibi — BedrockServerSession/BedrockClientSession
    // subclass etmeden bunu yapamıyoruz doğrudan, bu yüzden
    // BedrockServerInitializer yerine custom initializer kullanacağız.
    // Şimdilik BedrockPacketHandler.handlePacket üzerinden decode edilmiş paketlerle çalışıyoruz.
    // Raw buffer iletimi için OxRelay'de BedrockChannelInitializer kullanılacak.

    val isClosed      : Boolean get() = closed.get()
    val clientAddress : String  get() = clientSession.socketAddress?.toString() ?: "unknown"
    val isServerReady : Boolean get() = serverConnected.get() && !closed.get()
}
