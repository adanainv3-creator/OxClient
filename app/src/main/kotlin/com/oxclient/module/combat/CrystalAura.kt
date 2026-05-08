package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.module.*

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "End kristallerini otomatik yerleştirir ve patlatır"
) {
    enum class BreakMode { Instant, Sequential }
    enum class Priority { Distance, Health }

    // ── Ana kontroller ────────────────────────────────────────────────────
    private val autoPlace        = BoolSetting("AutoPlace",        true)
    private val autoBreak        = BoolSetting("AutoBreak",        true)
    private val breakMode        = EnumSetting("BreakMode",       BreakMode.Instant,   BreakMode.entries)
    private val priority         = EnumSetting("Priority",        Priority.Distance,   Priority.entries)

    // ── Menzil ────────────────────────────────────────────────────────────
    private val placeRange       = FloatSetting("PlaceRange",      6f,    1f, 12f)
    private val breakRange       = FloatSetting("BreakRange",      6f,    1f, 12f)
    private val wallsRange       = FloatSetting("WallsRange",      5f,    1f, 10f)

    // ── Hasar ─────────────────────────────────────────────────────────────
    private val antiSuicide      = BoolSetting("AntiSuicide",      true)

    // ── Duvar arkası ──────────────────────────────────────────────────────
    private val throughWalls     = BoolSetting("ThroughWalls",      true)
    private val throughBlocks    = BoolSetting("ThroughBlocks",     true)

    // ── Yerleştirme ───────────────────────────────────────────────────────
    private val placeDelay       = IntSetting("PlaceDelay",        0,    0, 500)
    private val breakDelay       = IntSetting("BreakDelay",        0,    0, 500)
    private val maxPlace         = IntSetting("MaxPlace",          25,   1, 50)
    private val maxBreak         = IntSetting("MaxBreak",          25,   1, 50)

    // ── Görsel ────────────────────────────────────────────────────────────
    private val removeParticles  = BoolSetting("RemoveParticles",  true)

    // ── Shortcut ──────────────────────────────────────────────────────────
    private val shortcut         = BoolSetting("Shortcut",         true)

    override fun registerSettings() = listOf(
        autoPlace, autoBreak,
        breakMode, priority,
        placeRange, breakRange, wallsRange,
        antiSuicide,
        throughWalls, throughBlocks,
        placeDelay, breakDelay,
        maxPlace, maxBreak,
        removeParticles,
        shortcut
    )

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        // Relay bağlandığında:
        // - throughWalls/throughBlocks: obsidyenin arkasına/icine kristal yerlestir
        // - maxPlace=25: hedefin etrafına aynı anda 25 kristal koy
        // - maxBreak=25: hepsini aynı anda patlat
        // - placeDelay/breakDelay=0: bekleme yok
        // - antiSuicide: kendini öldürecekse patlatma
    }

    override fun onEnable()  {}
    override fun onDisable() {}
}