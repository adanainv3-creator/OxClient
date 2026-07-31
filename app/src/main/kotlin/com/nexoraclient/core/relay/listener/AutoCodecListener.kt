package com.rubidiumclient.core.relay.listener

import com.rubidiumclient.core.relay.Definitions
import com.rubidiumclient.core.relay.RubidiumRelay
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.core.relay.codec.CodecRegistry
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729
import org.cloudburstmc.protocol.bedrock.codec.v748.serializer.InventoryContentSerializer_v748
import org.cloudburstmc.protocol.bedrock.codec.v748.serializer.InventorySlotSerializer_v748
import org.cloudburstmc.protocol.bedrock.codec.v975.serializer.InventoryContentSerializer_v975
import org.cloudburstmc.protocol.bedrock.codec.v975.serializer.InventorySlotSerializer_v975
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoCodecListener(private val relay: RubidiumRelay? = null) : RubidiumPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"

        // KRİTİK FIX: patchCodec eskiden 729'un üzerindeki HER protokol için
        // sonsuza kadar v729 serializer'ını zorluyordu. Inventory Content/Slot
        // paket formatı 748 ve 975'te tekrar değişti — bu yüzden üst
        // sürümlerde (748+, 975+) hâlâ eski v729 serializer'ıyla parse
        // edilen item'lar yanlış decode oluyor ve envanterde hiç görünmüyordu.
        // Artık her serializer, tanıtıldığı sürümden bir sonraki büyük format
        // değişikliğine kadar olan aralıkta kullanılıyor (en yüksek eşleşen
        // sürüm önce kontrol edilir).
        private fun patchCodec(codec: BedrockCodec): BedrockCodec {
            val v = codec.protocolVersion
            val (contentSerializer, slotSerializer) = when {
                v >= 975  -> InventoryContentSerializer_v975.INSTANCE  to InventorySlotSerializer_v975.INSTANCE
                v >= 748  -> InventoryContentSerializer_v748.INSTANCE  to InventorySlotSerializer_v748.INSTANCE
                v > 729   -> InventoryContentSerializer_v729.INSTANCE  to InventorySlotSerializer_v729.INSTANCE
                else      -> return codec
            }
            return codec.toBuilder()
                .updateSerializer(InventoryContentPacket::class.java, contentSerializer)
                .updateSerializer(InventorySlotPacket::class.java, slotSerializer)
                .build()
        }

        // ÖNEMLİ FIX: bu değerler eskiden Int.MAX_VALUE'ydi, yani kütüphanenin
        // kendi NBT/liste/byte-array boyut koruması pratikte tamamen devre
        // dışıydı. Bu ayar hem clientSession'a hem de (RubidiumRelaySession.
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

    override fun onSessionStart(session: RubidiumRelaySession) { done = false }

    override fun onClientPacket(packet: BedrockPacket, session: RubidiumRelaySession): Boolean {
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
