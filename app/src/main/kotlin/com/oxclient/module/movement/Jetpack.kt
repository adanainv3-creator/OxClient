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
 * PlayerAuthInput veya MovePlayer paketlerini intercept ederek
 * Y ve XZ hızını mod'a göre değiştirir.
 *
 * Modlar:
 *  JETPACK   — jump basılıyken yukarı hızlanır (Y += verticalSpeed)
 *  GLIDE     — düşüşü yavaşlatır (Y = -add)
 *  YPORT     — belirli aralıkla yukarı teleport eder
 *  MOTION    — sabit Y motion paketi enjekte eder
 *  TELEPORT  — sürekli yukarı ışınlanma
 *  JUMP      — her tick'te zıplama paketi
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
    @Volatile private var yPortPhase    = 0  // 0=up, 1=down

    companion object {
        private const val TAG = "Jetpack"
        // PlayerAuthInput input flags
        private const val FLAG_JUMPING  = 1 shl 2   // bit 2
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

    private fun handleAuthInput(event: PacketEvent) {
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val d = event.data
        if (d.size < 30) return

        try {
            // PlayerAuthInput: [packetId] [pitch f32] [yaw f32] [x f32] [y f32] [z f32] [velX f32] [velZ f32] [inputFlags u64 varint]
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(d, pos); pos = p1
            val pitch = PacketHelper.readFloatLE(d, pos); pos += 4
            val yaw   = PacketHelper.readFloatLE(d, pos); pos += 4
            val x     = PacketHelper.readFloatLE(d, pos); pos += 4
            val y     = PacketHelper.readFloatLE(d, pos); pos += 4
            val z     = PacketHelper.readFloatLE(d, pos); pos += 4

            // inputFlags (2 varint = u64)
            val (flagsLo, p2) = PacketHelper.readVarInt(d, pos + 8); pos = p2
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
            val (_, p1) = PacketHelper.readVarInt(d, pos); pos = p1
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
                // Jump basılıysa yukarı, basılı değilse add (hafif düşüş/yüzme)
                if (pressJump.value && jumpPressed) currentY + vSpeed * 0.05f
                else currentY + addVal
            }

            JetMode.Glide -> {
                // Düşüşü yavaşlat — Y'yi sabit tut veya çok yavaş düşür
                if (currentY > EntityTracker.selfY) currentY  // zaten yüksekteyse tut
                else currentY - 0.05f  // yavaş düşüş
            }

            JetMode.YPort -> {
                // 10 tick yukarı, 10 tick aşağı (sunucu bypass)
                if (tickCount % 20 < 10) currentY + vSpeed * 0.1f
                else currentY - vSpeed * 0.08f
            }

            JetMode.Motion -> {
                // Sabit Y hareketi
                currentY + vSpeed * 0.05f + addVal
            }

            JetMode.Teleport -> {
                // Her tick teleport ile yukarı
                currentY + vSpeed * 0.5f
            }

            JetMode.Jump -> {
                // Her 10 tick'te bir zıplama yüksekliği
                if (tickCount % 10 == 0) currentY + 0.42f
                else currentY + addVal
            }
        }
    }
}