package com.oxclient.module.visual

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.module.*

// ── ESP ───────────────────────────────────────────────────────────────────────

class ESP : BaseModule(
    name        = "ESP",
    category    = ModuleCategory.VISUAL,
    description = "Varlıkları duvarların arkasında gösterir"
) {
    enum class EspMode { BOX, OUTLINE, HEALTH_BAR }

    private val mode      = EnumSetting("Mod",       EspMode.BOX,   EspMode.entries)
    private val players   = BoolSetting("Oyuncular", true)
    private val mobs      = BoolSetting("Canavarlar",false)
    private val range     = FloatSetting("Menzil",   default = 64f, min = 10f, max = 128f)

    override fun registerSettings() = listOf(mode, players, mobs, range)

    override fun onEnable()  { Log.d("ESP", "Aktif — mod: ${mode.value}") }
    override fun onDisable() { Log.d("ESP", "Devre dışı") }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Gerçek implementasyon: AddPlayerPacket/AddEntityPacket → overlay'de highlight
    }
}

// ── FullBright ────────────────────────────────────────────────────────────────

class FullBright : BaseModule(
    name        = "FullBright",
    category    = ModuleCategory.VISUAL,
    description = "Karanlık yerlerde tam parlaklık"
) {
    private val nightVision = BoolSetting("Gece Görüşü", true)
    override fun registerSettings() = listOf(nightVision)

    override fun onEnable()  { Log.d("FullBright", "Aktif") }
    override fun onDisable() { Log.d("FullBright", "Devre dışı") }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Gerçek implementasyon: MobEffectPacket inject → night_vision sonsuz
    }
}
