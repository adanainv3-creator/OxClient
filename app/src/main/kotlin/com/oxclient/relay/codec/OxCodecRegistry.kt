package com.oxclient.relay.codec

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v748.Bedrock_v748
import org.cloudburstmc.protocol.bedrock.codec.v766.Bedrock_v766
import timber.log.Timber

/**
 * OxCodecRegistry — desteklenen codec'leri elle kaydeder.
 * bedrock-connection 3.0.0.Beta1'de BedrockCodec.REGISTRY static field'i kaldırıldı;
 * bunun yerine bilinen versiyonları elle listeleyip yönetiyoruz.
 */
object OxCodecRegistry {

    /**
     * Desteklenen codec listesi — en yeniden en eskiye sıralı.
     * Yeni Bedrock versiyonu eklenince buraya ekle.
     */
    private val codecs: List<BedrockCodec> by lazy {
        buildList {
            runCatching { add(Bedrock_v766.CODEC) }
            runCatching { add(Bedrock_v748.CODEC) }
        }.also {
            if (it.isEmpty()) error("Hiç codec yüklenemedi — bedrock-codec bağımlılığını kontrol edin")
            Timber.d("[OxCodecRegistry] ${it.size} codec yüklendi, en yeni: ${it.first().minecraftVersion}")
        }
    }

    val latestCodec: BedrockCodec
        get() = codecs.first()

    /**
     * Verilen protocolVersion'a en yakın codec'i döndürür.
     * Tam eşleşme varsa onu, yoksa en yakın küçük versiyonu, o da yoksa en yeniyi döner.
     */
    fun getClosestCodec(protocolVersion: Int): BedrockCodec {
        return codecs.firstOrNull { it.protocolVersion == protocolVersion }
            ?: codecs.firstOrNull { it.protocolVersion <= protocolVersion }
            ?: codecs.first()
    }
}
