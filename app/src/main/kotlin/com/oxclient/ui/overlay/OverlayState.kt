package com.oxclient.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ModuleToast(val moduleName: String, val enabled: Boolean)

/**
 * Overlay katmanının global UI durumu.
 * OverlayService, OverlayNotifier ve DashboardActivity bu state'i okur/yazar.
 */
object OverlayState {

    const val TOAST_DURATION_MS = 1500L

    // ── Overlay görünürlüğü ───────────────────────────────────────────────────

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    fun setOverlayVisible(visible: Boolean) { _overlayVisible.value = visible }

    // ── Menü açık/kapalı ─────────────────────────────────────────────────────

    private val _menuOpen = MutableStateFlow(false)
    val menuOpen: StateFlow<Boolean> = _menuOpen.asStateFlow()

    fun setMenuOpen(open: Boolean) { _menuOpen.value = open }

    // ── Modül toast bildirimleri ──────────────────────────────────────────────

    private val _activeToasts = MutableStateFlow<List<ModuleToast>>(emptyList())
    val activeToasts: StateFlow<List<ModuleToast>> = _activeToasts.asStateFlow()

    fun postModuleToast(toast: ModuleToast) {
        _activeToasts.value = _activeToasts.value + toast
    }

    fun clearModuleToast(toast: ModuleToast) {
        _activeToasts.value = _activeToasts.value - toast
    }
}
