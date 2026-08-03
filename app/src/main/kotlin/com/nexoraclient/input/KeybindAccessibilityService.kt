package com.rubidiumclient.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.rubidiumclient.module.KeybindManager

/**
 * Rubidium Client'ın klavye/gamepad/ekstra-fare-tuşu keybind'larını,
 * Minecraft'a odak vermeden (yani onun input'unu çalmadan) yakalayabilmesi
 * için kullanılan Accessibility Service.
 *
 * NEDEN ACCESSIBILITY SERVICE:
 * OverlayService'in pencereleri kasıtlı olarak odaklanabilir DEĞİL (FLAG_NOT_FOCUSABLE) —
 * böylece Minecraft kendi input'unu alabiliyor. Ama bunun sonucu olarak normal
 * bir overlay penceresi hiçbir zaman hardware tuş event'i alamaz.
 * FLAG_REQUEST_FILTER_KEY_EVENTS izniyle çalışan bir AccessibilityService ise,
 * hangi pencere fokuslu olursa olsun tüm KeyEvent'leri sistem seviyesinde görebiliyor.
 *
 * KISITLAMA: Bu yalnızca KeyEvent üreten girdiler için çalışır — fiziksel klavye
 * tuşları, gamepad düğmeleri, çoğu oyuncu faresinin ekstra (ileri/geri/yan) tuşları.
 * Standart sol/sağ mouse tıklaması ve imleç hareketi KeyEvent DEĞİL MotionEvent
 * olarak gelir; bunlar root olmadan başka bir uygulamadan sistem genelinde
 * yakalanamaz. Yani "mouse keybind" sadece ekstra düğmeli farelerde çalışır.
 *
 * KULLANICI KURULUMU: Bu servis Ayarlar > Erişilebilirlik'ten elle
 * etkinleştirilmelidir (Android bunu otomatik açmaya izin vermiyor).
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
        Log.i(TAG, "Keybind accessibility service bağlandı")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Sadece basma anını say, basılı tutmayı (repeat) sayma — yoksa modül
        // her tekrar event'inde toggle olur.
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return false

        return try {
            KeybindManager.dispatch(event.keyCode)
        } catch (e: Exception) {
            Log.e(TAG, "Keybind dispatch hatası", e)
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Bu servis sadece tuş yakalamak için var, accessibility event'leriyle ilgilenmiyor.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
