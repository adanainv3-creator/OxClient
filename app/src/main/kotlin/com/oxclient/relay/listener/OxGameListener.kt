package com.oxclient.relay.listener

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.modules.combat.OxInventoryTracker
import com.oxclient.relay.session.OxRelaySession
import org.cloudburstmc.protocol.bedrock.packet.*
import timber.log.Timber

/**
 * OxGameListener — Oyun paketlerini modüllere yayımlar ve envanter takibini besler.
 */
class OxGameListener(private val session: OxRelaySession) : OxPacketListener {

    override fun afterServerBound(packet: BedrockPacket) {
        when (packet) {
            is StartGamePacket -> {
                Timber.i("[Game] StartGamePacket — ${packet.levelName}")
                // Tanımları clientSide'a aktar
                runCatching {
                    val sh = session.serverSide.peer.codecHelper
                    session.clientSide?.peer?.codecHelper?.let { ch ->
                        ch.blockDefinitions = sh.blockDefinitions
                        ch.itemDefinitions  = sh.itemDefinitions
                    }
                }
            }
            is InventoryContentPacket -> OxInventoryTracker.onInventoryContent(packet)
            is InventorySlotPacket    -> OxInventoryTracker.onInventorySlot(packet)
            else -> {}
        }
        // Sunucu→İstemci yönü modüllere yayımla
        publishEvent(packet, PacketEvent.DIRECTION_S2C)
    }

    override fun afterClientBound(packet: BedrockPacket) {
        // İstemci→Sunucu yönü modüllere yayımla
        publishEvent(packet, PacketEvent.DIRECTION_C2S)
    }

    override fun onDisconnect(reason: String) {
        OxInventoryTracker.clear()
        PacketEventBus.clear()
        Timber.i("[Game] Bağlantı kesildi, envanter temizlendi")
    }

    private fun publishEvent(packet: BedrockPacket, direction: Int) {
        runCatching {
            PacketEventBus.publish(PacketEvent(
                direction = direction,
                packetId  = 0,
                payload   = ByteArray(0),
                packet    = packet
            ))
        }.onFailure { Timber.e(it, "[Game] publishEvent hata") }
    }
}
