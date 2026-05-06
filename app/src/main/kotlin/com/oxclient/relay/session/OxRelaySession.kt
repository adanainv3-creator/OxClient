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

/**
 * OxRelaySession — MC istemcisi (serverSide) ↔ Gerçek sunucu (clientSide) köprüsü.
 */
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

    private val packetQueue: Queue<Pair<BedrockPacket, Boolean>> =
        PlatformDependent.newMpscQueue()
    private val MAX_QUEUE = 512

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
            runCatching { c.sendPacket(packet) }
                .onFailure { Timber.e(it, "serverBound hata") }
        } else {
            if (packetQueue.size < MAX_QUEUE) packetQueue.add(packet to false)
            else Timber.w("serverBound kuyruk dolu, paket düşürüldü")
        }
    }

    fun serverBoundImmediate(packet: BedrockPacket) {
        val c = clientSide
        if (c != null) {
            runCatching { c.sendPacketImmediately(packet) }
                .onFailure { Timber.e(it, "serverBoundImmediate hata") }
        } else {
            if (packetQueue.size < MAX_QUEUE) packetQueue.add(packet to true)
        }
    }

    // ── Listener zinciri kurulumu ─────────────────────────────────────────────

    internal fun addDefaultListeners(
        remoteAddress: OxAddress,
        mcToken      : String,
        gamertag     : String
    ) {
        listeners.add(OxAutoCodecListener(this))
        listeners.add(OxLoginListener(this, remoteAddress, mcToken, gamertag))
        listeners.add(OxGameListener(this))
    }

    // ── Definition senkronu + kuyruk flush ───────────────────────────────────

    private fun syncAndFlush(cs: ClientSide) {
        runCatching {
            cs.codec = serverSide.codec
            val sh   = serverSide.peer.codecHelper
            val ch   = cs.peer.codecHelper
            ch.blockDefinitions        = sh.blockDefinitions
            ch.itemDefinitions         = sh.itemDefinitions
            ch.cameraPresetDefinitions = sh.cameraPresetDefinitions
            ch.encodingSettings        = sh.encodingSettings
        }.onFailure { Timber.e(it, "syncDefinitions hata") }

        var n = 0
        while (true) {
            val (pkt, immediate) = packetQueue.poll() ?: break
            runCatching {
                if (immediate) cs.sendPacketImmediately(pkt) else cs.sendPacket(pkt)
                n++
            }
        }
        if (n > 0) Timber.d("$n kuyruk paketi flush edildi")
    }

    // ── İç session sınıfları ─────────────────────────────────────────────────

    inner class ServerSide(peer: BedrockPeer, subClientId: Int) :
        BedrockServerSession(peer, subClientId) {

        init {
            packetHandler = object : BedrockPacketHandler {
                override fun onDisconnect(reason: CharSequence) {
                    Timber.i("[Session] MC uygulama kesildi: $reason")
                    runCatching { clientSide?.disconnect("İstemci kesildi") }
                    listeners.forEach { runCatching { it.onDisconnect(reason.toString()) } }
                    relay.stop()
                }
            }
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            route(wrapper,
                beforeFn  = { l, p -> l.beforeClientBound(p) },
                afterFn   = { l, p -> l.afterClientBound(p) },
                forwardFn = { raw -> serverBound(raw) }
            )
        }
    }

    inner class ClientSide(peer: BedrockPeer, subClientId: Int) :
        BedrockClientSession(peer, subClientId) {

        init {
            packetHandler = object : BedrockPacketHandler {
                override fun onDisconnect(reason: CharSequence) {
                    Timber.i("[Session] Sunucu kesildi: $reason")
                    runCatching { serverSide.disconnect(reason.toString()) }
                    listeners.forEach { runCatching { it.onDisconnect(reason.toString()) } }
                    relay.stop()
                }
            }
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            route(wrapper,
                beforeFn  = { l, p -> l.beforeServerBound(p) },
                afterFn   = { l, p -> l.afterServerBound(p) },
                forwardFn = { raw -> clientBound(raw) }
            )
        }
    }

    // ── Ortak paket yönlendirme ───────────────────────────────────────────────

    private fun route(
        wrapper  : BedrockPacketWrapper,
        beforeFn : (OxPacketListener, BedrockPacket) -> Boolean,
        afterFn  : (OxPacketListener, BedrockPacket) -> Unit,
        forwardFn: (BedrockPacket) -> Unit
    ) {
        val decoded = wrapper.packet

        for (listener in listeners) {
            try { if (beforeFn(listener, decoded)) return }
            catch (e: Throwable) { Timber.e(e, "beforeFn hata: ${listener.javaClass.simpleName}") }
        }

        val buffer = wrapper.packetBuffer.retainedSlice().skipBytes(wrapper.headerLength)
        val raw    = UnknownPacket().apply {
            payload  = buffer
            packetId = wrapper.packetId
        }
        forwardFn(raw)

        for (listener in listeners) {
            try { afterFn(listener, decoded) }
            catch (e: Throwable) { Timber.e(e, "afterFn hata: ${listener.javaClass.simpleName}") }
        }
    }
}
