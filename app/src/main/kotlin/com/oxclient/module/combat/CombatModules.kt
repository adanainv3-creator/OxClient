package com.oxclient.module.combat

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.module.*

// ── KillAura ─────────────────────────────────────────────────────────────────

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Menzildeki varlıklara otomatik saldırı"
) {
    enum class TargetMode { CLOSEST, MOST_DAMAGED, LOWEST_HP }

    private val range      = FloatSetting("Menzil",      default = 3.5f, min = 1f, max = 6f)
    private val cps        = IntSetting("CPS",           default = 12,   min = 1,  max = 20)
    private val targetMode = EnumSetting("Hedef", TargetMode.CLOSEST, TargetMode.entries)
    private val throughWalls = BoolSetting("Duvar Görmezden Gel", false)
    private val swingArm   = BoolSetting("Kol Sallama",  true)

    override fun registerSettings() = listOf(range, cps, targetMode, throughWalls, swingArm)

    private var lastAttack = 0L

    override fun onEnable()  { Log.d("KillAura", "Aktif — menzil: ${range.value}, CPS: ${cps.value}") }
    override fun onDisable() { Log.d("KillAura", "Devre dışı") }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Gerçek implementasyon: MovePlayerPacket parse → hedef bul → AttackPacket gönder
        val now = System.currentTimeMillis()
        val delay = 1000L / cps.value
        if (now - lastAttack < delay) return
        lastAttack = now
        // Hedef seçimi ve saldırı buraya
    }
}

// ── Criticals ────────────────────────────────────────────────────────────────

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik yapar"
) {
    enum class CritMode { PACKET, JUMP, MINI_JUMP }

    private val mode     = EnumSetting("Mod",   CritMode.PACKET, CritMode.entries)
    private val onlyOnGround = BoolSetting("Sadece Yerde", false)

    override fun registerSettings() = listOf(mode, onlyOnGround)

    override fun onEnable()  { Log.d("Criticals", "Aktif — mod: ${mode.value}") }
    override fun onDisable() { Log.d("Criticals", "Devre dışı") }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Gerçek implementasyon: saldırıdan önce fake fall paketi ekle
    }
}
