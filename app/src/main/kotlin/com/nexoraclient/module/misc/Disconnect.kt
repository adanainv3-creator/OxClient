package com.rubidiumclient.module.misc

import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory

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
