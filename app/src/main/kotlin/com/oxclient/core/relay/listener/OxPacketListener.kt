package com.oxclient.core.relay.listener

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * OxPacketListener — Relay paket dinleyici arayüzü.
 * WRelayPacketListener'dan adapte edildi.
 *
 * before* → true döndürürse paket iletilmez (intercept/iptal)
 * after*  → bilgilendirme amaçlı, paket zaten iletildi
 */
interface OxPacketListener {

    /** İstemciden (Minecraft) gelen paket sunucuya iletilmeden önce.
     *  @return true → paketi durdur */
    fun beforeClientBound(packet: BedrockPacket): Boolean = false

    /** İstemciden gelen paket sunucuya iletildikten sonra. */
    fun afterClientBound(packet: BedrockPacket) {}

    /** Sunucudan (2b2t) gelen paket istemciye iletilmeden önce.
     *  @return true → paketi durdur */
    fun beforeServerBound(packet: BedrockPacket): Boolean = false

    /** Sunucudan gelen paket istemciye iletildikten sonra. */
    fun afterServerBound(packet: BedrockPacket) {}

    /** Bağlantı kesildi (her iki taraftan). */
    fun onDisconnect(reason: String) {}
}
