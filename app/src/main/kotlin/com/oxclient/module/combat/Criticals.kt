package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.core.proxy.BedrockPacketIds
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketListener
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
), PacketListener {

    override val priority = 70  // KillAura'dan önce

    enum class CritMode { Vanilla, MovePacket, Jump, TPJump }

    private val mode     = EnumSetting("Mode", CritMode.Vanilla, CritMode.entries)
    private val shortcut = BoolSetting("Shortcut", false)

    override fun registerSettings() = listOf(mode, shortcut)

    @Volatile private var lastCritMs = 0L

    override fun onEnable()  { PacketEventBus.register(this) }
    override fun onDisable() { PacketEventBus.unregister(this) }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // InventoryTransaction USE_ITEM_ON_ENTITY (saldırı paketi) yakala
        if (event.packetId != BedrockPacketIds.INVENTORY_TRANSACTION) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val d = event.data
        if (d.size < 3) return
        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(d, pos); pos = p1
            val (_, p2) = PacketHelper.readVarInt(d, pos); pos = p2  // legacyRequestId
            val (txType, _) = PacketHelper.readVarInt(d, pos)
            if (txType != 2) return  // USE_ITEM_ON_ENTITY değilse çık
        } catch (_: Exception) { return }

        val now = System.currentTimeMillis()
        if (now - lastCritMs < 100) return
        lastCritMs = now

        when (mode.value) {
            CritMode.Vanilla    -> injectVanilla()
            CritMode.MovePacket -> injectMovePacket()
            CritMode.Jump       -> injectJump()
            CritMode.TPJump     -> injectTPJump()
        }
    }

    /**
     * VANILLA — gerçek zıplama yayı simüle eder.
     * En az ban riski, en doğal davranış.
     */
    private fun injectVanilla() {
        val rid = EntityTracker.selfRuntimeId
        val x = EntityTracker.selfX; val y = EntityTracker.selfY; val z = EntityTracker.selfZ
        val yaw = EntityTracker.selfYaw; val pitch = EntityTracker.selfPitch

        val frames = listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f)
        var cumY = 0f
        frames.forEach { dy ->
            cumY += dy
            PacketHelper.injectToServer(
                PacketHelper.buildMovePlayer(rid, x, y + cumY, z, yaw, pitch, yaw,
                    onGround = (dy == 0f), teleport = false)
            )
        }
    }

    /**
     * MOVE_PACKET — onGround=false ile tek paket.
     * En hızlı, bazı sunucularda çalışmaz.
     */
    private fun injectMovePacket() {
        val rid = EntityTracker.selfRuntimeId
        val x = EntityTracker.selfX; val y = EntityTracker.selfY; val z = EntityTracker.selfZ
        val yaw = EntityTracker.selfYaw; val pitch = EntityTracker.selfPitch

        PacketHelper.injectToServer(
            PacketHelper.buildMovePlayer(rid, x, y + 0.11f, z, yaw, pitch, yaw, onGround = false))
        PacketHelper.injectToServer(
            PacketHelper.buildMovePlayer(rid, x, y, z, yaw, pitch, yaw, onGround = true))
    }

    /**
     * JUMP — mikro zıplama, 4 kare.
     */
    private fun injectJump() {
        val rid = EntityTracker.selfRuntimeId
        val x = EntityTracker.selfX; val y = EntityTracker.selfY; val z = EntityTracker.selfZ
        val yaw = EntityTracker.selfYaw; val pitch = EntityTracker.selfPitch

        listOf(0.0625f, 0f, 0.0625f, 0f).forEach { dy ->
            PacketHelper.injectToServer(
                PacketHelper.buildMovePlayer(rid, x, y + dy, z, yaw, pitch, yaw,
                    onGround = (dy == 0f), teleport = false))
        }
    }

    /**
     * TP_JUMP — anlık yukarı teleport + aşağı teleport.
     * Yüksek güç, yüksek tespit riski.
     */
    private fun injectTPJump() {
        val rid = EntityTracker.selfRuntimeId
        val x = EntityTracker.selfX; val y = EntityTracker.selfY; val z = EntityTracker.selfZ
        val yaw = EntityTracker.selfYaw; val pitch = EntityTracker.selfPitch

        PacketHelper.injectToServer(
            PacketHelper.buildMovePlayer(rid, x, y + 0.42f, z, yaw, pitch, yaw,
                onGround = false, teleport = true))
        PacketHelper.injectToServer(
            PacketHelper.buildMovePlayer(rid, x, y, z, yaw, pitch, yaw,
                onGround = true, teleport = true))
    }
}