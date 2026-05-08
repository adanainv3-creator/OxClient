package com.oxclient.ui.overlay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.oxclient.module.ModuleManager

/**
 * OverlayState — Overlay UI'nın reaktif durumu
 */
object OverlayState {
    var isMenuOpen    by mutableStateOf(false)
    var isRelayActive by mutableStateOf(false)
    var connectedServer by mutableStateOf("—")
    var packetCount   by mutableStateOf(0L)
    var ping          by mutableStateOf(0)

    // Modül toggle UI state
    val killAuraEnabled  get() = ModuleManager.killAura.enabled
    val criticalsEnabled get() = ModuleManager.criticals.enabled
    val autoTotemEnabled get() = ModuleManager.autoTotem.enabled
    val tpAuraEnabled    get() = ModuleManager.tpAura.enabled
}
