package com.oxclient.relay.listener

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * OxPacketListener — Relay listener zinciri arayüzü.
 *
 * [beforeClientBound] : MC sunucusundan gelen paket → istemciye iletilmeden önce.
 *   return true → paketi blokla (istemciye iletme).
 *
 * [beforeServerBound] : MC istemcisinden gelen paket → sunucuya iletilmeden önce.
 *   return true → paketi blokla (sunucuya iletme).
 *
 * [afterClientBound]  : Paket istemciye iletildikten sonra (okuma amaçlı).
 * [afterServerBound]  : Paket sunucuya iletildikten sonra (okuma amaçlı).
 */
interface OxPacketListener {

    /** MC sunucusu → istemci yönü — true dönerse iletilmez */
    fun beforeClientBound(packet: BedrockPacket): Boolean = false

    /** MC istemcisi → sunucu yönü — true dönerse iletilmez */
    fun beforeServerBound(packet: BedrockPacket): Boolean = false

    /** Paket istemciye iletildikten sonra */
    fun afterClientBound(packet: BedrockPacket) {}

    /** Paket sunucuya iletildikten sonra */
    fun afterServerBound(packet: BedrockPacket) {}

    /** Bağlantı kesildi */
    fun onDisconnect(reason: String) {}
}
