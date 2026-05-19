package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.relay.OxRelay
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*

/**
 * AutoCodecListener
 *
 * Görevleri (sırasıyla):
 *  1. RequestNetworkSettingsPacket gelince:
 *     - Client'ın protokol versiyonunu tespit et
 *     - Doğru codec'i seç ve her iki tarafta ayarla
 *     - EncodingSettings'i genişlet (paket parse hatalarını önler)
 *     - Client'a NetworkSettingsPacket gönder + ZLIB compression aç
 *       (WRelay ile aynı akış — bu olmadan sunucu timeout'a düşer)
 *  2. LoginPacket gelince codec'i teyit et (nadiren farklı olur)
 *  3. Relay pong'unu güncelle → LAN listesinde doğru sürüm
 *
 * Priority = -10 → LoginPacketListener'dan önce çalışır.
 */
class AutoCodecListener(private val relay: OxRelay? = null) : OxPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"

        private val KNOWN_VERSIONS = listOf(
            // MC 26.x (2025-2026)
            "v975.Bedrock_v975",   // 26.20
            "v948.Bedrock_v948",   // 26.10 release
            "v935.Bedrock_v935",   // 26.10 preview
            "v924.Bedrock_v924",   // 26.0
            // MC 1.21.x
            "v818.Bedrock_v818",   // 1.21.93 edu
            "v800.Bedrock_v800",
            "v786.Bedrock_v786",   // 1.21.80
            "v748.Bedrock_v748",   // 1.21.60
            "v729.Bedrock_v729",   // 1.21.50
            "v712.Bedrock_v712",
            "v686.Bedrock_v686",
            "v671.Bedrock_v671",
            "v662.Bedrock_v662",
            "v649.Bedrock_v649",
            "v630.Bedrock_v630",
            "v618.Bedrock_v618",
            "v594.Bedrock_v594",
            "v589.Bedrock_v589",
            "v582.Bedrock_v582",
            "v575.Bedrock_v575",
        )

        private val codecCache = mutableMapOf<Int, BedrockCodec?>()

        fun findCodec(protocolVersion: Int): BedrockCodec? {
            return codecCache.getOrPut(protocolVersion) {
                for (versionClass in KNOWN_VERSIONS) {
                    try {
                        val clazz = Class.forName(
                            "org.cloudburstmc.protocol.bedrock.codec.$versionClass"
                        )
                        val codec = clazz.getField("CODEC").get(null) as? BedrockCodec
                            ?: continue
                        if (codec.protocolVersion == protocolVersion) {
                            Log.d(TAG, "Codec bulundu: $versionClass")
                            return@getOrPut codec
                        }
                    } catch (_: Exception) {}
                }
                Log.w(TAG, "Codec bulunamadı: protocol=$protocolVersion")
                null
            }
        }

        /**
         * Inventory serializer patch — v729 sonrası sürümlerde
         * inventory paketleri eski serializer ile gönderilmeli.
         */
        private fun patchCodec(codec: BedrockCodec): BedrockCodec {
            return if (codec.protocolVersion > 729) {
                codec.toBuilder()
                    .updateSerializer(
                        InventoryContentPacket::class.java,
                        InventoryContentSerializer_v729.INSTANCE
                    )
                    .updateSerializer(
                        InventorySlotPacket::class.java,
                        InventorySlotSerializer_v729.INSTANCE
                    )
                    .build()
            } else {
                codec
            }
        }
    }

    override val priority: Int = -10

    // RequestNetworkSettings için tek seferlik işlem bayrağı
    @Volatile private var networkSettingsSent = false

    override fun onSessionStart(session: OxRelaySession) {
        networkSettingsSent = false
    }

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {
            is RequestNetworkSettingsPacket -> {
                Log.d(TAG, "RequestNetworkSettings protokol: ${packet.protocolVersion}")

                if (networkSettingsSent) {
                    Log.w(TAG, "NetworkSettings zaten gönderildi — atlanıyor")
                    return true
                }
                networkSettingsSent = true

                try {
                    val protocolVersion = packet.protocolVersion
                    val rawCodec = findCodec(protocolVersion) ?: run {
                        Log.w(TAG, "Codec yok (protocol=$protocolVersion) — fallback codec kullanılıyor")
                        OxRelay.RELAY_CODEC
                    }
                    val codec = patchCodec(rawCodec)

                    // ── Her iki tarafta codec ayarla ───────────────────────
                    session.clientSession.setCodec(codec)
                    session.serverSession?.setCodec(codec)
                    session.activeCodec = codec

                    // ── EncodingSettings genişlet (parse hatalarını önler) ─
                    val encodingSettings = EncodingSettings.builder()
                        .maxListSize(Int.MAX_VALUE)
                        .maxByteArraySize(Int.MAX_VALUE)
                        .maxNetworkNBTSize(Int.MAX_VALUE)
                        .maxItemNBTSize(Int.MAX_VALUE)
                        .maxStringLength(Int.MAX_VALUE)
                        .build()
                    session.clientSession.peer.codecHelper.encodingSettings = encodingSettings
                    session.serverSession?.peer?.codecHelper?.encodingSettings = encodingSettings

                    Log.i(TAG, "Codec ayarlandı: protocol=$protocolVersion → MC ${codec.minecraftVersion}")

                    // ── Client'a NetworkSettingsPacket gönder ─────────────
                    // WRelay akışıyla birebir aynı: relay client'a compression'ı
                    // burada açar, server tarafındaki compression ise
                    // server'dan gelen NetworkSettingsPacket'te açılır
                    // (LoginPacketListener.onServerPacket → NetworkSettingsPacket).
                    val networkSettings = NetworkSettingsPacket().apply {
                        compressionThreshold  = 1
                        compressionAlgorithm  = PacketCompressionAlgorithm.ZLIB
                    }
                    session.sendToClient(networkSettings)
                    session.clientSession.setCompression(PacketCompressionAlgorithm.ZLIB)
                    Log.i(TAG, "NetworkSettings gönderildi → ZLIB compression aktif (client)")

                    // ── Relay pong güncelle ───────────────────────────────
                    relay?.updatePong(
                        codec.protocolVersion,
                        codec.minecraftVersion ?: knownVersionString(protocolVersion)
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "NetworkSettings işleme hatası: ${e.message}", e)
                    session.disconnect("NetworkSettings başarısız: ${e.message}")
                }

                // true → bu paketi server'a iletme (relay halletti)
                return true
            }

            is LoginPacket -> {
                // Codec zaten RequestNetworkSettings'te ayarlandı.
                // Sadece pong senkronizasyonu için tekrar kontrol et.
                Log.d(TAG, "Login protokol: ${packet.protocolVersion}")
                val codec = findCodec(packet.protocolVersion)
                if (codec != null && codec.protocolVersion != session.activeCodec.protocolVersion) {
                    session.activeCodec = codec
                    session.clientSession.setCodec(codec)
                    session.serverSession?.setCodec(codec)
                    relay?.updatePong(codec.protocolVersion, codec.minecraftVersion ?: "")
                    Log.i(TAG, "Login'de codec güncellendi: ${codec.protocolVersion}")
                }
            }
        }
        return true
    }

    private fun knownVersionString(protocol: Int) = when (protocol) {
        975  -> "26.20"
        948  -> "26.10"
        935  -> "26.10"
        924  -> "26.0"
        818  -> "1.21.93"
        786  -> "1.21.80"
        748  -> "1.21.60"
        729  -> "1.21.50"
        712  -> "1.21.40"
        686  -> "1.21.30"
        671  -> "1.21.20"
        else -> protocol.toString()
    }
}
