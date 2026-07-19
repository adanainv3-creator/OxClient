package com.oxclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.module.ModuleManager
import kotlin.math.min

class ArrayListModule : BaseModule(
    name = "Mod List",
    category = ModuleCategory.VISUAL,
    description = "Aktif modülleri sağ üst köşede renkli liste halinde gösterir"
) {
    private val activationTimestamps = HashMap<String, Long>()
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
        val sorted = active.sortedByDescending { activationTimestamps[it.name] ?: 0L }

        val nowNs = System.nanoTime()
        val dt = if (lastFrameTimeNs == 0L) 0.016f else ((nowNs - lastFrameTimeNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameTimeNs = nowNs
        val step = (0.8f * dt * 60f).coerceIn(0f, 1f)

        val activeNames = sorted.mapTo(HashSet()) { it.name }

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
        toRemove.forEach { slideProgress.remove(it) }
        if (slideProgress.isEmpty()) return

        val fadingOut = slideProgress.keys.filter { it !in activeNames }
        val drawOrder: List<Pair<String, ModuleCategory>> =
            sorted.map { it.name to it.category } +
                fadingOut.mapNotNull { n -> ModuleManager.byName(n)?.let { n to it.category } }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            textAlign = Paint.Align.RIGHT
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val fm = textPaint.fontMetrics
        val lineH = (fm.descent - fm.ascent) + 12f
        val rightX = screenW - 16f

        // Rainbow akış fazı: sürekli döner, cycleMs = bir tam turun süresi (ms).
        // Daha küçük değer = daha hızlı akış.
        val cycleMs = 1000f
        val basePhase = ((System.currentTimeMillis() % cycleMs) / cycleMs) * 360f
        val rainbowStops = 6
        val colorBuf = IntArray(rainbowStops)

        fun fillRainbow(phaseOffset: Float) {
            for (i in 0 until rainbowStops) {
                val hue = ((basePhase + phaseOffset + i * (360f / rainbowStops)) % 360f + 360f) % 360f
                colorBuf[i] = Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
            }
        }

        var y = 16f
        drawOrder.forEachIndexed { index, (name, _) ->
            val progress = slideProgress[name] ?: return@forEachIndexed

            val textW = textPaint.measureText(name)
            val accentW = 6f
            val boxW = textW + 20f + accentW

            val slideOffset = (1f - progress) * (boxW + 24f)
            val boxRight = rightX + slideOffset
            val boxLeft = boxRight - boxW
            val boxTop = y
            val boxBottom = y + lineH - 6f

            val alpha = (255 * progress).toInt().coerceIn(0, 255)

            // Her satır kendi faz ofsetiyle akıyor, böylece liste de kendi içinde
            // dalga gibi görünür (hepsi bire bir aynı anda değişmiyor).
            fillRainbow(index * -40f)

            bgPaint.color = Color.BLACK
            bgPaint.alpha = (110 * progress).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), 8f, 8f, bgPaint)

            accentPaint.shader = null
            accentPaint.color = colorBuf[0]
            accentPaint.alpha = alpha
            canvas.drawRoundRect(RectF(boxLeft, boxTop, boxLeft + 5f, boxBottom), 3f, 3f, accentPaint)

            val textLeft = boxRight - 10f - textW
            val textRight = boxRight - 10f
            textPaint.shader = LinearGradient(
                textLeft, 0f, textRight, 0f,
                colorBuf, null, Shader.TileMode.CLAMP
            )
            textPaint.alpha = alpha
            val textY = boxTop + 6f - fm.ascent
            canvas.drawText(name, boxRight - 10f, textY, textPaint)
            textPaint.shader = null

            y += lineH
        }
    }
}