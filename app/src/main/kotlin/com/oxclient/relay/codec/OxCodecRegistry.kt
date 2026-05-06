package com.oxclient.relay.codec

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec

/**
 * OxCodecRegistry — bedrock-connection'ın dahili registry'sini kullanır.
 * Versiyona özel import'lar (Bedrock_v766 vb.) gerektirmez.
 */
object OxCodecRegistry {

    val latestCodec: BedrockCodec
        get() = BedrockCodec.REGISTRY.latestCodec
            ?: error("Codec yok — bedrock-connection bağımlılığını kontrol edin")

    fun getClosestCodec(protocolVersion: Int): BedrockCodec =
        BedrockCodec.REGISTRY.getCodec(protocolVersion)
            ?: BedrockCodec.REGISTRY.latestCodec
            ?: error("Codec bulunamadı: protocol=$protocolVersion")
}
