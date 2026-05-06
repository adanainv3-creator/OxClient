package com.oxclient.relay.listener

import com.oxclient.relay.codec.OxCodecRegistry
import com.oxclient.relay.session.OxRelaySession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*
import timber.log.Timber

/**
 * OxAutoCodecListener — WClient AutoCodecPacketListener'ın OxClient karşılığı.
 *
 * 1. RequestNetworkSettingsPacket → codec seç, istemciye NetworkSettings gönder, compression aç.
 * 2. Sunucu NetworkSettings gönderirse → server tarafında compression aç.
 *
 * NOT: bedrock-connection 3.0.0.Beta1 API'sinde EncodingSettings ve v729 serializer sınıfları
 * kaldırıldı / yeniden adlandırıldı. Bu sınıflar reflection ile opsiyonel olarak kullanılır;
 * mevcut değilse sessizce atlanır.
 */
class OxAutoCodecListener(
    private val session   : OxRelaySession,
    private val patchCodec: Boolean = true
) : OxPacketListener {

    // ── İstemciden gelen paketler ─────────────────────────────────────────────

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (packet !is RequestNetworkSettingsPacket) return false

        try {
            val proto = packet.protocolVersion
            val codec = patchIfNeeded(OxCodecRegistry.getClosestCodec(proto))

            Timber.i("[AutoCodec] Client proto=$proto → codec=${codec.minecraftVersion}")

            session.serverSide.codec = codec
            applyEncodingSettings(session.serverSide.peer.codecHelper)

            // İstemciye NetworkSettings gönder (compression başlatır)
            val nsPacket = NetworkSettingsPacket().apply {
                compressionThreshold = 1
                compressionAlgorithm = PacketCompressionAlgorithm.ZLIB
            }
            session.clientBoundImmediate(nsPacket)
            session.serverSide.setCompression(PacketCompressionAlgorithm.ZLIB)
            Timber.i("[AutoCodec] ✓ Compression ZLIB aktif (client)")

        } catch (e: Exception) {
            Timber.e(e, "[AutoCodec] RequestNetworkSettings işleme hatası")
            session.serverSide.disconnect("Network settings hatası: ${e.message}")
        }

        return true  // İstemciye bu paketi iletme, biz yanıtladık
    }

    // ── Sunucudan gelen paketler ──────────────────────────────────────────────

    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet !is NetworkSettingsPacket) return false

        val algo = packet.compressionAlgorithm ?: PacketCompressionAlgorithm.ZLIB
        Timber.i("[AutoCodec] Sunucu NetworkSettings: algo=$algo threshold=${packet.compressionThreshold}")

        runCatching { session.clientSide?.setCompression(algo) }
        return false  // İstemciye ilet
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /**
     * EncodingSettings — bedrock-connection 3.0.0.Beta1'de API değişti.
     * Reflection ile güvenli şekilde set etmeye çalışırız; başarısız olursa sessizce geçeriz.
     */
    private fun applyEncodingSettings(helper: Any?) {
        if (helper == null) return
        try {
            // EncodingSettings.builder() pattern — sınıf varsa kullan
            val esClass    = Class.forName("org.cloudburstmc.protocol.bedrock.data.EncodingSettings")
            val builderMth = esClass.getMethod("builder")
            var builder    = builderMth.invoke(null)

            val intMax = Int.MAX_VALUE
            for (methodName in listOf("maxListSize", "maxByteArraySize", "maxNetworkNBTSize", "maxItemNBTSize", "maxStringLength")) {
                runCatching {
                    val m = builder!!.javaClass.getMethod(methodName, Int::class.java)
                    builder = m.invoke(builder, intMax)
                }
            }
            val buildMth    = builder!!.javaClass.getMethod("build")
            val settings    = buildMth.invoke(builder)
            val setterField = helper.javaClass.getMethod("setEncodingSettings", esClass)
            setterField.invoke(helper, settings)
            Timber.d("[AutoCodec] EncodingSettings uygulandı")
        } catch (_: Throwable) {
            Timber.d("[AutoCodec] EncodingSettings bu API versiyonunda mevcut değil — atlanıyor")
        }
    }

    /**
     * v729 serializer patch — InventoryContent ve InventorySlot paketlerini eski serializer ile
     * override eder. Sınıflar mevcut değilse patch uygulanmaz.
     */
    private fun patchIfNeeded(codec: BedrockCodec): BedrockCodec {
        if (!patchCodec || codec.protocolVersion <= 729) return codec

        return try {
            val icSerClass  = Class.forName("org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729")
            val isSerClass  = Class.forName("org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729")
            val icInstance  = icSerClass.getField("INSTANCE").get(null)
            val isInstance  = isSerClass.getField("INSTANCE").get(null)

            val builder = codec.toBuilder()

            // updateSerializer(Class, PacketSerializer) — reflection ile çağır
            val icPacketClass = Class.forName("org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket")
            val isPacketClass = Class.forName("org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket")
            val serializerInterface = Class.forName("org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer")

            val updateMethod = builder.javaClass.getMethod("updateSerializer", Class::class.java, serializerInterface)
            updateMethod.invoke(builder, icPacketClass, icInstance)
            updateMethod.invoke(builder, isPacketClass, isInstance)

            builder.build().also {
                Timber.d("[AutoCodec] v729 serializer patch uygulandı")
            }
        } catch (e: Exception) {
            Timber.w("[AutoCodec] v729 serializer patch başarısız (${e.javaClass.simpleName}: ${e.message}) — orijinal codec kullanılıyor")
            codec
        }
    }
}
