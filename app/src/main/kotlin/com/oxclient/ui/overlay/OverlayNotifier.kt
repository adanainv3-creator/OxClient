package com.oxclient.ui.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object OverlayNotifier {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun showModuleToast(moduleName: String, enabled: Boolean) {
        val toast = ModuleToast(
            moduleName = moduleName,
            enabled    = enabled,
            type       = ToastType.MODULE_TOGGLE
        )
        OverlayState.postModuleToast(toast)
        scope.launch {
            delay(OverlayState.TOAST_DURATION_MS)
            OverlayState.clearModuleToast(toast)
        }
    }

    fun showWarning(message: String) {
        val toast = ModuleToast(
            moduleName = "warn_${System.currentTimeMillis()}",
            enabled    = false,
            type       = ToastType.WARNING,
            customText = message
        )
        OverlayState.postModuleToast(toast)
        scope.launch {
            delay(OverlayState.WARNING_DURATION_MS)
            OverlayState.clearModuleToast(toast)
        }
    }

    fun showInfo(message: String) {
        val toast = ModuleToast(
            moduleName = "info_${System.currentTimeMillis()}",
            enabled    = true,
            type       = ToastType.INFO,
            customText = message
        )
        OverlayState.postModuleToast(toast)
        scope.launch {
            delay(OverlayState.TOAST_DURATION_MS)
            OverlayState.clearModuleToast(toast)
        }
    }

    fun showError(message: String) {
        val toast = ModuleToast(
            moduleName = "err_${System.currentTimeMillis()}",
            enabled    = false,
            type       = ToastType.ERROR,
            customText = message
        )
        OverlayState.postModuleToast(toast)
        scope.launch {
            delay(OverlayState.ERROR_DURATION_MS)
            OverlayState.clearModuleToast(toast)
        }
    }

    fun notifyLowHealth(health: Float) {
        showWarning("Dusuk can: ${"%.1f".format(health)}")
    }

    fun notifyModuleError(moduleName: String, reason: String) {
        showError("$moduleName hata: $reason")
    }

    fun notifyRelay(connected: Boolean, host: String = "") {
        if (connected) showInfo("Relay baglandi: $host")
        else           showWarning("Relay baglantisi kesildi")
    }
}
