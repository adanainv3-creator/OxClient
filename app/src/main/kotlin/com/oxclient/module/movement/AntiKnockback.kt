package com.oxclient.module.movement

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

/**
 * AntiKnockback — sunucudan gelen knockback (SetEntityMotionPacket) paketlerini
 * client'a ulaşmadan önce yakalayıp azaltır veya tamamen iptal eder.
 *
 * Bedrock'ta knockback, sunucunun oyuncunun kendi entity'sine gönderdiği
 * SetEntityMotionPacket ile uygulanır (client bu vektörü olduğu gibi işler).
 * Bu yüzden paketi client'a ORİJİNAL haliyle iletmek yerine, burada
 * yatay (X/Z) ve dikey (Y) bileşenleri ayrı ayrı azaltılmış bir kopyayla
 * değiştiriyoruz (cancelAndReplace) — relay bunu client'a öyle gönderiyor.
 */
class AntiKnockback : BaseModule(
    name        = "AntiKnockback",
    category    = ModuleCategory.MOVEMENT,
    description = "Gelen knockback'i azaltır veya tamamen iptal eder"
) {
    enum class Mode { Cancel, Reduce, FullBlock }

    private val mode                = enum ("Mode",                 Mode.Reduce)
    private val horizontalReduction = float("Horizontal Reduction", 70f, 0f, 100f)
    private val verticalReduction   = float("Vertical Reduction",   40f, 0f, 100f)
    private val minMotionThreshold  = float("Min Motion Threshold", 0.02f, 0f, 1f)

    private companion object { const val TAG = "AntiKnockback" }

    @Volatile private var blockedCount = 0L
    @Volatile private var reducedCount = 0L

    override fun onEnable() {
        super.onEnable()
        blockedCount = 0L
        reducedCount = 0L
        OverlayLogger.d(TAG, "Enabled: mode=${mode.value} hReduction=${horizontalReduction.value}% vReduction=${verticalReduction.value}%")
    }

    override fun onDisable() {
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled — blocked=$blockedCount reduced=$reducedCount")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

        val pkt = event.packet
        if (pkt !is SetEntityMotionPacket) return
        if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return

        val motion = pkt.motion ?: return

        // Çok küçük hareketler (yürüme/zıplama kaynaklı doğal motion) knockback değildir,
        // dokunulmadan geçirilir — yoksa normal hareket de garip hissettirir.
        val horizontalMag = kotlin.math.sqrt(motion.x * motion.x + motion.z * motion.z)
        if (horizontalMag < minMotionThreshold.value && kotlin.math.abs(motion.y) < minMotionThreshold.value) {
            return
        }

        when (mode.value) {
            Mode.FullBlock -> {
                // Referans implementasyondaki intercept() ile birebir aynı davranış:
                // paket hiçbir değişiklik/eşik kontrolü yapılmadan tamamen bloklanır,
                // client'a hiçbir motion paketi gitmez. Reduce/Cancel'daki eşik ve
                // vektör mantığı burada devre dışıdır — kaba ama basit bir tam blok.
                blockedCount++
                OverlayLogger.v(TAG, "Knockback tamamen bloklandı (FullBlock): orijinal=(${motion.x}, ${motion.y}, ${motion.z})")
                event.cancel()
            }
            Mode.Cancel -> {
                blockedCount++
                OverlayLogger.v(TAG, "Knockback iptal edildi: orijinal=(${motion.x}, ${motion.y}, ${motion.z})")
                event.cancelAndReplace(SetEntityMotionPacket().apply {
                    runtimeEntityId = pkt.runtimeEntityId
                    this.motion     = Vector3f.from(0f, 0f, 0f)
                })
            }
            Mode.Reduce -> {
                val hFactor = 1f - (horizontalReduction.value / 100f)
                val vFactor = 1f - (verticalReduction.value / 100f)

                val newMotion = Vector3f.from(
                    motion.x * hFactor,
                    motion.y * vFactor,
                    motion.z * hFactor
                )

                reducedCount++
                OverlayLogger.v(TAG, "Knockback azaltıldı: (${motion.x}, ${motion.y}, ${motion.z}) -> (${newMotion.x}, ${newMotion.y}, ${newMotion.z})")

                event.cancelAndReplace(SetEntityMotionPacket().apply {
                    runtimeEntityId = pkt.runtimeEntityId
                    this.motion     = newMotion
                })
            }
        }
    }
}
