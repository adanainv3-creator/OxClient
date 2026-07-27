package com.nexoraclient.events

import com.nexoraclient.core.relay.NexoraRelaySession
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

class PacketEvent(
    val packet   : BedrockPacket,
    val direction: Direction,
    val session  : NexoraRelaySession
) {
    enum class Direction {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT
    }

    var isCancelled: Boolean = false
        private set

    var replacementPacket: BedrockPacket? = null

    fun cancel() { isCancelled = true }

    fun cancelAndReplace(pkt: BedrockPacket) {
        replacementPacket = pkt
        isCancelled = false
    }

    val isClientToServer: Boolean get() = direction == Direction.CLIENT_TO_SERVER
    val isServerToClient: Boolean get() = direction == Direction.SERVER_TO_CLIENT
    val packetName      : String  get() = packet::class.simpleName ?: "UnknownPacket"

    val effectivePacket: BedrockPacket get() = replacementPacket ?: packet
}
