package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * ✅ HeadTrack — Kafanızı hedefi takip etmesini sağlar
 * 
 * Sadece rotation kilitleme, saldırı yok. Smooth interpolation ile
 * natural head movement simülasyonu.
 */
class HeadTrack : BaseModule(
    name        = "HeadTrack",
    category    = ModuleCategory.COMBAT,
    description = "Kafayı hedefi takip ettirir (saldırısız)"
), PacketEventBus.PacketListener {

    private val detectRange    = float("Detect Range",    100f, 10f, 150f)
    private val smooth         = float("Smoothness",      0.15f, 0.01f, 1f)   // 0-1: daha düşük = daha smooth
    private val priority       = enum ("Priority",        Priority.Distance)
    private val shortcut       = bool ("Shortcut",        false)

    enum class Priority { Distance, Health, LowestHealth }

    @Volatile private var currentTargetId = 0L
    @Volatile private var headYaw = 0f
    @Volatile private var headPitch = 0f
    @Volatile private var lastUpdateMs = 0L

    private companion object { const val TAG = "HeadTrack" }

    override fun onEnable() {
        super.onEnable()
        headYaw = EntityTracker.selfYaw
        headPitch = EntityTracker.selfPitch
        currentTargetId = 0L
        
        PacketEventBus.register(this)
        OverlayLogger.d(TAG, "Enabled: detectRange=${detectRange.value} smooth=${smooth.value} priority=${priority.value}")
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()
        if (now - lastUpdateMs < 50L) return  // Her 50ms'de bir (20 tick/s)
        lastUpdateMs = now

        val target = findTarget() ?: return
        updateHeadLock(target)
    }

    private fun findTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(detectRange.value)
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }

        if (candidates.isEmpty()) {
            if (currentTargetId != 0L) {
                currentTargetId = 0L
                OverlayLogger.v(TAG, "Hedef kaybedildi")
            }
            return null
        }

        val sorted = when (priority.value) {
            Priority.Distance -> candidates.sortedBy { EntityTracker.distanceTo(it) }
            Priority.Health -> candidates.sortedBy { it.health }
            Priority.LowestHealth -> candidates.sortedBy { it.health }
        }

        val target = sorted.firstOrNull() ?: return null
        
        if (target.runtimeId != currentTargetId) {
            currentTargetId = target.runtimeId
            OverlayLogger.d(TAG, "Hedef: ${target.name.ifEmpty { target.runtimeId.toString() }} dist=${"%.1f".format(EntityTracker.distanceTo(target))}")
        }
        
        return target
    }

    private fun updateHeadLock(target: EntityTracker.TrackedEntity) {
        val session = PacketEventBus.currentSession ?: return

        val targetRot = RotationUtil.toEntity(target)
        
        // ✅ Smooth interpolation
        val smoothFactor = smooth.value.coerceIn(0.01f, 1f)
        
        val newYaw = smoothYaw(headYaw, targetRot.yaw, smoothFactor)
        val newPitch = smoothPitch(headPitch, targetRot.pitch, smoothFactor)

        headYaw = newYaw
        headPitch = newPitch

        try {
            PacketUtil.sendMoveAtSelf(session, newYaw, newPitch, onGround = true)
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Rotation packet error: ${e.message}", e)
        }
    }

    private fun smoothYaw(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        
        val result = current + (diff * factor)
        
        var normalized = result % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        
        return normalized
    }

    private fun smoothPitch(current: Float, target: Float, factor: Float): Float {
        val diff = target - current
        return (current + (diff * factor)).coerceIn(-90f, 90f)
    }
}
