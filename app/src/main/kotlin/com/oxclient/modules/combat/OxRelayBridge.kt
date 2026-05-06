package com.oxclient.modules.combat

import com.oxclient.relay.session.OxRelaySession
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import timber.log.Timber

/**
 * OxRelayBridge — Modüllerin aktif OxRelaySession'a paket göndermesini sağlar.
 *
 * OxRelayService başladığında [attach], durduğunda [detach] çağrılır.
 */
object OxRelayBridge {

    @Volatile private var session: OxRelaySession? = null

    fun attach(s: OxRelaySession) { session = s; Timber.d("[Bridge] Session attach") }
    fun detach()                  { session = null; Timber.d("[Bridge] Session detach") }

    val isActive: Boolean get() = session != null

    /** Sunucuya paket gönder (C→S) */
    fun sendToServer(packet: BedrockPacket) {
        val s = session ?: run {
            Timber.w("[Bridge] sendToServer — session yok, paket düşürüldü")
            return
        }
        runCatching { s.serverBound(packet) }
            .onFailure { Timber.e(it, "[Bridge] sendToServer hata") }
    }

    /** İstemciye paket gönder (S→C) */
    fun sendToClient(packet: BedrockPacket) {
        val s = session ?: run {
            Timber.w("[Bridge] sendToClient — session yok, paket düşürüldü")
            return
        }
        runCatching { s.clientBound(packet) }
            .onFailure { Timber.e(it, "[Bridge] sendToClient hata") }
    }
}
