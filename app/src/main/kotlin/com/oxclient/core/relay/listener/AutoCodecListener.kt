package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.relay.OxRelay
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*

/**
 * AutoCodecListener — WRelay AutoCodecPacketListener ile birebir aynı davranış.
 *
 * ═══════════════════════════════════════════════════════════════════
 * Görevleri:
 *
 *  1. [C→R] RequestNetworkSettingsPacket:
 *     a) Client'ın protocolVersion'ına göre doğru codec'i bul
 *     b) Codec'i session'a set et (client tarafı)
 *        NOT: server tarafı henüz bağlı değil — sadece activeCodec güncellenir
 *             Server bağlanınca OxRelaySession.connectToServer() codec'i set eder
 *     c) EncodingSettings → Int.MAX_VALUE (parse hatalarını önler)
 *     d) Client'a NetworkSettingsPacket gönder (ZLIB, threshold=1)
 *     e) clientSession.setCompression(ZLIB)
 *     f) return false → server'a İLETME (LoginPacketListener connectToServer sonrası gönderecek)
 *
 *  2. Relay pong güncelle → LAN listesinde doğru sürüm
 *
 * Priority = -10 → LoginPacketListener'dan (0) önce çalışır.
 * ═══════════════════════════════════════════════════════════════════
 */
class AutoCodecListener(private val relay: OxRelay? = null) : OxPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"

        // En yeni → en eski sırasıyla — ilk bulunan kullanılır
        private val CODEC_CLASS_NAMES = listOf(
            "v975.Bedrock_v975",   // MC 26.20
            "v948.Bedrock_v948",   // MC 26.10 release
            "v935.Bedrock_v935",   // MC 26.10 preview
            "v924.Bedrock_v924",   // MC 26.0
            "v818.Bedrock_v818",   // MC 1.21.93 edu
            "v800.Bedrock_v800",
            "v786.Bedrock_v786",   // MC 1.21.80
            "v748.Bedrock_v748",   // MC 1.21.60
            "v729.Bedrock_v729",   // MC 1.21.50
            "v712.Bedrock_v712",   // MC 1.21.40
            "v686.Bedrock_v686",   // MC 1.21.30
            "v671.Bedrock_v671",   // MC 1.21.20
            "v662.Bedrock_v662",
            "v649.Bedrock_v649",
            "v630.Bedrock_v630",
            "v618.Bedrock_v618",
            "v594.Bedrock_v594",
            "v589.Bedrock_v589",
            "v582.Bedrock_v582",
            "v575.Bedrock_v575",
        )

        private val BASE = "org.cloudburstmc.protocol.bedrock.codec."

        // protocol → codec cache (thread-safe lazy map)
        private val codecCache = HashMap<Int, BedrockCodec?>()

        @Synchronized
        fun findCodec(protocol: Int): BedrockCodec? {
            if (codecCache.containsKey(protocol)) return codecCache[protocol]
            for (name in CODEC_CLASS_NAMES) {
                try {
                    val codec = Class.forName("$BASE$name")
                        .getField("CODEC")
                        .get(null) as? BedrockCodec ?: continue
                    if (codec.protocolVersion == protocol) {
                        Log.i(TAG, "Codec eşleşti: $name (protocol=$protocol, mc=${codec.minecraftVersion})")
                        codecCache[protocol] = codec
                        return codec
                    }
                } catch (_: Exception) {}
            }
            Log.w(TAG, "Codec bulunamadı: protocol=$protocol — RELAY_CODEC kullanılacak")
            codecCache[protocol] = null
            return null
        }

        private val UNLIMITED_ENCODING: EncodingSettings = EncodingSettings.builder()
            .maxListSize(Int.MAX_VALUE)
            .maxByteArraySize(Int.MAX_VALUE)
            .maxNetworkNBTSize(Int.MAX_VALUE)
            .maxItemNBTSize(Int.MAX_VALUE)
            .maxStringLength(Int.MAX_VALUE)
            .build()
    }

    override val priority: Int = -10  // LoginPacketListener'dan (0) önce çalış

    @Volatile private var processed = false

    override fun onSessionStart(session: OxRelaySession) {
        processed = false
    }

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {
            is RequestNetworkSettingsPacket -> {
                if (processed) {
                    Log.w(TAG, "RequestNetworkSettings tekrar geldi — atlanıyor")
                    return false
                }
                processed = true

                val protocol = packet.protocolVersion
                Log.i(TAG, "RequestNetworkSettings — protocol=$protocol")

                // 1. Codec seç
                val codec = findCodec(protocol) ?: OxRelay.RELAY_CODEC.also {
                    Log.w(TAG, "Bilinmeyen protocol=$protocol — RELAY_CODEC fallback: ${it.protocolVersion}")
                }

                // 2. Client codec'ini set et
                //    Server henüz bağlı değil — sadece activeCodec'i güncelle
                //    Server bağlanınca connectToServer() içindeki initSession codec'i set eder
                try {
                    session.clientSession.codec = codec
                } catch (e: Exception) {
                    Log.w(TAG, "Client codec set hatası: ${e.message}")
                }
                session.activeCodec = codec

                // 3. EncodingSettings — sınırsız
                try {
                    session.clientSession.peer.codecHelper.encodingSettings = UNLIMITED_ENCODING
                } catch (e: Exception) {
                    Log.w(TAG, "EncodingSettings set hatası: ${e.message}")
                }

                Log.i(TAG, "Codec seçildi: protocol=${codec.protocolVersion} mc=${codec.minecraftVersion}")

                // 4. Client'a NetworkSettingsPacket gönder
                val netSettings = NetworkSettingsPacket().apply {
                    compressionThreshold = 1
                    compressionAlgorithm = PacketCompressionAlgorithm.ZLIB
                }
                session.sendToClient(netSettings)

                // 5. Client-side ZLIB compression aç
                try {
                    session.clientSession.setCompression(PacketCompressionAlgorithm.ZLIB)
                    Log.i(TAG, "Client ZLIB compression açıldı ✓")
                } catch (e: Exception) {
                    Log.e(TAG, "Client compression hatası: ${e.message}", e)
                }

                // 6. Relay pong güncelle (LAN listesinde doğru sürüm)
                relay?.updatePong(codec.protocolVersion, codec.minecraftVersion ?: mcVersionFor(protocol))

                // false → server'a İLETME
                // LoginPacketListener, server bağlandıktan sonra ayrıca RequestNetworkSettings gönderecek
                return false
            }
        }
        return true
    }

    private fun mcVersionFor(protocol: Int) = when (protocol) {
        975  -> "26.20"
        948  -> "26.10"
        935  -> "26.10"
        924  -> "26.0"
        818  -> "1.21.93"
        800  -> "1.21.90"
        786  -> "1.21.80"
        748  -> "1.21.60"
        729  -> "1.21.50"
        712  -> "1.21.40"
        686  -> "1.21.30"
        671  -> "1.21.20"
        662  -> "1.21.10"
        else -> protocol.toString()
    }
}
