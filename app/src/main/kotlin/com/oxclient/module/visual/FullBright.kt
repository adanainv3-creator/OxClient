package com.oxclient.module.visual

import android.util.Log
import com.oxclient.core.proxy.BedrockPacketIds
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.InjectionQueue
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.module.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class FullBright : BaseModule(
    name        = "FullBright",
    category    = ModuleCategory.VISUAL,
    description = "Geceyi gündüz gibi görünür yapar"
), PacketListener {

    override val priority = 30

    enum class FbMode { Gamma, NightVision, Lighting }

    private val mode     = EnumSetting("Mode",     FbMode.NightVision, FbMode.entries)
    private val strength = FloatSetting("Strength", 1000f, 100f, 10000f)
    private val shortcut = BoolSetting("Shortcut",  false)

    override fun registerSettings() = listOf(mode, strength, shortcut)

    @Volatile private var lastNvInjectMs = 0L
    private val NV_REFRESH_MS   = 8000L
    private val NIGHT_VISION_ID = 16

    // Tek bir SupervisorJob — scope ömür boyu yaşar, sadece loop job'ları iptal edilir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    // ── Yaşam döngüsü ────────────────────────────────────────────────────

    override fun onEnable() {
        PacketEventBus.register(this)
        lastNvInjectMs = 0L
        Log.d("FullBright", "Açıldı (${mode.value})")
        startLoop()
    }

    override fun onDisable() {
        stopLoop()
        PacketEventBus.unregister(this)
        if (mode.value == FbMode.NightVision) removeNightVision()
        Log.d("FullBright", "Kapandı")
    }

    // ── Loop yönetimi ────────────────────────────────────────────────────

    /**
     * FIX 1: loop?.cancel() ardından yeni job başlatıyoruz.
     * Eski kodda `while (isActive && isEnabled)` içindeki `isActive`,
     * dosyanın altındaki extension ile scope'un Job'ını kontrol ediyordu —
     * scope hiç iptal edilmediğinden loop durmuyordu.
     *
     * Düzeltme: `currentCoroutineContext().isActive` kullan — bu, yalnızca
     * çalışan coroutine'in (loop job'ının) aktifliğini kontrol eder.
     */
    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            var attempts = 0
            // FIX 1: currentCoroutineContext().isActive — loop job iptal edilince durur
            while (currentCoroutineContext().isActive && isEnabled) {
                when (mode.value) {
                    FbMode.NightVision -> {
                        if (EntityTracker.selfRuntimeId == 0L) {
                            if (attempts < 20) {
                                Log.d("FullBright", "selfRuntimeId=0, bekleniyor... ($attempts)")
                                delay(500)
                                attempts++
                                continue
                            }
                        }
                        attempts = 0
                        refreshNightVision()
                    }
                    FbMode.Gamma -> injectGamma()
                    FbMode.Lighting -> { /* onPacket'te işlenir */ }
                }
                delay(5000)
            }
        }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    // ── Paket dinleyici ──────────────────────────────────────────────────

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when {
            // StartGame → NV modunda hemen inject et
            // FIX 2: EntityTracker priority=10, FullBright priority=30 — EntityTracker
            // önce çalışır, selfRuntimeId bu noktada zaten set edilmiş olur.
            // 100ms delay yine de bırakıldı — ek güvence.
            mode.value == FbMode.NightVision &&
            event.packetId == BedrockPacketIds.START_GAME -> {
                lastNvInjectMs = 0L
                scope.launch {
                    delay(200) // EntityTracker'ın parseStartGame'i tamamlaması için
                    if (isEnabled) injectNightVision()
                }
            }

            // Gamma: SetTime'ı gündüze çevir
            mode.value == FbMode.Gamma &&
            event.packetId == BedrockPacketIds.SET_TIME &&
            event.direction == PacketEvent.Direction.SERVER_TO_CLIENT -> {
                event.modifiedData = buildSetTime(6000)
            }

            // Lighting: LevelChunk light verisini patchle
            mode.value == FbMode.Lighting &&
            event.packetId == BedrockPacketIds.LEVEL_CHUNK &&
            event.direction == PacketEvent.Direction.SERVER_TO_CLIENT -> {
                patchChunkLight(event)
            }
        }
    }

    // ── Night Vision ─────────────────────────────────────────────────────

    private fun refreshNightVision() {
        val now = System.currentTimeMillis()
        if (now - lastNvInjectMs < NV_REFRESH_MS) return
        lastNvInjectMs = now
        injectNightVision()
    }

    private fun injectNightVision() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) {
            Log.w("FullBright", "NV inject atlandı: selfRuntimeId=0 (StartGame henüz gelmedi)")
            return
        }
        if (!InjectionQueue.isBound) {
            Log.w("FullBright", "NV inject atlandı: InjectionQueue bağlı değil")
            return
        }

        val pkt = PacketHelper.buildMobEffect(
            runtimeId = rid,
            eventId   = 1,           // add
            effectId  = NIGHT_VISION_ID,
            amplifier = (strength.value / 200f).toInt().coerceIn(0, 255),
            particles = false,
            duration  = 2_000_000    // sonsuz
        )
        PacketHelper.injectToClient(pkt)
        Log.d("FullBright", "NV enjekte edildi (rid=$rid)")
    }

    private fun removeNightVision() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) return
        if (!InjectionQueue.isBound) return

        val pkt = PacketHelper.buildMobEffect(
            runtimeId = rid,
            eventId   = 3,           // remove
            effectId  = NIGHT_VISION_ID,
            amplifier = 0,
            particles = false,
            duration  = 0
        )
        PacketHelper.injectToClient(pkt)
        Log.d("FullBright", "NV kaldırıldı")
    }

    // ── Gamma ────────────────────────────────────────────────────────────

    private fun injectGamma() {
        val pkt = buildSetTime(6000)
        PacketHelper.injectToClient(pkt)
    }

    private fun buildSetTime(time: Int): ByteArray {
        val out = ByteArrayOutputStream()
        PacketHelper.writeVarInt(out, BedrockPacketIds.SET_TIME)
        PacketHelper.writeVarInt(out, time)
        return PacketHelper.wrapBatch(out.toByteArray())
    }

    // ── Lighting ─────────────────────────────────────────────────────────

    private fun patchChunkLight(event: PacketEvent) {
        val d = event.data
        try {
            val modified = d.copyOf()
            var i = 0
            while (i < modified.size - 16) {
                if ((0 until 16).all { modified[i + it] == 0.toByte() }) {
                    val end = minOf(i + 2048, modified.size)
                    for (j in i until end) modified[j] = 0xFF.toByte()
                    i = end
                } else {
                    i++
                }
            }
            event.modifiedData = modified
        } catch (_: Exception) {}
    }
}
