package com.oxclient.module.visual

import com.oxclient.core.proxy.BedrockPacketIds
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.InjectionQueue
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
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
    private val TAG             = "FullBright"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    // ── Yaşam döngüsü ────────────────────────────────────────────────────

    override fun onEnable() {
        PacketEventBus.register(this)
        lastNvInjectMs = 0L
        OverlayLogger.d(TAG, "Açıldı (${mode.value})")
        startLoop()
    }

    override fun onDisable() {
        stopLoop()
        PacketEventBus.unregister(this)
        if (mode.value == FbMode.NightVision) removeNightVision()
        OverlayLogger.d(TAG, "Kapandı")
    }

    // ── Loop yönetimi ────────────────────────────────────────────────────

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            var attempts = 0
            while (currentCoroutineContext().isActive && isEnabled) {
                when (mode.value) {
                    FbMode.NightVision -> {
                        // ✅ FIX: selfUniqueId kontrolü — runtimeId değil
                        if (EntityTracker.selfUniqueId == 0L) {
                            if (attempts < 20) {
                                OverlayLogger.d(TAG, "selfUniqueId=0, bekleniyor... ($attempts)")
                                delay(500)
                                attempts++
                                continue
                            }
                        }
                        attempts = 0
                        refreshNightVision()
                    }
                    FbMode.Gamma    -> injectGamma()
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
            mode.value == FbMode.NightVision &&
            event.packetId == BedrockPacketIds.START_GAME -> {
                lastNvInjectMs = 0L
                scope.launch {
                    delay(200)
                    if (isEnabled) injectNightVision()
                }
            }

            mode.value == FbMode.Gamma &&
            event.packetId == BedrockPacketIds.SET_TIME &&
            event.direction == PacketEvent.Direction.SERVER_TO_CLIENT -> {
                event.modifiedData = buildSetTime(6000)
            }

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
        // ✅ FIX: uniqueEntityId kullan, runtimeId değil
        val uid = EntityTracker.selfUniqueId
        if (uid == 0L) {
            OverlayLogger.w(TAG, "NV inject atlandı: selfUniqueId=0")
            return
        }
        if (!InjectionQueue.isBound) {
            OverlayLogger.w(TAG, "NV inject atlandı: InjectionQueue bağlı değil")
            return
        }

        val pkt = PacketHelper.buildMobEffect(
            uniqueEntityId = uid,
            eventId        = 1,
            effectId       = NIGHT_VISION_ID,
            amplifier      = (strength.value / 200f).toInt().coerceIn(0, 255),
            particles      = false,
            duration       = 2_000_000
        )
        PacketHelper.injectToClient(pkt)
        OverlayLogger.i(TAG, "NV enjekte edildi (uid=$uid)")
    }

    private fun removeNightVision() {
        val uid = EntityTracker.selfUniqueId
        if (uid == 0L || !InjectionQueue.isBound) return

        val pkt = PacketHelper.buildMobEffect(
            uniqueEntityId = uid,
            eventId        = 3,
            effectId       = NIGHT_VISION_ID,
            amplifier      = 0,
            particles      = false,
            duration       = 0
        )
        PacketHelper.injectToClient(pkt)
        OverlayLogger.i(TAG, "NV kaldırıldı")
    }

    // ── Gamma ────────────────────────────────────────────────────────────

    private fun injectGamma() {
        PacketHelper.injectToClient(buildSetTime(6000))
    }

    private fun buildSetTime(time: Int): ByteArray {
        val out = ByteArrayOutputStream()
        PacketHelper.writeVarInt(out, BedrockPacketIds.SET_TIME)
        PacketHelper.writeVarInt(out, time)
        return PacketHelper.wrapBatch(out.toByteArray())
    }

    // ── Lighting ─────────────────────────────────────────────────────────

    private fun patchChunkLight(event: PacketEvent) {
        try {
            val modified = event.data.copyOf()
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
