package com.oxclient.module.visual

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.data.camera.CameraEase
import org.cloudburstmc.protocol.bedrock.data.camera.CameraFovInstruction
import org.cloudburstmc.protocol.bedrock.packet.CameraInstructionPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket

class FOVChanger : BaseModule(
    name        = "FOVChanger",
    category    = ModuleCategory.VISUAL,
    description = "Oyuncunun görüş açısını (FOV) değiştirir"
) {
    private val fov         = float("FOV",          110f, 30f, 170f)
    private val easeTime    = float("Ease Time",     0f,   0f,  5f)
    private val refreshSec  = int  ("Refresh (s)",   5,    1,   30)
    private val shortcut    = bool ("Shortcut",      false)

    private val TAG = "FOVChanger"
    private var loop: Job? = null

    override fun onEnable() {
        super.onEnable()
        loop = scope.launch {
            while (currentCoroutineContext().isActive && isEnabled) {
                applyFov()
                delay(refreshSec.value * 1000L)
            }
        }
    }

    override fun onDisable() {
        loop?.cancel()
        loop = null
        super.onDisable()
        resetFov()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.packet is StartGamePacket) {
            scope.launch { delay(200); if (isEnabled) applyFov() }
        }
    }

    private fun applyFov() {
        val session = PacketEventBus.currentSession
            ?: run { OverlayLogger.w(TAG, "FOV atlandı: session yok"); return }
        session.clientBound(CameraInstructionPacket().apply {
            fovInstruction = CameraFovInstruction(fov.value, easeTime.value, CameraEase.LINEAR, false)
        })
    }

    private fun resetFov() {
        val session = PacketEventBus.currentSession ?: return
        session.clientBound(CameraInstructionPacket().apply {
            fovInstruction = CameraFovInstruction(0f, 0f, CameraEase.LINEAR, true)
        })
    }
}
