package com.oxclient.relay.listener

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.modules.combat.OxInventoryTracker
import com.oxclient.relay.session.OxRelaySession
import org.cloudburstmc.protocol.bedrock.packet.*
import timber.log.Timber

/**
 * OxGameListener — Oyun süreci paket hook'ları.
 *
 * - Envanter paketlerini OxInventoryTracker'a besler
 * - Her paketi PacketEventBus üzerinden modüllere yayımlar
 * - StartGamePacket — definition'ları istemciye senkronize eder
 */
class OxGameListener(
    private val session: OxRelaySession
) : OxPacketListener {

    // ── Sunucudan gelen paketler ──────────────────────────────────────────────

    override fun afterServerBound(packet: BedrockPacket) {
        when (packet) {
            is StartGamePacket -> {
                Timber.i("[Game] StartGamePacket — world=${packet.levelName}")
                // Block/item tanımlarını codec helper'a aktar
                val sh = session.serverSide.peer.codecHelper
                val ch = session.clientSide?.peer?.codecHelper
                ch?.blockDefinitions = sh.blockDefinitions
                ch?.itemDefinitions  = sh.itemDefinitions
            }
            is InventoryContentPacket -> OxInventoryTracker.onInventoryContent(packet)
            is InventorySlotPacket    -> OxInventoryTracker.onInventorySlot(packet)
            else -> {}
        }

        // Tüm sunucu→istemci paketlerini modüllere yayımla
        publishEvent(packet, PacketEvent.DIRECTION_S2C)
    }

    // ── İstemciden gelen paketler ─────────────────────────────────────────────

    override fun afterClientBound(packet: BedrockPacket) {
        publishEvent(packet, PacketEvent.DIRECTION_C2S)
    }

    // ── Bağlantı koptu ───────────────────────────────────────────────────────

    override fun onDisconnect(reason: String) {
        OxInventoryTracker.clear()
        PacketEventBus.clear()
        Timber.i("[Game] Bağlantı kesildi, envanter temizlendi")
    }

    // ── Yardımcı ─────────────────────────────────────────────────────────────

    private fun publishEvent(packet: BedrockPacket, direction: Int) {
        try {
            val event = PacketEvent(
                direction = direction,
                packetId  = 0,           // ham id ihtiyaç duyulmaz — packet objesi var
                payload   = ByteArray(0),
                packet    = packet
            )
            PacketEventBus.publish(event)
        } catch (e: Exception) {
            Timber.e(e, "[Game] publishEvent hata")
        }
    }
}
