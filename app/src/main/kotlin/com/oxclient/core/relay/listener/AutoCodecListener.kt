package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.relay.OxRelay
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket

/**
 * AutoCodecListener — gelen RequestNetworkSettingsPacket veya LoginPacket'ten
 * client'ın protokol versiyonunu okuyarak hem client session hem server session'ı
 * doğru codec ile yapılandırır.
 *
 * Ayrıca relay'in pong paketini client versiyonuyla günceller (updatePong),
 * böylece MC server listesinde sürüm uyuşmazlığı göstermez.
 *
 * Sıra: LoginPacketListener'dan ÖNCE çalışmalı (priority = -10).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * Codec Listesi (v2 — 26.x serisi eklendi):
 *   v975 (26.20), v948 (26.10 release), v935 (26.10 preview), v924 (26.0),
 *   v818 (1.21.93 edu), v786 (1.21.80), v748 (1.21.60), v729 (1.21.50),
 *   v712, v686, v671, v662, v649, v630, v618 …
 * ─────────────────────────────────────────────────────────────────────────
 */
class AutoCodecListener(private val relay: OxRelay? = null) : OxPacketListener {

    companion object {
        private const val TAG = "AutoCodecListener"

        /**
         * Protokol versiyonuna göre codec arar.
         * Kütüphanede bulunmayan versiyonlar sessizce atlanır.
         */
        private val KNOWN_VERSIONS = listOf(
            // MC 26.x (2025-2026)
            "v975.Bedrock_v975",   // 26.20
            "v948.Bedrock_v948",   // 26.10 release
            "v935.Bedrock_v935",   // 26.10 preview  ← senin sürümün
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
            "v567.Bedrock_v567",
            "v560.Bedrock_v560",
        )

        /** Codec cache: protocolVersion → BedrockCodec (null = bulunamadı) */
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
    }

    override val priority: Int = -10

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {
            is RequestNetworkSettingsPacket -> {
                Log.d(TAG, "RequestNetworkSettings protokol: ${packet.protocolVersion}")
                applyCodec(session, packet.protocolVersion)
            }
            is LoginPacket -> {
                Log.d(TAG, "Login protokol: ${packet.protocolVersion}")
                applyCodec(session, packet.protocolVersion)
            }
        }
        return true
    }

    private fun applyCodec(session: OxRelaySession, protocolVersion: Int) {
        val codec = findCodec(protocolVersion)

        if (codec != null) {
            session.activeCodec = codec
            session.clientSession.setCodec(codec)
            session.serverSession?.setCodec(codec)
            Log.i(TAG, "Codec ayarlandı: protocol=$protocolVersion → MC ${codec.minecraftVersion}")

            // Relay pong'unu güncelle — MC'nin server listesinde "Sürüm uyumsuz" göstermemesi için
            relay?.updatePong(codec.protocolVersion, codec.minecraftVersion ?: protocolVersion.toString())

        } else {
            // Kütüphanede codec yok ama yine de bağlantıya izin ver;
            // mevcut codec üzerinden iletim yapılır (passthrough modu)
            Log.w(TAG, "protocol=$protocolVersion için codec yok — mevcut codec korunuyor (${session.activeCodec.protocolVersion})")

            // Pong'u en azından doğru protokol numarasıyla güncelle
            val mcVer = knownVersionString(protocolVersion)
            relay?.updatePong(protocolVersion, mcVer)
        }
    }

    /** Protokol numarasından yaklaşık MC sürüm string'i tahmin eder (fallback için). */
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
