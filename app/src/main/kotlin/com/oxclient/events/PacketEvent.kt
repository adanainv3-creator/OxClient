package com.oxclient.events

/**
 * PacketEvent
 *
 * OxVpnService → PacketProcessor tarafından publish edilir.
 * Modüller onPacket() içinde bu event'i alarak paketi değiştirebilir
 * veya iptal edebilir.
 */
data class PacketEvent(
    val packetId  : Int,
    val data      : ByteArray,
    val direction : Direction
) {
    var isCancelled: Boolean = false
    var modifiedData: ByteArray? = null

    enum class Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT }
}
