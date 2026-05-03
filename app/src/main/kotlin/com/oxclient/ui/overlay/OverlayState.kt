package com.oxclient.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reactive state for the in-game HUD overlay. */
object OverlayState {
    const val TOTEM_SYMBOL      = "⊕"
    const val TOAST_DURATION_MS = 2500L

    // Totem counter (top-left)
    private val _totemCount = MutableStateFlow(0)
    val totemCount: StateFlow<Int> = _totemCount.asStateFlow()
    fun updateTotemCount(n: Int) { _totemCount.value = n }

    // Module toast (top-center animated banner)
    private val _toast = MutableStateFlow<ModuleToast?>(null)
    val moduleToast: StateFlow<ModuleToast?> = _toast.asStateFlow()
    fun postModuleToast(t: ModuleToast) { _toast.value = t }
    fun clearModuleToast(t: ModuleToast) { if (_toast.value == t) _toast.value = null }

    // Overlay visibility
    private val _visible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _visible.asStateFlow()
    fun setOverlayVisible(v: Boolean) { _visible.value = v }

    // Hile menüsü açık/kapalı
    private val _menuOpen = MutableStateFlow(false)
    val menuOpen: StateFlow<Boolean> = _menuOpen.asStateFlow()
    fun setMenuOpen(open: Boolean) { _menuOpen.value = open }
}

data class ModuleToast(val moduleName: String, val enabled: Boolean) {
    val displayText: String
        get() = "OxClient | $moduleName ${if (enabled) "Activated" else "Deactivated"}"
}
