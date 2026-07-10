package com.oxclient.core.relay.listener

import com.oxclient.core.relay.Definitions
import com.oxclient.core.relay.OxRelay
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.core.relay.codec.CodecRegistry
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoCodecListener(private val relay: OxRelay? = null) : OxPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"

        private fun patchCodec(codec: BedrockCodec): BedrockCodec {
            return if (codec.protocolVersion > 729) {
                codec.toBuilder()
                    .updateSerializer(InventoryContentPacket::class.java, InventoryContentSerializer_v729.INSTANCE)
                    .updateSerializer(InventorySlotPacket::class.java, InventorySlotSerializer_v729.INSTANCE)
                    .build()
            } else codec
        }

        private val UNLIMITED = EncodingSettings.builder()
            .maxListSize(Int.MAX_VALUE)
            .maxByteArraySize(Int.MAX_VALUE)
            .maxNetworkNBTSize(Int.MAX_VALUE)
            .maxItemNBTSize(Int.MAX_VALUE)
            .maxStringLength(Int.MAX_VALUE)
            .build()
    }

    override val priority: Int = -10

    @Volatile private var done = false

    override fun onSessionStart(session: OxRelaySession) { done = false }

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (packet !is RequestNetworkSettingsPacket) return true
        if (done) return false
        done = true

        val protocol = packet.protocolVersion

        try {
            val raw   = CodecRegistry.getClosestCodec(protocol)
            val codec = patchCodec(raw)

            session.clientSession.codec = codec
            session.activeCodec = codec

            val defs = Definitions.getClosestDefinitions(codec.protocolVersion)
            session.clientSession.peer.codecHelper.apply {
                itemDefinitions         = defs.itemDefinitions
                blockDefinitions        = defs.blockDefinitions
                cameraPresetDefinitions = Definitions.cameraPresetDefinitions
                encodingSettings        = UNLIMITED
            }

            session.sendToClient(NetworkSettingsPacket().apply {
                compressionThreshold = 1
                compressionAlgorithm = PacketCompressionAlgorithm.ZLIB
            })

            session.clientSession.setCompression(PacketCompressionAlgorithm.ZLIB)

            relay?.updatePong(codec.protocolVersion, codec.minecraftVersion ?: "")

        } catch (e: Exception) {
            session.disconnect("NetworkSettings hatası: ${e.message}")
        }

        return false
    }
}
