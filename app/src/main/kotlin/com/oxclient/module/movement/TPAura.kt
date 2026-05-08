package com.oxclient.module.movement

import com.oxclient.events.PacketEvent
import com.oxclient.module.*

class TPAura : BaseModule(
    name        = "TPAura",
    category    = ModuleCategory.MOVEMENT,
    description = "Hedefe ışınlanarak saldırır"
) {

    enum class TpMode { Random, Strafe, Behind, Speed }

    private val mode            = EnumSetting("Mode",            TpMode.Strafe, TpMode.entries)
    private val range           = FloatSetting("Range",          1.5f,  0.5f, 6f)
    private val yOffset         = FloatSetting("Y Offset",       0f,   -2f,   2f)
    private val passive         = BoolSetting("Passive",         false)
    private val horizontalSpeed = FloatSetting("HorizontalSpeed",6.11f, 0.1f, 20f)
    private val verticalSpeed   = FloatSetting("VerticalSpeed",  4f,    0.1f, 10f)
    private val strafeSpeed     = FloatSetting("StrafeSpeed",    20f,   1f,   50f)
    private val shortcut        = BoolSetting("Shortcut",        true)

    override fun registerSettings() = listOf(
        mode, range, yOffset, passive,
        horizontalSpeed, verticalSpeed, strafeSpeed, shortcut
    )

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Relay bağlandığında:
        // hedefi bul → mode'a göre offset hesapla
        // MovePlayer paketi gönder (teleport)
    }

    override fun onEnable()  {}
    override fun onDisable() {}
}
