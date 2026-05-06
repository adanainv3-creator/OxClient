package com.oxclient.relay.session

import com.oxclient.relay.OxAddress
import com.oxclient.relay.OxRelay
import com.oxclient.relay.listener.OxAutoCodecListener
import com.oxclient.relay.listener.OxGameListener
import com.oxclient.relay.listener.OxLoginListener
import com.oxclient.relay.listener.OxPacketListener
import io.netty.util.internal.PlatformDependent
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import timber.log.Timber
import java.util.*

class OxRelaySession internal constructor(
    peer       : BedrockPeer,
    subClientId: Int,
    val relay  : OxRelay
) {
    val serverSide = ServerSide(peer, subClientId)
    var clientSide : ClientSide? = null
        internal set(value) {
            if (value != null) syncAndFlush(value)
            field = value
        }

    val listeners: MutableList<OxPacketListener> = ArrayList()
    private val packetQueue: Queue<Pair<BedrockPacket, Boolean>> = PlatformDependent.newMpscQueue()
    private val MAX_QUEUE = 1000

    // ── Gönderme API ─────────────────────────────────────────────────────────

    fun clientBound(packet: BedrockPacket) {
        runCatching { serverSide.sendPacket(packet) }
            .onFailure { Timber.e(it, "clientBound hata") }
    }

    fun clientBoundImmediate(packet: BedrockPacket) {
        runCatching { serverSide.sendPacketImmediately(packet) }
            .onFailure { Timber.e(it, "clientBoundImmediate hata") }
    }

    fun serverBound(packet: BedrockPacket) {
        val c = clientSide
        if (c != null) {
            runCatching { c.sendPacket(packet) }.onFailure { Timber.e(it, "serverBound hata") }
        } else {
            if (packetQueue.size < MAX_QUEUE) packetQueue.add(packet to false)
        }
    }

    fun serverBoundImmediate(packet: BedrockPacket) {
        val c = clientSide
        if (c != null) {
            runCatching { c.sendPacketImmediately(packet) }.onFailure { Timber.e(it, "serverBoundImmediate hata") }
        } else {
            if (packetQueue.size < MAX_QUEUE) packetQueue.add(packet to true)
        }
    }

    // ── Listener kurulumu ─────────────────────────────────────────────────────

    internal fun addDefaultListeners(remoteAddress: OxAddress, mcToken: String, gamertag: String) {
        listeners.add(OxAutoCodecListener(this))
        listeners.add(OxLoginListener(this, remoteAddress, mcToken, gamertag))
        listeners.add(OxGameListener(this))
    }

    // ── Senkronizasyon + kuyruk flush ─────────────────────────────────────────

    private fun syncAndFlush(cs: ClientSide) {
        runCatching {
            cs.codec = serverSide.codec
            val sh = serverSide.peer.codecHelper
            val ch = cs.peer.codecHelper
            ch.blockDefinitions        = sh.blockDefinitions
            ch.itemDefinitions         = sh.itemDefinitions
            ch.cameraPresetDefinitions = sh.cameraPresetDefinitions
            tryTransferEncodingSettings(sh, ch)
        }.onFailure { Timber.e(it, "syncDefinitions hata") }

        var n = 0
        while (true) {
            val (pkt, imm) = packetQueue.poll() ?: break
            runCatching { if (imm) cs.sendPacketImmediately(pkt) else cs.sendPacket(pkt); n++ }
        }
        if (n > 0) Timber.d("$n kuyruk paketi gönderildi")
    }

    /**
     * encodingSettings bedrock-connection 3.0.0.Beta1'de API'de farklı adlandırılmış veya
     * hiç olmayabilir. Reflection ile güvenli şekilde kopyalarız.
     */
    private fun tryTransferEncodingSettings(src: Any, dst: Any) {
        try {
            val getter = src.javaClass.getMethod("getEncodingSettings")
            val value  = getter.invoke(src) ?: return
            val setter = dst.javaClass.getMethod("setEncodingSettings", value.javaClass)
            setter.invoke(dst, value)
        } catch (_: Throwable) {
            // Bu API versiyonunda encodingSettings yok — sessizce geç
        }
    }

    // ── İç session'lar ────────────────────────────────────────────────────────

    /**
     * ServerSide — MC uygulamasından (istemciden) gelen bağlantı.
     *
     * bedrock-connection 3.0.0.Beta1'de BedrockPacketHandler.onDisconnect(CharSequence)
     * interface'den kaldırıldı. Disconnect'i BedrockSession.disconnect(String?) override
     * ederek yakalıyoruz.
     */
    inner class ServerSide(peer: BedrockPeer, subClientId: Int) :
        BedrockServerSession(peer, subClientId) {

        init {
            packetHandler = object : BedrockPacketHandler {}
        }

        override fun disconnect(reason: String?) {
            Timber.i("[Session] MC uygulama kesildi: $reason")
            runCatching { clientSide?.disconnect("İstemci kesildi") }
            listeners.forEach { runCatching { it.onDisconnect(reason ?: "unknown") } }
            super.disconnect(reason)
            relay.stop()
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                for (listener in listeners) {
                    try { if (listener.beforeClientBound(wrapper.packet)) return }
                    catch (e: Throwable) { Timber.e(e, "beforeClientBound hata") }
                }
                val raw = UnknownPacket().apply {
                    payload  = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                    packetId = wrapper.packetId
                }
                serverBound(raw)
                for (listener in listeners) {
                    try { listener.afterClientBound(wrapper.packet) }
                    catch (e: Throwable) { Timber.e(e, "afterClientBound hata") }
                }
            } catch (e: Exception) { Timber.e(e, "ServerSide.onPacket hata") }
        }
    }

    /**
     * ClientSide — uzak Bedrock sunucusuna olan bağlantı.
     */
    inner class ClientSide(peer: BedrockPeer, subClientId: Int) :
        BedrockClientSession(peer, subClientId) {

        init {
            packetHandler = object : BedrockPacketHandler {}
        }

        override fun disconnect(reason: String?) {
            Timber.i("[Session] Sunucu kesildi: $reason")
            runCatching { serverSide.disconnect(reason ?: "unknown") }
            listeners.forEach { runCatching { it.onDisconnect(reason ?: "unknown") } }
            super.disconnect(reason)
            relay.stop()
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                for (listener in listeners) {
                    try { if (listener.beforeServerBound(wrapper.packet)) return }
                    catch (e: Throwable) { Timber.e(e, "beforeServerBound hata") }
                }
                val raw = UnknownPacket().apply {
                    payload  = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
                    packetId = wrapper.packetId
                }
                clientBound(raw)
                for (listener in listeners) {
                    try { listener.afterServerBound(wrapper.packet) }
                    catch (e: Throwable) { Timber.e(e, "afterServerBound hata") }
                }
            } catch (e: Exception) { Timber.e(e, "ClientSide.onPacket hata") }
        }
    }
}
