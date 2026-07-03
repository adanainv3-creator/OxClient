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
    enum class FbMode { NightVision, TimeForce, Both }

    private val mode        = enum ("Mode",          FbMode.NightVision)
    private val strength    = float("Strength",      1000f, 1f, 1000f)
    private val forceTime   = int  ("Force Time",    6000,  0,  24000)
    private val refreshSec  = int  ("Refresh (s)",   8,     1,  60)
    private val shortcut    = bool ("Shortcut",      false)

    private val TAG = "FullBright"
    private var loop: Job? = null

    override fun onEnable() {
        super.onEnable()
        OverlayLogger.d(TAG, "Açıldı (${mode.value})")
        loop = scope.launch {
            var attempts = 0
            while (currentCoroutineContext().isActive && isEnabled) {
                val needsNv = mode.value == FbMode.NightVision || mode.value == FbMode.Both
                if (needsNv) {
                    if (EntityTracker.selfUniqueId == 0L && attempts < 20) {
                        delay(500); attempts++; continue
                    }
                    attempts = 0
                    injectNightVision()
                }
                delay(refreshSec.value * 1000L)
            }
        }
    }

    override fun onDisable() {
        loop?.cancel()
        loop = null
        val needsNv = mode.value == FbMode.NightVision || mode.value == FbMode.Both
        if (needsNv) removeNightVision()
        super.onDisable()
        OverlayLogger.d(TAG, "Kapandı")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (event.packet) {
            is StartGamePacket -> {
                val needsNv = mode.value == FbMode.NightVision || mode.value == FbMode.Both
                if (needsNv) {
                    scope.launch { delay(200); if (isEnabled) injectNightVision() }
                }
            }
            is SetTimePacket -> {
                val needsTime = mode.value == FbMode.TimeForce || mode.value == FbMode.Both
                if (needsTime && event.direction == PacketEvent.Direction.SERVER_TO_CLIENT)
                    event.replacementPacket = SetTimePacket().apply { time = forceTime.value }
            }
            else -> {}
        }
    }

    private fun injectNightVision() {
        // ✅ FIX: MobEffectPacket.runtimeEntityId RUNTIME id bekliyor, UNIQUE id değil.
        // selfUniqueId kullanıldığı için istemci paketi kendi oyuncusuna ait tanımıyordu
        // ve efekt hiçbir zaman görünmüyordu.
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) { OverlayLogger.w(TAG, "NV atlandı: selfRuntimeId=0"); return }
        val session = PacketEventBus.currentSession
            ?: run { OverlayLogger.w(TAG, "NV atlandı: session yok"); return }
        session.clientBound(MobEffectPacket().apply {
            runtimeEntityId = rid
            event           = MobEffectPacket.Event.ADD
            effectId        = 16
            amplifier       = (strength.value / 200f).toInt().coerceIn(0, 255)
            isParticles     = false
            duration        = 2_000_000
        })
        OverlayLogger.i(TAG, "NV enjekte edildi (rid=$rid)")
    }

    private fun removeNightVision() {
        val rid = EntityTracker.selfRuntimeId
        if (rid == 0L) return
        val session = PacketEventBus.currentSession ?: return
        session.clientBound(MobEffectPacket().apply {
            runtimeEntityId = rid
            event           = MobEffectPacket.Event.REMOVE
            effectId        = 16
            amplifier       = 0
            isParticles     = false
            duration        = 0
        })
    }
}
