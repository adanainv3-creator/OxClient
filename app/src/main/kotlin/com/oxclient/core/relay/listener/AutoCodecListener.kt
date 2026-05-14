package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket

/**
 * AutoCodecListener — gelen LoginPacket veya RequestNetworkSettingsPacket'ten
 * client'ın protokol versiyonunu okuyarak hem client session'ını hem de
 * server session'ını doğru codec ile yapılandırır.
 *
 * Sıra: LoginPacketListener'dan ÖNCE çalışmalı (priority = -10).
 */
class AutoCodecListener : OxPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"
    }

    override val priority: Int = -10

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {
            is RequestNetworkSettingsPacket -> {
                val protocolVersion = packet.protocolVersion
                Log.d(TAG, "RequestNetworkSettings protokol versiyonu: $protocolVersion")
                applyCodec(session, protocolVersion)
            }
            is LoginPacket -> {
                val protocolVersion = packet.protocolVersion
                Log.d(TAG, "Login protokol versiyonu: $protocolVersion")
                applyCodec(session, protocolVersion)
            }
        }
        return true // Devam et — asla engelleme
    }

    private fun applyCodec(session: OxRelaySession, protocolVersion: Int) {
        val codec = findCodec(protocolVersion)
        if (codec != null) {
            session.activeCodec = codec
            session.clientSession.setCodec(codec)
            session.serverSession?.setCodec(codec)
            Log.i(TAG, "Codec ayarlandı: v$protocolVersion → ${codec.minecraftVersion}")
        } else {
            Log.w(TAG, "Codec bulunamadı: v$protocolVersion, mevcut codec korunuyor")
        }
    }

    /**
     * Protokol versiyonuna göre kayıtlı codec'i arar.
     * CloudburstMC Protocol 3.x — BedrockCodec'in statik codec listesine erişir.
     */
    private fun findCodec(protocolVersion: Int): BedrockCodec? {
        // Önce reflection ile CloudburstMC'nin codec kaydını tara
        val knownVersions = listOf(
            "v786.Bedrock_v786", "v748.Bedrock_v748",
            "v729.Bedrock_v729", "v712.Bedrock_v712",
            "v686.Bedrock_v686", "v671.Bedrock_v671",
            "v662.Bedrock_v662", "v649.Bedrock_v649",
            "v630.Bedrock_v630", "v618.Bedrock_v618",
            "v594.Bedrock_v594", "v589.Bedrock_v589",
            "v582.Bedrock_v582", "v575.Bedrock_v575",
            "v567.Bedrock_v567", "v560.Bedrock_v560",
            "v557.Bedrock_v557", "v554.Bedrock_v554",
            "v545.Bedrock_v545", "v534.Bedrock_v534",
            "v527.Bedrock_v527", "v503.Bedrock_v503",
            "v486.Bedrock_v486", "v475.Bedrock_v475",
            "v471.Bedrock_v471", "v465.Bedrock_v465",
            "v448.Bedrock_v448", "v440.Bedrock_v440",
            "v431.Bedrock_v431", "v428.Bedrock_v428",
            "v422.Bedrock_v422", "v419.Bedrock_v419",
            "v416.Bedrock_v416", "v413.Bedrock_v413",
            "v407.Bedrock_v407", "v388.Bedrock_v388",
            "v361.Bedrock_v361", "v354.Bedrock_v354",
            "v340.Bedrock_v340"
        )

        for (versionClass in knownVersions) {
            try {
                val clazz = Class.forName(
                    "org.cloudburstmc.protocol.bedrock.codec.$versionClass"
                )
                val codec = clazz.getField("CODEC").get(null) as? BedrockCodec ?: continue
                if (codec.protocolVersion == protocolVersion) return codec
            } catch (_: Exception) { /* bu versiyon yok, devam et */ }
        }
        return null
    }
}
