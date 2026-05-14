package com.oxclient.ui.overlay

import androidx.compose.runtime.*

enum class ToastType { MODULE_TOGGLE, WARNING, INFO, ERROR }

data class ModuleToast(
    val moduleName : String,
    val enabled    : Boolean,
    val type       : ToastType = ToastType.MODULE_TOGGLE,
    val customText : String    = ""
)

object OverlayState {

    const val TOAST_DURATION_MS      = 1800L
    const val WARNING_DURATION_MS    = 2500L
    const val ERROR_DURATION_MS      = 3000L

    var isOverlayVisible by mutableStateOf(false)
        private set

    var isMenuOpen by mutableStateOf(false)
        private set

    var selfHealth    by mutableFloatStateOf(20f)
        private set
    var selfMaxHealth by mutableFloatStateOf(20f)
        private set
    var selfAbsorb    by mutableFloatStateOf(0f)
        private set
    var selfArmor     by mutableFloatStateOf(0f)
        private set
    var selfHunger    by mutableFloatStateOf(20f)
        private set

    var selfX by mutableFloatStateOf(0f)
        private set
    var selfY by mutableFloatStateOf(0f)
        private set
    var selfZ by mutableFloatStateOf(0f)
        private set

    var entityCount by mutableIntStateOf(0)
        private set
    var playerCount by mutableIntStateOf(0)
        private set
    var hostileCount by mutableIntStateOf(0)
        private set

    var espBlockCount by mutableIntStateOf(0)
        private set

    var packetCtoS by mutableLongStateOf(0L)
        private set
    var packetStoC by mutableLongStateOf(0L)
        private set

    var activeModuleCount by mutableIntStateOf(0)
        private set

    private val _toasts = mutableStateListOf<ModuleToast>()
    val toasts: List<ModuleToast> get() = _toasts

    internal fun setOverlayVisible(v: Boolean) { isOverlayVisible = v }
    internal fun setMenuOpen(v: Boolean)       { isMenuOpen = v }

    fun updateEntityStats(entities: Int, players: Int, hostiles: Int) {
        entityCount  = entities
        playerCount  = players
        hostileCount = hostiles
    }

    fun updateSelfStats(
        health: Float, maxHealth: Float,
        absorb: Float, armor: Float, hunger: Float
    ) {
        selfHealth    = health
        selfMaxHealth = maxHealth
        selfAbsorb    = absorb
        selfArmor     = armor
        selfHunger    = hunger
    }

    fun updatePosition(x: Float, y: Float, z: Float) {
        selfX = x; selfY = y; selfZ = z
    }

    fun updatePacketStats(cToS: Long, sToC: Long) {
        packetCtoS = cToS
        packetStoC = sToC
    }

    fun updateEspBlockCount(count: Int) { espBlockCount = count }

    fun updateActiveModuleCount(count: Int) { activeModuleCount = count }

    fun postModuleToast(toast: ModuleToast) {
        _toasts.removeAll { it.moduleName == toast.moduleName }
        _toasts.add(toast)
    }

    fun clearModuleToast(toast: ModuleToast) { _toasts.remove(toast) }

    fun clearAllToasts() { _toasts.clear() }

    fun postWarning(message: String) {
        val toast = ModuleToast(
            moduleName = "warning_${System.currentTimeMillis()}",
            enabled    = false,
            type       = ToastType.WARNING,
            customText = message
        )
        _toasts.add(toast)
    }

    fun postInfo(message: String) {
        val toast = ModuleToast(
            moduleName = "info_${System.currentTimeMillis()}",
            enabled    = true,
            type       = ToastType.INFO,
            customText = message
        )
        _toasts.add(toast)
    }

    val healthPercent: Float
        get() = if (selfMaxHealth > 0f) selfHealth / selfMaxHealth else 0f

    val isLowHealth: Boolean
        get() = selfHealth <= 6f && selfMaxHealth > 0f
}
