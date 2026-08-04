package com.rubidiumclient.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.rubidiumclient.module.KeybindManager

/**
 * The Accessibility Service Rubidium Client uses to capture keyboard/gamepad/
 * extra-mouse-button keybinds without taking focus away from Minecraft (i.e.
 * without stealing its input).
 *
 * WHY AN ACCESSIBILITY SERVICE:
 * OverlayService's windows are intentionally NOT focusable (FLAG_NOT_FOCUSABLE)
 * so Minecraft keeps receiving its own input. The downside is that a normal
 * overlay window can never receive hardware key events. An AccessibilityService
 * running with FLAG_REQUEST_FILTER_KEY_EVENTS, however, can see every KeyEvent
 * system-wide regardless of which window currently has focus.
 *
 * LIMITATION: This only works for inputs that produce a KeyEvent — physical
 * keyboard keys, gamepad buttons, and most gaming mice's extra (forward/back/
 * side) buttons. Standard left/right mouse clicks and pointer movement arrive
 * as MotionEvents, not KeyEvents; those can't be captured system-wide from
 * another app without root. So "mouse keybinds" only work for mice with extra
 * buttons.
 *
 * USER SETUP: This service must be enabled manually under Settings >
 * Accessibility (Android doesn't allow apps to enable it automatically).
 */
class KeybindAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeybindA11yService"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
        isRunning = true
        Log.i(TAG, "Keybind accessibility service connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only count the initial key-down, not repeats — otherwise the module
        // would toggle on every repeat event while the key is held down.
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return false

        return try {
            KeybindManager.dispatch(event.keyCode)
        } catch (e: Exception) {
            Log.e(TAG, "Keybind dispatch error", e)
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // This service only exists to capture key events; accessibility events are unused.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
