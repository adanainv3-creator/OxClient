package com.oxclient.module.misc

import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory

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
