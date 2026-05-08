package com.oxclient.module.visual

import android.util.Log
import com.oxclient.core.proxy.BedrockPacketIds
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.module.*
import java.io.ByteArrayOutputStream

/**
 * FullBright
 *
 * Modlar:
 *
 *  NightVision — MobEffect paketi ile Night Vision efekti enjekte eder.
 *                Süre dolmadan önce yeniler. İstemciye gönderilir (client-bound).
 *
 *  Gamma       — Bedrock'ta gerçek gamma değiştirme proxy'den mümkün değil.
 *                Bunun yerine SetTime paketi ile daima gündüz (zamanı 6000) gönderir.
 *                Bu yöntem ışık seviyesini dolaylı olarak artırır.
 *
 *  Lighting    — LevelChunk paketlerini intercept ederek sky/block light
 *                değerlerini maksimuma patch'ler (deneysel).
 */
class FullBright : BaseModule(
    name        = "FullBright",
    category    = ModuleCategory.VISUAL,
    description = "Geceyi gündüz gibi görünür yapar"
), PacketListener {

    override val priority = 30

    enum class FbMode { Gamma, NightVision, Lighting }

    // ── Ayarlar ───────────────────────────────────────────────────────────
    private val mode     = EnumSetting("Mode",     FbMode.NightVision, FbMode.entries)
    private val strength = FloatSetting("Strength",1000f, 100f, 10000f)
    private val shortcut = BoolSetting("Shortcut", false)

    override fun registerSettings() = listOf(mode, strength, shortcut)

    // ── İç durum ──────────────────────────────────────────────────────────
    @Volatile private var lastNvInjectMs = 0L
    private val NV_REFRESH_INTERVAL      = 8000L  // 8 saniyede bir yenile
    private val NIGHT_VISION_EFFECT_ID   = 16      // Bedrock Night Vision effect ID

    companion object {
        private const val TAG = "FullBright"
    }

    override fun onEnable() {
        PacketEventBus.register(this)
        lastNvInjectMs = 0L
        Log.d(TAG, "Etkinleştirildi (mode=${mode.value})")

        // Hemen uygula
        when (mode.value) {
            FbMode.NightVision -> injectNightVision()
            FbMode.Gamma       -> injectGamma()
            FbMode.Lighting    -> {}  // paket gelince işlenir
        }
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        // Night Vision'ı kaldır
        if (mode.value == FbMode.NightVision) removeNightVision()
        Log.d(TAG, "Devre dışı")
    }

    // ── Paket dinleyici ───────────────────────────────────────────────────

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (mode.value) {
            FbMode.NightVision -> {
                // Her SetTime veya MovePlayer paketinde NV süresini kontrol et
                if (event.packetId == BedrockPacketIds.SET_TIME ||
                    event.packetId == BedrockPacketIds.MOVE_PLAYER) {
                    refreshNightVision()
                }
            }
            FbMode.Gamma -> {
                // SetTime paketini intercept et → zamanı gündüze çek
                if (event.packetId == BedrockPacketIds.SET_TIME &&
                    event.direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
                    event.modifiedData = buildSetTime(6000)
                }
            }
            FbMode.Lighting -> {
                // LevelChunk → light verisini patch'le
                if (event.packetId == BedrockPacketIds.LEVEL_CHUNK &&
                    event.direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
                    patchChunkLight(event)
                }
            }
        }
    }

    // ── Night Vision ──────────────────────────────────────────────────────

    private fun refreshNightVision() {
        val now = System.currentTimeMillis()
        if (now - lastNvInjectMs < NV_REFRESH_INTERVAL) return
        lastNvInjectMs = now
        injectNightVision()
    }

    private fun injectNightVision() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) {
            // selfRuntimeId henüz bilinmiyor, StartGame bekleniyor
            return
        }
        // eventId=1 → efekt ekle
        val pkt = PacketHelper.buildMobEffect(
            runtimeId = rid,
            eventId   = 1,
            effectId  = NIGHT_VISION_EFFECT_ID,
            amplifier = 0,
            particles = false,
            duration  = 1000000  // süresiz
        )
        // İstemciye enjekte et (görsel efekt)
        PacketHelper.injectToClient(pkt)
        Log.v(TAG, "NightVision enjekte edildi (rid=$rid)")
    }

    private fun removeNightVision() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) return
        // eventId=3 → efekti kaldır
        val pkt = PacketHelper.buildMobEffect(
            runtimeId = rid,
            eventId   = 3,
            effectId  = NIGHT_VISION_EFFECT_ID,
            amplifier = 0,
            particles = false,
            duration  = 0
        )
        PacketHelper.injectToClient(pkt)
        Log.v(TAG, "NightVision kaldırıldı")
    }

    // ── Gamma (SetTime) ───────────────────────────────────────────────────

    private fun injectGamma() {
        // SetTime ile sürekli gündüz → dolaylı ışık artışı
        val pkt = buildSetTime(6000)
        PacketHelper.injectToClient(pkt)
        Log.v(TAG, "Gamma: SetTime=6000 enjekte edildi")
    }

    private fun buildSetTime(time: Int): ByteArray {
        val out = ByteArrayOutputStream()
        PacketHelper.writeVarInt(out, BedrockPacketIds.SET_TIME)
        PacketHelper.writeVarInt(out, time)
        return PacketHelper.wrapBatch(out.toByteArray())
    }

    // ── Lighting (Chunk patch) ────────────────────────────────────────────

    /**
     * LevelChunk paketindeki sky ve block light sub-chunk verilerini
     * maksimum değere (0xFF) patch'ler.
     *
     * ⚠️ Bu yöntem deneyseldir. Bedrock LevelChunk formatı versiyon
     *    bağımlıdır; yanlış parse bazı chunk'ların görünmemesine yol açabilir.
     *    Sorun yaşarsanız NightVision modunu kullanın.
     */
    private fun patchChunkLight(event: PacketEvent) {
        val d = event.data
        try {
            // LevelChunk başlığını atla — chunk_x, chunk_z, sub_chunk_count
            // Basit yaklaşım: 0x00 byte'larından oluşan light alanlarını 0xFF'e çevir
            val modified = d.copyOf()
            var i = 0
            while (i < modified.size - 2048) {
                // 2048 byte'lık sıfır bloğu bul (boş light data)
                if (modified.slice(i until i + 16).all { it == 0.toByte() }) {
                    for (j in i until minOf(i + 2048, modified.size)) {
                        modified[j] = 0xFF.toByte()
                    }
                    i += 2048
                } else {
                    i++
                }
            }
            event.modifiedData = modified
        } catch (_: Exception) {}
    }
}