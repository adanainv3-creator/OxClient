package com.rubidiumclient.module.misc

import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory

/**
 * Performance — overlay render loop'unun ne kadar agresif çalışacağını
 * kontrol eden ayarlar. Bu modülün kendisi ekrana hiçbir şey çizmiyor,
 * sadece OverlayService/ESPOverlayView'in okuyacağı sınırları tutuyor.
 * Diğer tüm modüller gibi otomatik olarak Config'in profil sisteminden
 * kaydedilip/yüklenir (Config zaten her BaseModule'ü generic serialize ediyor).
 *
 * NOT: Bu ayarların gerçek etkisi olması için ESPOverlayView/OverlayService'in
 * bunları okuyup kullanması gerekiyor — o dosyaları görmedim, bu yüzden şu an
 * sadece ayarların kendisini tanımlıyorum. Wiring için ESPOverlayView.kt'yi
 * atarsan hemen bağlarım.
 */
class Performance : BaseModule(
    name        = "Performance",
    category    = ModuleCategory.MISC,
    description = "Overlay'in fps tavanı ve boşta render etmeme ayarları — cihazı gereksiz yormasın diye"
) {
    // Overlay view'in saniyede en fazla kaç kez yeniden çizileceği. Gerçek
    // ihtiyaç genelde ESP/ArrayList gibi elemanların göz ile takip edilebilir
    // olması için 30-60 arası yeterli — cihazın gerçek ekran yenileme hızına
    // (90/120hz) kilitlenip gereksiz yere her frame çizmek pil/performans
    // israfı.
    val overlayFpsCap = int("Overlay FPS Cap", 60, 15, 144)

    // Hiçbir overlay-çizen modül (ESP/EnemyESP/Xray/ArrayList vb.) aktif
    // değilken, overlay view'in invalidate döngüsünü tamamen durdurup sadece
    // bir modül tekrar açıldığında uyanmasını sağlar. Bu, sürekli boşta
    // frame üretmenin önüne geçen asıl optimizasyon.
    val pauseWhenIdle = bool("Pause When Idle", true)

    // Overlay her zaman LAYER_TYPE_HARDWARE kullanıyor olabilir; bazı düşük
    // uçlu cihazlarda software layer daha az bellek/GPU sync overhead'i
    // yaratabilir. Varsayılan false (mevcut davranış korunuyor), sadece
    // gerçekten fps sorunu yaşayan kullanıcı için bir seçenek.
    val forceSoftwareLayer = bool("Force Software Layer", false)
}
