package com.oxclient.core.relay

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v786.Bedrock_v786

/**
 * OxCodecRegistry — Desteklenen Bedrock codec'lerini tutar.
 *
 * CloudburstMC Protocol kütüphanesindeki en güncel codec otomatik
 * alınır. Yeni bir Bedrock sürümü çıktığında sadece bu dosyayı
 * güncellemek yeterli.
 */
object OxCodecRegistry {

    /**
     * Desteklenen codec'ler (eski → yeni sırayla).
     * Gerekirse buraya ek codec'ler eklenebilir.
     */
    private val codecs: List<BedrockCodec> = listOf(
        Bedrock_v786.CODEC   // 1.21.60 — güncel
    )

    /** En yeni codec */
    val latestCodec: BedrockCodec
        get() = codecs.last()

    /**
     * Verilen protokol versiyonuna en yakın codec'i döner.
     * Tam eşleşme yoksa en yakın düşük versiyonu seçer.
     */
    fun getClosestCodec(protocolVersion: Int): BedrockCodec {
        return codecs
            .filter { it.protocolVersion <= protocolVersion }
            .maxByOrNull { it.protocolVersion }
            ?: codecs.first()
    }

    /** Protokol versiyonuna tam eşleşme; yoksa null */
    fun getExactCodec(protocolVersion: Int): BedrockCodec? =
        codecs.firstOrNull { it.protocolVersion == protocolVersion }
}
