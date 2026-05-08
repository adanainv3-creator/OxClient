package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.module.*

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Yakındaki düşmanlara otomatik saldırır"
) {

    enum class AttackMode { Single, Multi, Switch }
    enum class RotationMode { Lock, Approximate, None }
    enum class SwingMode { Client, Server, Both, None }
    enum class PriorityMode { Distance, Health, Direction }

    // ── Ayarlar ──────────────────────────────────────────────────────────────
    private val cpsMin        = IntSetting("CPS Min",        10,  1,  20)
    private val cpsMax        = IntSetting("CPS Max",        20,  1,  20)
    private val range         = FloatSetting("Range",        3.7f, 1f, 6f)
    private val fov           = IntSetting("Fov",           180,  30, 360)
    private val switchDelay   = IntSetting("SwitchDelay",    50,   0, 500)
    private val attackMode    = EnumSetting("AttackMode",   AttackMode.Single,    AttackMode.entries)
    private val rotationMode  = EnumSetting("RotationMode", RotationMode.Lock,    RotationMode.entries)
    private val swingMode     = EnumSetting("Swing",        SwingMode.Both,       SwingMode.entries)
    private val priorityMode  = EnumSetting("PriorityMode", PriorityMode.Distance,PriorityMode.entries)
    private val reversePriority = BoolSetting("ReversePriority", false)
    private val mouseover       = BoolSetting("Mouseover",       false)
    private val swingSound      = BoolSetting("SwingSound",      true)
    private val failRate        = FloatSetting("FailRate",        0f,   0f, 1f)
    private val shortcut        = BoolSetting("Shortcut",        false)

    override fun registerSettings() = listOf(
        cpsMin, cpsMax, range, fov, switchDelay,
        attackMode, rotationMode, swingMode, priorityMode,
        reversePriority, mouseover, swingSound, failRate, shortcut
    )

    // ── Paket kancası ─────────────────────────────────────────────────────────
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Relay/MITM bağlantısı hazır olduğunda buraya
        // handleAttack(event) çağrısı yapılacak
    }

    override fun onEnable()  { /* rotasyon başlat */ }
    override fun onDisable() { /* rotasyon sıfırla */ }
}
