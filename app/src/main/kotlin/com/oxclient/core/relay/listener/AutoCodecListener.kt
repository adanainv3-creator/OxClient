package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.relay.OxCodecRegistry
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.definition.Definitions
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket

/**
 * AutoCodecListener — İstemcinin protokol versiyonunu algılar,
 * uygun BedrockCodec'i seçer ve sıkıştırmayı etkinleştirir.
 *
 * WRelay AutoCodecPacketListener'dan adapte edildi.
 */
class AutoCodecListener(
    private val session: OxRelaySession
) : OxPacketListener {

    private val TAG = "AutoCodecListener"

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (packet is RequestNetworkSettingsPacket) {
            try {
                val codec = OxCodecRegistry.getClosestCodec(packet.protocolVersion)
                Log.i(TAG, "Protokol ${packet.protocolVersion} → codec ${codec.protocolVersion} (${codec.minecraftVersion})")

                session.server.codec = codec
                session.server.peer.codecHelper.apply {
                    itemDefinitions         = Definitions.itemDefinitions
                    blockDefinitions        = Definitions.blockDefinitions
                    cameraPresetDefinitions = Definitions.cameraPresetDefinitions
                    encodingSettings        = EncodingSettings.builder()
                        .maxListSize(Int.MAX_VALUE)
                        .maxByteArraySize(Int.MAX_VALUE)
                        .maxNetworkNBTSize(Int.MAX_VALUE)
                        .maxItemNBTSize(Int.MAX_VALUE)
                        .maxStringLength(Int.MAX_VALUE)
                        .build()
                }

                // NetworkSettings → ZLIB sıkıştırma etkinleştir
                val netSettings = NetworkSettingsPacket().apply {
                    compressionThreshold = 1
                    compressionAlgorithm = PacketCompressionAlgorithm.ZLIB
                }
                session.clientBoundImmediately(netSettings)
                session.server.setCompression(PacketCompressionAlgorithm.ZLIB)
                Log.i(TAG, "ZLIB sıkıştırma etkinleştirildi (threshold=1)")

            } catch (e: Exception) {
                Log.e(TAG, "NetworkSettings kurulum hatası: ${e.message}", e)
                session.server.disconnect("NetworkSettings kurulum hatası: ${e.message}")
            }
            return true  // paketi durdur — sunucuya gitmesine gerek yok
        }
        return false
    }
}
