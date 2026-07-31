package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.ModuleManager
import kotlin.math.min

class ArrayListModule : BaseModule(
    name = "ArrayList",
    category = ModuleCategory.VISUAL,
    description = "Aktif modülleri sağ üst köşede renkli liste halinde gösterir"
) {
    private val activationTimestamps = HashMap<String, Long>()
    private val slideProgress = HashMap<String, Float>()
    private var lastFrameTimeNs = 0L

    companion object {
        private const val RAINBOW_STOPS = 6
        private const val GRADIENT_BASE_WIDTH = 200f
        private const val RAINBOW_INTERVAL_MS = 33L
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        textAlign = Paint.Align.RIGHT
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val reusableRect = RectF()
    private val gradientMatrix = Matrix()
    private val hsvBuf = FloatArray(3).apply { this[1] = 0.85f; this[2] = 1f }

    private val colorCache = HashMap<String, IntArray>()
    private val gradientCache = HashMap<String, LinearGradient>()
    private var lastRainbowUpdateMs = 0L

    private var cachedVersion = -1
    private var cachedSorted: List<BaseModule> = emptyList()
    private var cachedActiveNames: Set<String> = emptySet()

    override fun onEnable() {
        super.onEnable()
        activationTimestamps.clear()
        slideProgress.clear()
        colorCache.clear()
        gradientCache.clear()
        lastFrameTimeNs = 0L
        lastRainbowUpdateMs = 0L
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
        if (toRemove.isNotEmpty()) {
            toRemove.forEach {
                slideProgress.remove(it)
                colorCache.remove(it)
                gradientCache.remove(it)
            }
        }
        if (slideProgress.isEmpty()) return

        val fadingOut = slideProgress.keys.filter { it !in activeNames }
        val drawOrder: List<Pair<String, ModuleCategory>> =
            sorted.map { it.name to it.category } +
                fadingOut.mapNotNull { n -> ModuleManager.byName(n)?.let { n to it.category } }

        val fm = textPaint.fontMetrics
        val lineH = (fm.descent - fm.ascent) + 12f
        val rightX = screenW - 16f

        val cycleMs = 1000f
        val nowMs = System.currentTimeMillis()
        val basePhase = ((nowMs % cycleMs) / cycleMs) * 360f

        val refreshRainbow = nowMs - lastRainbowUpdateMs >= RAINBOW_INTERVAL_MS
        if (refreshRainbow) lastRainbowUpdateMs = nowMs

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

            var colors = colorCache[name]
            if (refreshRainbow || colors == null) {
                val arr = colors ?: IntArray(RAINBOW_STOPS).also { colorCache[name] = it }
                for (i in 0 until RAINBOW_STOPS) {
                    val hue = ((basePhase + index * -40f + i * (360f / RAINBOW_STOPS)) % 360f + 360f) % 360f
                    hsvBuf[0] = hue
                    arr[i] = Color.HSVToColor(hsvBuf)
                }
                colors = arr
                gradientCache[name] = LinearGradient(
                    0f, 0f, GRADIENT_BASE_WIDTH, 0f, arr, null, Shader.TileMode.CLAMP
                )
            }
            val gradient = gradientCache[name] ?: LinearGradient(
                0f, 0f, GRADIENT_BASE_WIDTH, 0f, colors, null, Shader.TileMode.CLAMP
            ).also { gradientCache[name] = it }

            reusableRect.set(boxLeft, boxTop, boxRight, boxBottom)
            bgPaint.shader = null
            bgPaint.color = Color.BLACK
            bgPaint.alpha = (110 * progress).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(reusableRect, 8f, 8f, bgPaint)

            reusableRect.set(boxLeft, boxTop, boxLeft + 5f, boxBottom)
            accentPaint.shader = null
            accentPaint.color = colors[0]
            accentPaint.alpha = alpha
            canvas.drawRoundRect(reusableRect, 3f, 3f, accentPaint)

            val textLeft = boxRight - 10f - textW
            val scaleX = if (textW > 0f) textW / GRADIENT_BASE_WIDTH else 1f
            gradientMatrix.setScale(scaleX, 1f)
            gradientMatrix.postTranslate(textLeft, 0f)
            gradient.setLocalMatrix(gradientMatrix)

            textPaint.shader = gradient
            textPaint.alpha = alpha
            val textY = boxTop + 6f - fm.ascent
            canvas.drawText(name, boxRight - 10f, textY, textPaint)

            y += lineH
        }
    }
}
