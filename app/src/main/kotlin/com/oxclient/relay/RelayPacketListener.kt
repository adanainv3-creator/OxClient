package com.oxclient.relay

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * RelayPacketListener
 *
 * MITM relay'in paket intercept arayüzü.
 *
 * [beforeClientBound] / [beforeServerBound]:
 *   - `true`  döndürürse paketi yakala (sunucuya/client'a iletme)
 *   - `false` döndürürse normal akış devam eder
 *
 * [afterClientBound] / [afterServerBound]:
 *   - Paket iletildikten sonra çağrılır, her zaman tetiklenir
 */
interface RelayPacketListener {

    /**
     * Gerçek sunucudan Minecraft'a gidecek paketi intercept et.
     * @return true → paketi yakala ve iletme
     */
    fun beforeClientBound(packet: BedrockPacket): Boolean = false

    /** Paket Minecraft'a iletildikten sonra */
    fun afterClientBound(packet: BedrockPacket) {}

    /**
     * Minecraft'tan gerçek sunucuya gidecek paketi intercept et.
     * @return true → paketi yakala ve iletme
     */
    fun beforeServerBound(packet: BedrockPacket): Boolean = false

    /** Paket gerçek sunucuya iletildikten sonra */
    fun afterServerBound(packet: BedrockPacket) {}

    /** Bağlantı kesildiğinde */
    fun onDisconnect() {}
}
