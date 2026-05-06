package com.oxclient.ui.overlay

import kotlinx.coroutines.*

object OverlayNotifier {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun showModuleToast(moduleName: String, enabled: Boolean) {
        val toast = ModuleToast(moduleName, enabled)
        OverlayState.postModuleToast(toast)
        scope.launch {
            delay(OverlayState.TOAST_DURATION_MS)
            OverlayState.clearModuleToast(toast)
        }
    }
}
