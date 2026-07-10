package com.oxclient.core.relay.listener

import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

interface OxPacketListener {

    fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean = true

    fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean = true

    fun onSessionStart(session: OxRelaySession) {}

    fun onSessionEnd(session: OxRelaySession) {}

    val priority: Int get() = 0
}
