package com.oxclient.core.relay.listener

import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * OxPacketListener — relay pipeline'ına eklenen her dinleyicinin implement ettiği arayüz.
 *
 * Her paket için iki yön metodu çağrılır:
 *   [onClientPacket] → Client'tan Server'a giden paketler
 *   [onServerPacket] → Server'dan Client'a gelen paketler
 *
 * `false` döndürmek paketi engeller (drop eder); `true` döndürmek iletmeye devam eder.
 *
 * Oturum yaşam döngüsü için [onSessionStart] / [onSessionEnd] override edilebilir.
 */
interface OxPacketListener {

    /**
     * Client → Server yönündeki paketi işler.
     * @return `true` → paketi server'a ilet, `false` → paketi engelle (drop)
     */
    fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean = true

    /**
     * Server → Client yönündeki paketi işler.
     * @return `true` → paketi client'a ilet, `false` → paketi engelle (drop)
     */
    fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean = true

    /** Yeni bir relay oturumu (downstream + upstream bağlantısı) kurulduğunda. */
    fun onSessionStart(session: OxRelaySession) {}

    /** Relay oturumu kapandığında / bağlantı kesildiğinde. */
    fun onSessionEnd(session: OxRelaySession) {}

    /** Listener'ın önceliği — düşük sayı = daha erken çağrılır. */
    val priority: Int get() = 0
}
