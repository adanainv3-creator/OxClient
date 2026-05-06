package com.oxclient.module.movement

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.module.*

// ── TPAura ────────────────────────────────────────────────────────────────────

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Hedef varlığa anında ışınlanır"
) {
    private val range    = FloatSetting("Menzil",       default = 5f,   min = 1f,  max = 20f)
    private val cooldown = IntSetting("Bekleme (ms)", default = 1000, min = 100, max = 5000)
    private val onlyHostile = BoolSetting("Sadece Düşman", true)

    override fun registerSettings() = listOf(range, cooldown, onlyHostile)

    private var lastTp = 0L

    override fun onEnable()  { Log.d("TPAura", "Aktif") }
    override fun onDisable() { Log.d("TPAura", "Devre dışı") }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastTp < cooldown.value) return
        lastTp = now
        // Gerçek implementasyon: en yakın hedefi bul, MovePlayerPacket ile ışınlan
    }
}

// ── Sprint ────────────────────────────────────────────────────────────────────

class Sprint : BaseModule(
    name        = "Sprint",
    category    = ModuleCategory.MOVEMENT,
    description = "Her zaman sprint modunda hareket eder"
) {
    enum class SprintMode { OMNI, LEGIT }

    private val mode = EnumSetting("Mod", SprintMode.OMNI, SprintMode.entries)
    override fun registerSettings() = listOf(mode)

    override fun onEnable()  { Log.d("Sprint", "Aktif") }
    override fun onDisable() { Log.d("Sprint", "Devre dışı") }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Gerçek implementasyon: MovePlayerPacket'te sprint flag'ini zorla
    }
}

// ── NoFall ────────────────────────────────────────────────────────────────────

class NoFall : BaseModule(
    name        = "NoFall",
    category    = ModuleCategory.MOVEMENT,
    description = "Düşme hasarını sıfırlar"
) {
    enum class NoFallMode { PACKET, GROUND_SPOOF }

    private val mode = EnumSetting("Mod", NoFallMode.PACKET, NoFallMode.entries)
    override fun registerSettings() = listOf(mode)

    override fun onEnable()  { Log.d("NoFall", "Aktif") }
    override fun onDisable() { Log.d("NoFall", "Devre dışı") }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Gerçek implementasyon: MovePlayerPacket'te onGround=true zorla
    }
}
