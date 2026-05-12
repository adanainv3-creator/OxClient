package com.oxclient.core.relay

import com.oxclient.core.relay.listener.OxPacketListener
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import io.netty.util.internal.PlatformDependent
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import java.util.*

/**
 * OxRelaySession — İstemci (Minecraft) ↔ Sunucu (2b2t) köprüsü.
 *
 * İki iç sınıf:
 *   ServerSession — istemciden gelen paketleri alır (C→S yönü)
 *   ClientSession — sunucudan gelen paketleri alır (S→C yönü)
 *
 * Her iki yönde de:
 *   1. OxPacketListener.before* → modifiye etme / iptal etme şansı
 *   2. PacketEventBus.publish() → OxClient modülleri (KillAura vb.)
 *   3. Paket karşı tarafa iletilir (UnknownPacket olarak — ham bytes)
 *   4. OxPacketListener.after* → son işlemler
 */
class OxRelaySession internal constructor(
    peer: BedrockPeer,
    subClientId: Int,
    val relay: OxRelay
) {
    val server = ServerSession(peer, subClientId)

    var client: ClientSession? = null
        internal set(value) {
            value?.let { cs ->
                try {
                    cs.codec = server.codec
                    cs.peer.codecHelper.blockDefinitions    = server.peer.codecHelper.blockDefinitions
                    cs.peer.codecHelper.itemDefinitions     = server.peer.codecHelper.itemDefinitions
                    cs.peer.codecHelper.cameraPresetDefinitions = server.peer.codecHelper.cameraPresetDefinitions
                    cs.peer.codecHelper.encodingSettings    = server.peer.codecHelper.encodingSettings

                    // Client bağlandıktan önce kuyruğa alınan paketleri gönder
                    var processed = 0
                    var item: Pair<BedrockPacket, Boolean>?
                    while (packetQueue.poll().also { item = it } != null) {
                        runCatching {
                            if (item!!.second) cs.sendPacketImmediately(item!!.first)
                            else               cs.sendPacket(item!!.first)
                            processed++
                        }
                    }
                    if (processed > 0)
                        android.util.Log.d("OxRelaySession", "Kuyruktan $processed paket gönderildi")
                } catch (e: Exception) {
                    android.util.Log.e("OxRelaySession", "Client init hatası: ${e.message}")
                }
            }
            field = value
        }

    val listeners: MutableList<OxPacketListener> = ArrayList()

    private val packetQueue: Queue<Pair<BedrockPacket, Boolean>> =
        PlatformDependent.newMpscQueue()

    // ── Yardımcı göndericiler ─────────────────────────────────────────────

    /** Paketi istemciye (Minecraft) gönder */
    fun clientBound(packet: BedrockPacket) {
        runCatching { server.sendPacket(packet) }
            .onFailure { android.util.Log.e("OxRelaySession", "clientBound hatası: ${it.message}") }
    }

    fun clientBoundImmediately(packet: BedrockPacket) {
        runCatching { server.sendPacketImmediately(packet) }
            .onFailure { android.util.Log.e("OxRelaySession", "clientBoundImm hatası: ${it.message}") }
    }

    /** Paketi sunucuya (2b2t) gönder */
    fun serverBound(packet: BedrockPacket) {
        val c = client
        if (c != null) {
            runCatching { c.sendPacket(packet) }
                .onFailure { android.util.Log.e("OxRelaySession", "serverBound hatası: ${it.message}") }
        } else {
            if (packetQueue.size < 1000) packetQueue.add(packet to false)
            else android.util.Log.w("OxRelaySession", "Kuyruk dolu, paket atıldı")
        }
    }

    fun serverBoundImmediately(packet: BedrockPacket) {
        val c = client
        if (c != null) {
            runCatching { c.sendPacketImmediately(packet) }
                .onFailure { android.util.Log.e("OxRelaySession", "serverBoundImm hatası: ${it.message}") }
        } else {
            if (packetQueue.size < 1000) packetQueue.add(packet to true)
            else android.util.Log.w("OxRelaySession", "Kuyruk dolu, paket atıldı")
        }
    }

    // ── İstemciden gelen paketler (C→S) ──────────────────────────────────

    inner class ServerSession(peer: BedrockPeer, subClientId: Int) :
        BedrockServerSession(peer, subClientId) {

        init {
            packetHandler = object : BedrockPacketHandler {
                override fun onDisconnect(reason: CharSequence) {
                    android.util.Log.i("OxRelaySession", "İstemci bağlantısı kesildi: $reason")
                    runCatching { client?.disconnect() }
                    listeners.forEach { runCatching { it.onDisconnect(reason.toString()) } }
                    relay.connectionManager?.cleanup()
                }
            }
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                // 1. Listener'lar: before → iptal edilirse ilet
                for (l in listeners) {
                    try { if (l.beforeClientBound(wrapper.packet)) return }
                    catch (e: Throwable) { android.util.Log.e("OxRelaySession", "beforeClientBound: ${e.message}") }
                }

                // 2. PacketEventBus — C→S yönü (modüller görsün)
                val event = PacketEvent(
                    packetId  = wrapper.packetId,
                    packet    = wrapper.packet,
                    direction = PacketEvent.Direction.CLIENT_TO_SERVER,
                    session   = this@OxRelaySession
                )
                PacketEventBus.publish(event)

                // 3. Ham paketi sunucuya ilet (modifiye edilmediyse orijinal)
                if (!event.isCancelled) {
                    val outPacket = event.replacementPacket ?: run {
                        val buf = wrapper.packetBuffer
                            .retainedSlice()
                            .skipBytes(wrapper.headerLength)
                        UnknownPacket().also {
                            it.payload  = buf
                            it.packetId = wrapper.packetId
                        }
                    }
                    serverBound(outPacket)
                }

                // 4. after
                for (l in listeners) {
                    runCatching { l.afterClientBound(wrapper.packet) }
                }
            } catch (e: Exception) {
                android.util.Log.e("OxRelaySession", "C→S paket hatası: ${e.message}")
            }
        }
    }

    // ── Sunucudan gelen paketler (S→C) ────────────────────────────────────

    inner class ClientSession(peer: BedrockPeer, subClientId: Int) :
        BedrockClientSession(peer, subClientId) {

        init {
            packetHandler = object : BedrockPacketHandler {
                override fun onDisconnect(reason: CharSequence) {
                    android.util.Log.i("OxRelaySession", "Sunucu bağlantısı kesildi: $reason")
                    runCatching { server.disconnect(reason.toString()) }
                    listeners.forEach { runCatching { it.onDisconnect(reason.toString()) } }
                    relay.connectionManager?.cleanup()
                }
            }
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                // 1. before
                for (l in listeners) {
                    try { if (l.beforeServerBound(wrapper.packet)) return }
                    catch (e: Throwable) { android.util.Log.e("OxRelaySession", "beforeServerBound: ${e.message}") }
                }

                // 2. PacketEventBus — S→C yönü
                val event = PacketEvent(
                    packetId  = wrapper.packetId,
                    packet    = wrapper.packet,
                    direction = PacketEvent.Direction.SERVER_TO_CLIENT,
                    session   = this@OxRelaySession
                )
                PacketEventBus.publish(event)

                // 3. Ham paketi istemciye ilet
                if (!event.isCancelled) {
                    val outPacket = event.replacementPacket ?: run {
                        val buf = wrapper.packetBuffer
                            .retainedSlice()
                            .skipBytes(wrapper.headerLength)
                        UnknownPacket().also {
                            it.payload  = buf
                            it.packetId = wrapper.packetId
                        }
                    }
                    clientBound(outPacket)
                }

                // 4. after
                for (l in listeners) {
                    runCatching { l.afterServerBound(wrapper.packet) }
                }
            } catch (e: Exception) {
                android.util.Log.e("OxRelaySession", "S→C paket hatası: ${e.message}")
            }
        }
    }
}
