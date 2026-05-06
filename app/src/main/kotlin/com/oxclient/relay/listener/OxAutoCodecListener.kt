package com.oxclient.relay.listener

import com.oxclient.relay.session.OxRelaySession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v766.Bedrock_v766
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket
import timber.log.Timber

/**
 * OxAutoCodecListener — İstemcinin protokol versiyonunu tespit eder ve codec'i ayarlar.
 *
 * Adım 1: MC uygulaması RequestNetworkSettings gönderir (protokol versiyonu içerir).
 * Adım 2: Biz uygun codec'i seçip serverSide'a ayarlarız.
 * Adım 3: Sunucu NetworkSettings gönderdikten sonra compression başlar.
 */
class OxAutoCodecListener(
    private val session: OxRelaySession
) : OxPacketListener {

    private var codecSet = false

    // Desteklenen codec'ler (genişletilebilir)
    private val supportedCodecs: List<BedrockCodec> = listOf(
        Bedrock_v766.CODEC   // 1.21.50
    )

    /** İstemciden gelen RequestNetworkSettings paketini yakala */
    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (codecSet || packet !is RequestNetworkSettingsPacket) return false

        val proto    = packet.protocolVersion
        val codec    = findBestCodec(proto)

        Timber.i("[AutoCodec] Client protokol: $proto → ${codec.minecraftVersion}")

        // serverSide codec'i ayarla
        session.serverSide.codec = codec
        codecSet = true

        return false  // paketi bloklamıyoruz
    }

    /** Sunucudan gelen NetworkSettings paketinde compression aç */
    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet !is NetworkSettingsPacket) return false

        val algo = when (packet.compressionAlgorithm) {
            PacketCompressionAlgorithm.ZLIB  -> PacketCompressionAlgorithm.ZLIB
            PacketCompressionAlgorithm.SNAPPY -> PacketCompressionAlgorithm.SNAPPY
            else -> PacketCompressionAlgorithm.ZLIB
        }

        Timber.i("[AutoCodec] Compression: $algo threshold=${packet.compressionThreshold}")

        // Her iki tarafta da compression'ı etkinleştir
        session.serverSide.setCompression(algo)
        session.clientSide?.setCompression(algo)

        return false
    }

    private fun findBestCodec(protocolVersion: Int): BedrockCodec {
        return supportedCodecs.firstOrNull { it.protocolVersion == protocolVersion }
            ?: supportedCodecs.maxByOrNull { it.protocolVersion }
            ?: Bedrock_v766.CODEC
    }
}
