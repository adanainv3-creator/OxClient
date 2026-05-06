package com.oxclient.events

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * Relay katmanından yayımlanan paket olayı.
 * Modüller bu event'i alarak paketleri okur / değiştirir / iptal eder.
 */
data class PacketEvent(
    val direction : Int,
    val packetId  : Int,
    val payload   : ByteArray,
    /** Ham Bedrock paketi — codec decode edebilirse dolu, yoksa null */
    val packet    : BedrockPacket? = null
) {
    var isCancelled: Boolean = false

    companion object {
        const val DIRECTION_C2S = 0   // Client → Server
        const val DIRECTION_S2C = 1   // Server → Client
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketEvent) return false
        return direction == other.direction &&
               packetId  == other.packetId  &&
               payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = direction
        result = 31 * result + packetId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
