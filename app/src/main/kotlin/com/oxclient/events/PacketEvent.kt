package com.oxclient.events

import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * PacketEvent — OxRelaySession tarafından publish edilir.
 *
 * Modüller onPacket() içinde:
 *   - isCancelled = true   → paketi durdur (karşı tarafa iletme)
 *   - replacementPacket    → farklı bir paket gönder
 *
 * Önceki versiyon raw ByteArray kullanıyordu.
 * Yeni versiyon doğrudan typed BedrockPacket nesnesi sağlar —
 * modüller cast ederek içeriğe kolayca erişebilir.
 */
data class PacketEvent(
    val packetId  : Int,
    val packet    : BedrockPacket,
    val direction : Direction,
    val session   : OxRelaySession
) {
    var isCancelled      : Boolean      = false
    var replacementPacket: BedrockPacket? = null

    enum class Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT }
}
