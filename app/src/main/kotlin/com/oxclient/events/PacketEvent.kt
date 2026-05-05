package com.oxclient.events

/**
 * Inject katmanından yayımlanan paket olayı.
 *
 * [direction]   → DIRECTION_C2S veya DIRECTION_S2C
 * [packetId]    → Ham paket kimliği (inject katmanından gelir)
 * [payload]     → Ham bayt verisi (inject katmanından gelir)
 * [isCancelled] → true olursa dinleyici zinciri kırılır
 */
data class PacketEvent(
    val direction : Int,
    val packetId  : Int,
    val payload   : ByteArray
) {
    var isCancelled: Boolean = false

    companion object {
        const val DIRECTION_C2S = 0  // Client → Server
        const val DIRECTION_S2C = 1  // Server → Client
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketEvent) return false
        return direction == other.direction && packetId == other.packetId && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = direction
        result = 31 * result + packetId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
