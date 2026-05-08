package com.oxclient.module.movement

import com.oxclient.events.PacketEvent
import com.oxclient.module.*

class Jetpack : BaseModule(
    name        = "Jetpack",
    category    = ModuleCategory.MOVEMENT,
    description = "Paket tabanlı uçuş / jetpack hareketi"
) {

    enum class JetMode { Jetpack, Glide, YPort, Motion, Teleport, Jump }

    private val mode            = EnumSetting("Mode",           JetMode.Jetpack, JetMode.entries)
    private val verticalSpeed   = FloatSetting("Vertical Speed",  1.5f,  0.1f, 10f)
    private val horizontalSpeed = FloatSetting("Horizontal Speed",1.5f,  0.1f, 10f)
    private val add             = FloatSetting("Add",            -0.02f,-0.5f,  0.5f)
    private val pressJump       = BoolSetting("PressJump",       true)
    private val glideSpoof      = BoolSetting("GlideSpoof",      true)
    private val shortcut        = BoolSetting("Shortcut",        false)

    override fun registerSettings() = listOf(
        mode, verticalSpeed, horizontalSpeed, add,
        pressJump, glideSpoof, shortcut
    )

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Relay bağlandığında:
        // MovePlayer paketini intercept et
        // mode'a göre Y/XZ hızı ayarla
    }

    override fun onEnable()  {}
    override fun onDisable() {}
}
