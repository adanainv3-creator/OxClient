package com.oxclient.relay.listener

import com.oxclient.relay.codec.OxCodecRegistry
import com.oxclient.relay.session.OxRelaySession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*
import timber.log.Timber

/**
 * OxAutoCodecListener — WClient AutoCodecPacketListener'ın OxClient karşılığı.
 *
 * 1. RequestNetworkSettingsPacket → codec seç, istemciye NetworkSettings gönder, compression aç.
 * 2. Sunucu NetworkSettings gönderirse → server tarafında compression aç.
 */
class OxAutoCodecListener(
    private val session   : OxRelaySession,
    private val patchCodec: Boolean = true
) : OxPacketListener {

    // ── İstemciden gelen paketler ─────────────────────────────────────────────

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (packet !is RequestNetworkSettingsPacket) return false

        try {
            val proto    = packet.protocolVersion
            val codec    = patchIfNeeded(OxCodecRegistry.getClosestCodec(proto))

            Timber.i("[AutoCodec] Client proto=$proto → codec=${codec.minecraftVersion}")

            session.serverSide.codec = codec
            session.serverSide.peer.codecHelper.apply {
                try {
                    encodingSettings = EncodingSettings.builder()
                        .maxListSize(Int.MAX_VALUE)
                        .maxByteArraySize(Int.MAX_VALUE)
                        .maxNetworkNBTSize(Int.MAX_VALUE)
                        .maxItemNBTSize(Int.MAX_VALUE)
                        .maxStringLength(Int.MAX_VALUE)
                        .build()
                } catch (_: Throwable) {
                    // Eski API — encodingSettings yoksa sessizce geç
                }
            }

            // İstemciye NetworkSettings gönder (compression başlatır)
            val nsPacket = NetworkSettingsPacket().apply {
                compressionThreshold = 1
                compressionAlgorithm = PacketCompressionAlgorithm.ZLIB
            }
            session.clientBoundImmediate(nsPacket)
            session.serverSide.setCompression(PacketCompressionAlgorithm.ZLIB)
            Timber.i("[AutoCodec] ✓ Compression ZLIB aktif (client)")

        } catch (e: Exception) {
            Timber.e(e, "[AutoCodec] RequestNetworkSettings işleme hatası")
            session.serverSide.disconnect("Network settings hatası: ${e.message}")
        }

        return true  // İstemciye bu paketi iletme, biz yanıtladık
    }

    // ── Sunucudan gelen paketler ──────────────────────────────────────────────

    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet !is NetworkSettingsPacket) return false

        val algo = packet.compressionAlgorithm ?: PacketCompressionAlgorithm.ZLIB
        Timber.i("[AutoCodec] Sunucu NetworkSettings: algo=$algo threshold=${packet.compressionThreshold}")

        runCatching { session.clientSide?.setCompression(algo) }
        return false  // İstemciye ilet
    }

    // ── Yardımcı ─────────────────────────────────────────────────────────────

    private fun patchIfNeeded(codec: BedrockCodec): BedrockCodec {
        return if (patchCodec && codec.protocolVersion > 729) {
            try {
                codec.toBuilder()
                    .updateSerializer(InventoryContentPacket::class.java, InventoryContentSerializer_v729.INSTANCE)
                    .updateSerializer(InventorySlotPacket::class.java, InventorySlotSerializer_v729.INSTANCE)
                    .build()
            } catch (e: Exception) {
                Timber.w("[AutoCodec] Codec patch başarısız: ${e.message}")
                codec
            }
        } else codec
    }
}
