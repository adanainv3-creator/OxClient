package com.oxclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.module.ModuleManager
import kotlin.math.min

/**
 * Ekranın sağ üst köşesinde etkin modülleri renkli, kayarak açılıp kapanan
 * satırlar halinde gösteren klasik "ArrayList" HUD'u.
 *
 * Diğer görsel modüller (bkz. ESP) gibi kendi state'ini tutmuyor; her frame'de
 * ModuleManager.modules üzerinden hangi modüllerin isEnabled olduğuna bakıp
 * listeyi yeniden kuruyor. Overlay render döngüsü tarafından her frame
 * render(canvas, screenW, screenH) ile çağrılması yeterli.
 */
class ArrayListModule : BaseModule(
    name        = "Mod List",
    category    = ModuleCategory.VISUAL,
    description = "Aktif modülleri sağ üst köşede renkli bir liste halinde gösterir"
) {
    enum class SortMode { Name, Category, ActivationTime }

    private val sortMode       = enum ("Sort Mode",        SortMode.ActivationTime)
    private val textSize       = float("Text Size",        30f,  16f, 48f)
    private val rowSpacing     = float("Row Spacing",      6f,   0f,  20f)
    private val rightMargin    = float("Right Margin",     16f,  0f,  100f)
    private val topMargin      = float("Top Margin",       16f,  0f,  200f)
    private val paddingX       = float("Padding X",        10f,  0f,  40f)
    private val paddingY       = float("Padding Y",        6f,   0f,  30f)
    private val rainbowSpeed   = float("Rainbow Speed",    1f,   0f,  5f)
    private val hueSpread      = float("Hue Spread",       28f,  0f,  90f)
    private val saturation     = float("Saturation",       0.75f,0f,  1f)
    private val brightness     = float("Brightness",       1f,   0.3f,1f)
    private val bgAlpha        = int  ("Background Alpha", 110,  0,   255)
    private val showBackground = bool ("Background",       true)
    private val showAccentBar  = bool ("Accent Bar",       true)
    private val animate        = bool ("Slide Animation",  true)
    private val animSpeed      = float("Anim Speed",       0.2f, 0.02f,1f)
    private val shortcut       = bool ("Shortcut",         false)

    // Modülün en son ne zaman etkinleştirildiğini tutar (ActivationTime sıralaması için)
    private val activationTimestamps = HashMap<String, Long>()
    // Her satırın kayma/fade ilerlemesi: 0f = tamamen gizli/dışarıda, 1f = tam görünür
    private val slideProgress = HashMap<String, Float>()

    private var lastFrameTimeNs = 0L

    override fun onEnable() {
        super.onEnable()
        activationTimestamps.clear()
        slideProgress.clear()
        lastFrameTimeNs = 0L
    }

    private fun trackActivations() {
        val now = System.currentTimeMillis()
        for (m in ModuleManager.modules) {
            if (m === this) continue
            if (m.isEnabled) {
                activationTimestamps.putIfAbsent(m.name, now)
            } else {
                activationTimestamps.remove(m.name)
            }
        }
    }

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return
        trackActivations()

        val active = ModuleManager.modules.filter { it.isEnabled && it !== this }

        // Sıralama: liste sağ üstten aşağı doğru büyür
        val sorted = when (sortMode.value) {
            SortMode.Name           -> active.sortedByDescending { it.name.length }
            SortMode.Category       -> active.sortedWith(compareBy({ it.category.ordinal }, { it.name }))
            SortMode.ActivationTime -> active.sortedByDescending { activationTimestamps[it.name] ?: 0L }
        }

        // Frame'den bağımsız, tutarlı animasyon hızı için delta-time
        val nowNs = System.nanoTime()
        val dt = if (lastFrameTimeNs == 0L) 0.016f else ((nowNs - lastFrameTimeNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameTimeNs = nowNs
        val step = if (animate.value) (animSpeed.value * dt * 60f).coerceIn(0f, 1f) else 1f

        val activeNames = sorted.mapTo(HashSet()) { it.name }

        // Yeni etkinleşen modüller için ilerlemeyi başlat / artır
        for (m in sorted) {
            val cur = slideProgress[m.name] ?: 0f
            slideProgress[m.name] = if (animate.value) min(1f, cur + step) else 1f
        }
        // Kapatılmış modüller: ilerlemeyi 0'a doğru azalt, 0'a inince tamamen unut
        // (bu sayede kapanan modül sağa doğru kayarak kaybolur)
        val toRemove = ArrayList<String>()
        for (key in slideProgress.keys) {
            if (key !in activeNames) {
                val cur = slideProgress[key] ?: 0f
                val next = (cur - step).coerceAtLeast(0f)
                if (next <= 0f) toRemove.add(key) else slideProgress[key] = next
            }
        }
        toRemove.forEach { slideProgress.remove(it) }
        if (slideProgress.isEmpty()) return

        // Çizim sırası: önce hâlâ etkin olan modüller (sıralı), sonra kaybolmakta olan eskiler
        val fadingOut = slideProgress.keys.filter { it !in activeNames }
        val drawOrder: List<Pair<String, ModuleCategory>> =
            sorted.map { it.name to it.category } +
                fadingOut.mapNotNull { n -> ModuleManager.byName(n)?.let { n to it.category } }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize  = this@ArrayListModule.textSize.value
            textAlign = Paint.Align.RIGHT
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val fm = textPaint.fontMetrics
        val lineH = (fm.descent - fm.ascent) + paddingY.value * 2 + rowSpacing.value
        val rightX = screenW - rightMargin.value
        val timeHue = (System.currentTimeMillis() / 16.6 * rainbowSpeed.value).toFloat()

        var y = topMargin.value
        drawOrder.forEachIndexed { index, (name, _) ->
            val progress = slideProgress[name] ?: return@forEachIndexed
            val hue = ((timeHue + index * hueSpread.value) % 360f + 360f) % 360f
            val color = Color.HSVToColor(floatArrayOf(hue, saturation.value, brightness.value))

            val textW = textPaint.measureText(name)
            val accentW = if (showAccentBar.value) 6f else 0f
            val boxW = textW + paddingX.value * 2 + accentW

            // Satır sağdan (ekran dışından) kayarak içeri girer / dışarı çıkar
            val slideOffset = (1f - progress) * (boxW + 24f)
            val boxRight = rightX + slideOffset
            val boxLeft = boxRight - boxW
            val boxTop = y
            val boxBottom = y + lineH - rowSpacing.value

            val alpha = (255 * progress).toInt().coerceIn(0, 255)

            if (showBackground.value) {
                bgPaint.color = Color.BLACK
                bgPaint.alpha = (bgAlpha.value * progress).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), 8f, 8f, bgPaint)
            }

            if (showAccentBar.value) {
                accentPaint.color = color
                accentPaint.alpha = alpha
                canvas.drawRoundRect(RectF(boxLeft, boxTop, boxLeft + 5f, boxBottom), 3f, 3f, accentPaint)
            }

            textPaint.color = color
            textPaint.alpha = alpha
            val textY = boxTop + paddingY.value - fm.ascent
            canvas.drawText(name, boxRight - paddingX.value, textY, textPaint)

            y += lineH
        }
    }
}
