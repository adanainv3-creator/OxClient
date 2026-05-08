package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.module.*

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
) {

    enum class CritMode { Vanilla, MovePacket, Jump, TPJump }

    private val mode     = EnumSetting("Mode", CritMode.Vanilla, CritMode.entries)
    private val shortcut = BoolSetting("Shortcut", false)

    override fun registerSettings() = listOf(mode, shortcut)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (mode.value) {
            CritMode.Vanilla    -> handleVanilla(event)
            CritMode.MovePacket -> handleMovePacket(event)
            CritMode.Jump       -> handleJump(event)
            CritMode.TPJump     -> handleTPJump(event)
        }
    }

    // Relay hazır olduğunda gerçek paket manipülasyonu buraya gelecek
    private fun handleVanilla(event: PacketEvent)    {}
    private fun handleMovePacket(event: PacketEvent) {}
    private fun handleJump(event: PacketEvent)       {}
    private fun handleTPJump(event: PacketEvent)     {}
}
