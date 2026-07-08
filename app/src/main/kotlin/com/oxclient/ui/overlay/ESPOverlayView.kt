package com.oxclient.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.oxclient.module.ModuleManager
import com.oxclient.module.visual.ESP

/**
 * ESPOverlayView — ESP modülünün tracer/box/label/radar çizimlerini oyunun
 * ÜZERİNE basan, tam ekran, şeffaf, DOKUNULAMAZ (touch-through) Canvas view'ı.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * NEDEN BU DOSYA GEREKLİYDİ:
 * ESP.render(canvas, w, h) fonksiyonu tam ve doğru yazılmıştı ama OverlayService
 * içinde bunu çağıran hiçbir yer YOKTU — servis sadece Compose tabanlı FAB/menü/
 * shortcut view'larını WindowManager'a ekliyordu. Yani BlockTracker veri
 * topluyordu, ESP renderList'i dolduruyordu, ama hiçbir View bu veriyi
 * gerçek bir Canvas'a çizip ekrana basmıyordu. Bu view tam olarak o eksik
 * halkayı tamamlıyor.
 *
 * touch-through: WindowManager tarafında FLAG_NOT_TOUCHABLE ile eklenmesi
 * gerekiyor (bkz. OverlayService.overlayParams(touchable = false)) — aksi
 * halde tam ekranı kaplayan bu view, altındaki Minecraft istemcisine giden
 * tüm dokunuşları yutar.
 * ═══════════════════════════════════════════════════════════════════════
 */
class ESPOverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "ESPOverlayView"
    }

    init {
        // Sık invalidate edilen bir çizim yüzeyi için donanım katmanı,
        // yazılım katmanına göre belirgin şekilde daha az frame düşürüyor.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // Modül referansını her seferinde ModuleManager'dan tazeler — ESP instance'ı
    // OxClientApp.registerModules() içinde bir kere yaratılıp her zaman aynı
    // kalır, bu yüzden burada cache'lemek de güvenli olurdu ama byName() lookup'ı
    // ihmal edilebilir maliyette ve modül yeniden yaratılırsa (örn. test) hata
    // vermeyi engelliyor.
    private val espModule: ESP?
        get() = ModuleManager.byName("ESP") as? ESP

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val esp = espModule
        if (esp != null && esp.isEnabled) {
            try {
                esp.render(canvas, width, height)
            } catch (e: Exception) {
                // ESP render hatası servisi/relay'i asla düşürmemeli — sessizce logla,
                // bir sonraki frame'de tekrar denenir.
                OverlayLogger.v(TAG, "Render hatası: ${e.message}")
            }
        }

        // Sürekli render loop: ESP kapalıyken de düşük maliyetli invalidate
        // döngüsü devam eder, kullanıcı modülü açtığı an ekstra gecikme olmaz.
        postInvalidateOnAnimation()
    }

    /** Servis view'ı WindowManager'a eklerken ilk çizim döngüsünü başlatmak için çağırır. */
    fun startRenderLoop() {
        postInvalidateOnAnimation()
    }
}
