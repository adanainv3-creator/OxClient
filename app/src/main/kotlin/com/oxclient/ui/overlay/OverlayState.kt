package com.oxclient.ui.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ModuleToast(val moduleName: String, val enabled: Boolean)

/**
 * OverlayState
 *
 * Overlay görünürlüğü ve modül toast bildirimlerini tutan singleton.
 * Compose state ile reaktif güncelleme sağlar.
 */
object OverlayState {

    const val TOAST_DURATION_MS = 1800L

    var isOverlayVisible by mutableStateOf(false)
        private set

    var isMenuOpen by mutableStateOf(false)
        private set

    private val _toasts = mutableStateListOf<ModuleToast>()
    val toasts: List<ModuleToast> get() = _toasts

    internal fun setOverlayVisible(v: Boolean) { isOverlayVisible = v }
    internal fun setMenuOpen(v: Boolean)       { isMenuOpen = v }

    fun postModuleToast(toast: ModuleToast) {
        _toasts.removeAll { it.moduleName == toast.moduleName }
        _toasts.add(toast)
    }

    fun clearModuleToast(toast: ModuleToast) {
        _toasts.remove(toast)
    }
}
