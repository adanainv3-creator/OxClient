package com.nexoraclient.core.relay.listener

import com.nexoraclient.core.relay.NexoraRelaySession
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

interface NexoraPacketListener {

    fun onClientPacket(packet: BedrockPacket, session: NexoraRelaySession): Boolean = true

    fun onServerPacket(packet: BedrockPacket, session: NexoraRelaySession): Boolean = true

    fun onSessionStart(session: NexoraRelaySession) {}

    fun onSessionEnd(session: NexoraRelaySession) {}

    val priority: Int get() = 0
}
