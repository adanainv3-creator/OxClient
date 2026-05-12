package com.oxclient.module.visual

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket

class FullBright : BaseModule(
    name        = "FullBright",
    category    = ModuleCategory.VISUAL,
    description = "Geceyi gündüz gibi görünür yapar"
) {
    enum class FbMode { Gamma, NightVision }

    private val mode     = enum ("Mode",     FbMode.NightVision)
    private val strength = float("Strength", 100f, 1000f, 1000f)
    private val shortcut = bool ("Shortcut", false)

    private val TAG = "FullBright"
    private var loop: Job? = null

    override fun onEnable() {
        super.onEnable()
        OverlayLogger.d(TAG, "Açıldı (${mode.value})")
        loop = scope.launch {
            var attempts = 0
            while (currentCoroutineContext().isActive && isEnabled) {
                if (mode.value == FbMode.NightVision) {
                    if (EntityTracker.selfUniqueId == 0L && attempts < 20) {
                        OverlayLogger.d(TAG, "selfUniqueId=0, bekleniyor ($attempts)")
                        delay(500); attempts++; continue
                    }
                    attempts = 0
                    injectNightVision()
                }
                delay(8000)
            }
        }
    }

    override fun onDisable() {
        loop?.cancel(); loop = null
        removeNightVision()
        super.onDisable()
        OverlayLogger.d(TAG, "Kapandı")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (event.packet) {
            is StartGamePacket -> {
                if (mode.value == FbMode.NightVision) {
                    scope.launch { delay(200); if (isEnabled) injectNightVision() }
                }
            }
            is SetTimePacket -> {
                if (mode.value == FbMode.Gamma && event.direction == PacketEvent.Direction.SERVER_TO_CLIENT)
                    event.replacementPacket = SetTimePacket().apply { time = 6000 }
            }
            else -> {}
        }
    }

    private fun injectNightVision() {
        val uid = EntityTracker.selfUniqueId
        if (uid == 0L) { OverlayLogger.w(TAG, "NV atlandı: selfUniqueId=0"); return }
        val session = PacketEventBus.currentSession
            ?: run { OverlayLogger.w(TAG, "NV atlandı: session yok"); return }
        session.clientBound(MobEffectPacket().apply {
            runtimeEntityId = uid
            event           = MobEffectPacket.Event.ADD
            effectId        = 16
            amplifier       = (strength.value / 200f).toInt().coerceIn(0, 255)
            isParticles     = false
            duration        = 2_000_000
        })
        OverlayLogger.i(TAG, "NV enjekte edildi (uid=$uid)")
    }

    private fun removeNightVision() {
        val uid = EntityTracker.selfUniqueId; if (uid == 0L) return
        val session = PacketEventBus.currentSession ?: return
        session.clientBound(MobEffectPacket().apply {
            runtimeEntityId = uid; event = MobEffectPacket.Event.REMOVE
            effectId = 16; amplifier = 0; isParticles = false; duration = 0
        })
    }
}
