package com.oxclient.relay.listener

import android.util.Log
import com.oxclient.relay.OxRelaySession
import com.oxclient.relay.RelayPacketListener
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket

/**
 * AutoCodecPacketListener
 *
 * - [RequestNetworkSettingsPacket] gelince protocol versiyonunu algıla
 * - CloudburstMC codec registry'den doğru codec'i seç
 * - [NetworkSettingsPacket] ile ZLIB compression'ı etkinleştir
 */
class AutoCodecPacketListener(
    private val session: OxRelaySession
) : RelayPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"
    }

    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet !is RequestNetworkSettingsPacket) return false

        val protocolVersion = packet.protocolVersion
        session.protocolVersion = protocolVersion
        Log.i(TAG, "Protocol version algılandı: $protocolVersion")

        // Doğru codec'i seç
        val codec = findCodec(protocolVersion)
        if (codec != null) {
            session.serverSession.codec = codec
            Log.i(TAG, "Codec ayarlandı: ${codec.minecraftVersion}")
        } else {
            Log.w(TAG, "Codec bulunamadı! Desteklenmeyen protocol: $protocolVersion")
        }

        // NetworkSettings paketi oluştur — ZLIB compression aç
        val networkSettings = NetworkSettingsPacket().apply {
            compressionThreshold  = 1
            compressionAlgorithm  = PacketCompressionAlgorithm.ZLIB
            isClientThrottleEnabled = false
            clientThrottleThreshold = 0
            clientThrottleScalar    = 0f
        }

        // Client'a hemen gönder
        session.serverSession.sendPacketImmediately(networkSettings)
        session.serverSession.setCompression(PacketCompressionAlgorithm.ZLIB)

        Log.d(TAG, "NetworkSettings gönderildi, ZLIB aktif")
        return true // paketi gerçek sunucuya iletme, biz hallettik
    }

    private fun findCodec(protocolVersion: Int): BedrockCodec? {
        return try {
            // BedrockCodec.CODEC_LIST tüm kayıtlı codec'leri içerir
            val field = BedrockCodec::class.java.getDeclaredField("CODEC_LIST")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val list = field.get(null) as? List<BedrockCodec>
            list?.find { it.protocolVersion == protocolVersion }
                ?: list?.maxByOrNull { it.protocolVersion } // fallback: en yeni
        } catch (e: Exception) {
            Log.w(TAG, "Codec listesi alınamadı: ${e.message}")
            null
        }
    }
}
