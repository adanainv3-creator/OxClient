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

        // ÖNEMLİ FIX: bu değerler eskiden Int.MAX_VALUE'ydi, yani kütüphanenin
        // kendi NBT/liste/byte-array boyut koruması pratikte tamamen devre
        // dışıydı. Bu ayar hem clientSession'a hem de (OxRelaySession.
        // connectToServer() içinde buradan kopyalandığı için) serverSession'a
        // uygulanıyor — yani gerçek sunucudan gelen kötü niyetli/aşırı büyük
        // item NBT'si (başka bir oyuncunun "NBT bomb" saldırısı) hiçbir sınıra
        // takılmadan okunmaya çalışılıyor, bu da 15-30 saniyelik donmalara
        // sebep oluyordu. Aşağıdaki değerler normal oyun verisi için
        // fazlasıyla cömert (hiçbir vanilla item/chunk verisi bu sınırlara
        // yaklaşmaz) ama kötü niyetli aşırı büyük değerleri hemen reddeder.
        private val SAFE_LIMITS = EncodingSettings.builder()
            .maxListSize(1_000_000)
            .maxByteArraySize(16 * 1024 * 1024)   // 16MB
            .maxNetworkNBTSize(4 * 1024 * 1024)   // 4MB — chunk/block-entity NBT'si için bolca yeter
            .maxItemNBTSize(1 * 1024 * 1024)      // 1MB — normal item NBT'si (enchant/lore/custom name) için fazlasıyla cömert
            .maxStringLength(32768)
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
                encodingSettings        = SAFE_LIMITS
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
