package com.nexoraclient.module.misc

import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.BaseModule
import com.nexoraclient.module.ModuleCategory

class Disconnect : BaseModule(
    name        = "Disconnect",
    category    = ModuleCategory.MISC,
    description = "Sunucudan bağlantıyı keser"
) {
    private val shortcut = bool("Shortcut", false)

    override fun onEnable() {
        PacketEventBus.currentSession?.disconnect("OxClient: Disconnect")
        setEnabled(false)
    }
}
