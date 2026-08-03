package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.ModuleManager
import kotlin.math.min

/**
 * Sade/estetik varyant — önceki rainbow-gradient tasarım yerine, kategoriye
 * göre sabit, göz yormayan pastel bir vurgu rengi kullanılıyor. Rainbow
 * animasyonu ve LinearGradient/HSV hesaplaması tamamen kaldırıldı, bu da
 * kodu hem daha sade hem de daha performanslı yapıyor (her frame gradient/
 * renk yeniden hesaplamıyor).
 */
class ArrayListModule : BaseModule(
    name = "ArrayList",
    category = ModuleCategory.VISUAL,
    description = "Aktif modülleri sağ üst köşede sade bir liste halinde gösterir"
) {
    private val activationTimestamps = HashMap<String, Long>()
    private val slideProgress = HashMap<String, Float>()
    private var lastFrameTimeNs = 0L

    companion object {
        // Kategoriye göre sabit, pastel/desatüre vurgu renkleri — göz alıcı
        // parlak/rainbow tonlar yerine sakin, birbirinden ayırt edilebilir
        // ama baskın olmayan renkler.
        // NOT: ModuleCategory.SOCIAL ismi diğer kategorilerle (COMBAT/MOVEMENT/
        // VISUAL/MISC) aynı isimlendirme deseninden (module/social paketi ->
        // SOCIAL) çıkarıldı, doğrulanmadı. Derleme hatası alırsan bu satırı
        // gerçek enum sabitinin ismiyle güncelle ya da tamamen kaldır.
        private fun accentColorFor(category: ModuleCategory): Int = when (category) {
            ModuleCategory.COMBAT   -> Color.rgb(0xCB, 0x8A, 0x8E) // muted rose
            ModuleCategory.MOVEMENT -> Color.rgb(0x7E, 0x9C, 0xC2) // muted slate blue
            ModuleCategory.VISUAL   -> Color.rgb(0xA6, 0x98, 0xC9) // muted lavender
            ModuleCategory.SOCIAL   -> Color.rgb(0x74, 0xAD, 0xA3) // muted teal
            ModuleCategory.MISC     -> Color.rgb(0x9A, 0xA3, 0x9E) // muted sage
            else                    -> Color.rgb(0xB0, 0xB0, 0xB0) // fallback: nötr gri
        }
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f
        textAlign = Paint.Align.RIGHT
        color = Color.rgb(0xEC, 0xEC, 0xEC) // yumuşak off-white, saf beyaz değil
        setShadowLayer(1.6f, 0f, 1f, Color.argb(140, 0, 0, 0)) // hafif, göze batmayan gölge
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val reusableRect = RectF()

    private var cachedVersion = -1
    private var cachedSorted: List<BaseModule> = emptyList()
    private var cachedActiveNames: Set<String> = emptySet()

    override fun onEnable() {
        super.onEnable()
        activationTimestamps.clear()
        slideProgress.clear()
        lastFrameTimeNs = 0L
        cachedVersion = -1
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

        val version = ModuleManager.version.value
        if (version != cachedVersion) {
            cachedVersion = version
            val active = ModuleManager.modules.filter { it.isEnabled && it !== this }
            cachedSorted = active.sortedByDescending { activationTimestamps[it.name] ?: 0L }
            cachedActiveNames = cachedSorted.mapTo(HashSet()) { it.name }
        }
        val sorted = cachedSorted
        val activeNames = cachedActiveNames

        val nowNs = System.nanoTime()
        val dt = if (lastFrameTimeNs == 0L) 0.016f else ((nowNs - lastFrameTimeNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameTimeNs = nowNs
        val step = (0.8f * dt * 60f).coerceIn(0f, 1f)

        for (m in sorted) {
            val cur = slideProgress[m.name] ?: 0f
            slideProgress[m.name] = min(1f, cur + step)
        }

        val toRemove = ArrayList<String>()
        for (key in slideProgress.keys) {
            if (key !in activeNames) {
                val cur = slideProgress[key] ?: 0f
                val next = (cur - step).coerceAtLeast(0f)
                if (next <= 0f) toRemove.add(key) else slideProgress[key] = next
            }
        }
        if (toRemove.isNotEmpty()) toRemove.forEach { slideProgress.remove(it) }
        if (slideProgress.isEmpty()) return

        val fadingOut = slideProgress.keys.filter { it !in activeNames }
        val drawOrder: List<Pair<String, ModuleCategory>> =
            sorted.map { it.name to it.category } +
                fadingOut.mapNotNull { n -> ModuleManager.byName(n)?.let { n to it.category } }

        val fm = textPaint.fontMetrics
        val lineH = (fm.descent - fm.ascent) + 11f
        val rightX = screenW - 16f

        var y = 16f
        for ((name, category) in drawOrder) {
            val progress = slideProgress[name] ?: continue

            val textW = textPaint.measureText(name)
            val accentW = 3f
            val boxW = textW + 18f + accentW

            val slideOffset = (1f - progress) * (boxW + 24f)
            val boxRight = rightX + slideOffset
            val boxLeft = boxRight - boxW
            val boxTop = y
            val boxBottom = y + lineH - 6f

            val alpha = (255 * progress).toInt().coerceIn(0, 255)
            val accentColor = accentColorFor(category)

            // Arka plan: sade, yarı saydam koyu dikdörtgen — flashy hiçbir şey yok.
            reusableRect.set(boxLeft, boxTop, boxRight, boxBottom)
            bgPaint.color = Color.rgb(0x14, 0x14, 0x16)
            bgPaint.alpha = (95 * progress).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(reusableRect, 6f, 6f, bgPaint)

            // İnce, sabit renkli kategori şeridi (kalın/parlak değil).
            reusableRect.set(boxLeft, boxTop, boxLeft + 3f, boxBottom)
            accentPaint.color = accentColor
            accentPaint.alpha = alpha
            canvas.drawRoundRect(reusableRect, 1.5f, 1.5f, accentPaint)

            textPaint.alpha = alpha
            val textY = boxTop + 5f - fm.ascent
            canvas.drawText(name, boxRight - 9f, textY, textPaint)

            y += lineH
        }
    }
}
