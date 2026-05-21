package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.relay.Definitions
import com.oxclient.core.relay.OxRelay
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*

/**
 * AutoCodecListener — WRelay AutoCodecPacketListener ile birebir aynı davranış.
 *
 * RequestNetworkSettingsPacket gelince:
 *   1. Codec seç (protocol versiyonuna göre)
 *   2. Client codec'ini set et
 *   3. Definitions'ları HEMEN set et (Definitions.kt'den — önceden yüklenmiş)
 *      → WRelay'de de burada set ediliyor, StartGame'i beklemiyor
 *   4. EncodingSettings → sınırsız
 *   5. Client'a NetworkSettingsPacket gönder + ZLIB aç
 *   6. return false → server'a iletme
 */
class AutoCodecListener(private val relay: OxRelay? = null) : OxPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"

        private val CODEC_NAMES = listOf(
            "v975.Bedrock_v975", "v948.Bedrock_v948", "v935.Bedrock_v935",
            "v924.Bedrock_v924", "v818.Bedrock_v818", "v800.Bedrock_v800",
            "v786.Bedrock_v786", "v748.Bedrock_v748", "v729.Bedrock_v729",
            "v712.Bedrock_v712", "v686.Bedrock_v686", "v671.Bedrock_v671",
            "v662.Bedrock_v662", "v649.Bedrock_v649", "v630.Bedrock_v630",
        )
        private val BASE = "org.cloudburstmc.protocol.bedrock.codec."
        private val cache = HashMap<Int, BedrockCodec?>()

        @Synchronized
        fun findCodec(protocol: Int): BedrockCodec? {
            if (cache.containsKey(protocol)) return cache[protocol]
            for (name in CODEC_NAMES) {
                try {
                    val codec = Class.forName("$BASE$name").getField("CODEC").get(null) as? BedrockCodec
                        ?: continue
                    if (codec.protocolVersion == protocol) {
                        cache[protocol] = codec
                        return codec
                    }
                } catch (_: Exception) {}
            }
            cache[protocol] = null
            return null
        }

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
        Log.i(TAG, "RequestNetworkSettings — protocol=$protocol")

        try {
            val raw   = findCodec(protocol) ?: OxRelay.RELAY_CODEC
            val codec = patchCodec(raw)

            // 1. Client codec
            session.clientSession.codec = codec
            session.activeCodec = codec
            Log.i(TAG, "Codec: ${codec.protocolVersion} mc=${codec.minecraftVersion}")

            // 2. Definitions — WRelay AutoCodecPacketListener ile birebir aynı
            //    Block/item/camera definitions RequestNetworkSettings'te hemen set edilir.
            //    StartGame'i beklersek paket decode edilemez → timeout.
            session.clientSession.peer.codecHelper.apply {
                itemDefinitions         = Definitions.itemDefinitions
                blockDefinitions        = Definitions.blockDefinitions
                cameraPresetDefinitions = Definitions.cameraPresetDefinitions
                encodingSettings        = UNLIMITED
            }
            Log.i(TAG, "Definitions set edildi ✓")

            // 3. Client'a NetworkSettingsPacket gönder
            session.sendToClient(NetworkSettingsPacket().apply {
                compressionThreshold = 1
                compressionAlgorithm = PacketCompressionAlgorithm.ZLIB
            })

            // 4. Client ZLIB compression aç
            session.clientSession.setCompression(PacketCompressionAlgorithm.ZLIB)
            Log.i(TAG, "Client ZLIB açıldı ✓")

            // 5. Relay pong güncelle
            relay?.updatePong(codec.protocolVersion, codec.minecraftVersion ?: "")

        } catch (e: Exception) {
            Log.e(TAG, "RequestNetworkSettings işleme hatası: ${e.message}", e)
            session.disconnect("NetworkSettings hatası: ${e.message}")
        }

        return false // server'a iletme
    }
}
