package com.oxclient.module.movement

import android.util.Log
import com.oxclient.core.proxy.BedrockPacketIds
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.module.*

/**
 * Jetpack
 *
 * ✅ FIX: handleAuthInput PlayerAuthInput formatı düzeltildi.
 *
 * Bedrock 1.21.60 PlayerAuthInput formatı (paket ID varint'ten sonra):
 *   [pitch    float]   ← pos
 *   [yaw      float]   ← pos+4
 *   [headYaw  float]   ← pos+8  ← ÖNCEKİ KOD BUNU ATLIYORDU → x/y/z 4 byte gerideydi
 *   [x        float]   ← pos+12
 *   [y        float]   ← pos+16
 *   [z        float]   ← pos+20
 *   ...
 *   [inputFlags u64]   ← daha ileride
 *
 * Önceki kod: pos+8'den velX/velZ okuyordu (pos+8 aslında headYaw),
 * sonra pos+8'den inputFlags — tamamen yanlış offset.
 */
class Jetpack : BaseModule(
    name        = "Jetpack",
    category    = ModuleCategory.MOVEMENT,
    description = "Paket tabanlı uçuş / jetpack hareketi"
), PacketListener {

    override val priority = 60

    enum class JetMode { Jetpack, Glide, YPort, Motion, Teleport, Jump }

    // ── Ayarlar ───────────────────────────────────────────────────────────
    private val mode            = EnumSetting("Mode",            JetMode.Jetpack, JetMode.entries)
    private val verticalSpeed   = FloatSetting("Vertical Speed",  1.5f,  0.1f, 10f)
    private val horizontalSpeed = FloatSetting("Horizontal Speed",1.5f,  0.1f, 10f)
    private val add             = FloatSetting("Add",            -0.02f,-0.5f,  0.5f)
    private val pressJump       = BoolSetting("PressJump",        true)
    private val glideSpoof      = BoolSetting("GlideSpoof",       true)
    private val shortcut        = BoolSetting("Shortcut",         false)

    override fun registerSettings() = listOf(
        mode, verticalSpeed, horizontalSpeed, add,
        pressJump, glideSpoof, shortcut
    )

    // ── İç durum ──────────────────────────────────────────────────────────
    @Volatile private var jumpPressed   = false
    @Volatile private var tickCount     = 0
    @Volatile private var yPortY        = 0f
    @Volatile private var yPortPhase    = 0

    companion object {
        private const val TAG = "Jetpack"
        // PlayerAuthInput input flags — bit pozisyonları
        private const val FLAG_JUMPING  = 1 shl 2
        private const val FLAG_SNEAKING = 1 shl 3
    }

    override fun onEnable() {
        PacketEventBus.register(this)
        tickCount = 0; yPortPhase = 0
        Log.d(TAG, "Etkinleştirildi (mode=${mode.value})")
    }

    override fun onDisable() {
        PacketEventBus.unregister(this)
        jumpPressed = false
    }

    // ── Paket intercept ───────────────────────────────────────────────────

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (event.packetId) {
            BedrockPacketIds.PLAYER_AUTH_INPUT -> handleAuthInput(event)
            BedrockPacketIds.MOVE_PLAYER       -> handleMovePlayer(event)
        }
    }

    /**
     * PlayerAuthInput Bedrock 1.21.60 formatı:
     * [packetId varint] [pitch f32] [yaw f32] [headYaw f32] [x f32] [y f32] [z f32]
     * [velX f32] [velZ f32] [inputFlags varlong] ...
     *
     * ✅ FIX: headYaw (4 byte) artık doğru şekilde atlanıyor.
     * ✅ FIX: inputFlags doğru offset'ten okunuyor (pos+24: velX+velZ sonrası).
     */
    private fun handleAuthInput(event: PacketEvent) {
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val d = event.data
        if (d.size < 30) return

        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(d, pos); pos = p1

            /* pitch   */ PacketHelper.readFloatLE(d, pos); pos += 4
            /* yaw     */ PacketHelper.readFloatLE(d, pos); pos += 4
            /* headYaw */ PacketHelper.readFloatLE(d, pos); pos += 4  // ✅ FIX: artık atlanıyor
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos); pos += 4
            /* velX    */ PacketHelper.readFloatLE(d, pos); pos += 4
            /* velZ    */ PacketHelper.readFloatLE(d, pos); pos += 4
            // inputFlags: u64 → iki varint olarak oku (lo word yeterli)
            val (flagsLo, _) = PacketHelper.readVarInt(d, pos)
            jumpPressed = (flagsLo and FLAG_JUMPING) != 0

            tickCount++
            val newY = calcNewY(y)

            if (newY != y) {
                val modified = PacketHelper.patchPlayerAuthInputPosition(d, x, newY, z)
                event.modifiedData = modified
            }
        } catch (_: Exception) {}
    }

    private fun handleMovePlayer(event: PacketEvent) {
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val d = event.data
        if (d.size < 24) return

        try {
            var pos = 0
            val (_, p1)   = PacketHelper.readVarInt(d, pos); pos = p1
            val (rid, p2) = PacketHelper.readVarLong(d, pos); pos = p2
            if (rid != EntityTracker.selfRuntimeId) return

            val x     = PacketHelper.readFloatLE(d, pos); pos += 4
            val y     = PacketHelper.readFloatLE(d, pos); pos += 4
            val z     = PacketHelper.readFloatLE(d, pos); pos += 4
            val pitch = PacketHelper.readFloatLE(d, pos); pos += 4
            val yaw   = PacketHelper.readFloatLE(d, pos)

            val newY = calcNewY(y)
            if (newY != y) {
                event.modifiedData = PacketHelper.buildMovePlayer(
                    rid, x, newY, z, yaw, pitch, yaw,
                    onGround = false, teleport = mode.value == JetMode.Teleport
                )
            }
        } catch (_: Exception) {}
    }

    // ── Y hesaplama ───────────────────────────────────────────────────────

    private fun calcNewY(currentY: Float): Float {
        val vSpeed = verticalSpeed.value
        val addVal = add.value

        return when (mode.value) {

            JetMode.Jetpack -> {
                if (pressJump.value && jumpPressed) currentY + vSpeed * 0.05f
                else currentY + addVal
            }

            JetMode.Glide -> {
                if (currentY > EntityTracker.selfY) currentY
                else currentY - 0.05f
            }

            JetMode.YPort -> {
                if (tickCount % 20 < 10) currentY + vSpeed * 0.1f
                else currentY - vSpeed * 0.08f
            }

            JetMode.Motion -> {
                currentY + vSpeed * 0.05f + addVal
            }

            JetMode.Teleport -> {
                currentY + vSpeed * 0.5f
            }

            JetMode.Jump -> {
                if (tickCount % 10 == 0) currentY + 0.42f
                else currentY + addVal
            }
        }
    }
}
