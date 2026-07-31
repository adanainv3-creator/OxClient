package com.rubidiumclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.OreTracker
import com.rubidiumclient.utils.OreTracker.TrackedOreType
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class Xray : BaseModule(
    name        = "Xray",
    category    = ModuleCategory.VISUAL,
    description = "Yakındaki değerli cevherleri duvarların ardından gösterir"
) {
    enum class RenderMode { Tracer, Box, Both }

    private val renderMode    = enum ("Render Mode",  RenderMode.Box)
    private val scanRange     = float("Scan Range",   48f,  8f, 128f)
    private val maxDisplay    = int  ("Max Display",  200,  10, 1000)
    private val tracerWidth   = float("Tracer Width", 2f,   0.5f, 8f)
    private val boxAlpha      = int  ("Box Alpha",    50,   10,  200)
    private val showLabels    = bool ("Show Labels",  true)
    private val showDistance  = bool ("Show Distance",true)

    private val showDiamond   = bool ("Diamond",       true)
    private val showDebris    = bool ("Ancient Debris",true)
    private val showEmerald   = bool ("Emerald",       true)
    private val showGold      = bool ("Gold",          true)
    private val showIron      = bool ("Iron",          false)
    private val showRedstone  = bool ("Redstone",      false)
    private val showLapis     = bool ("Lapis",         false)
    private val showCoal      = bool ("Coal",          false)
    private val showCopper    = bool ("Copper",        false)
    private val showQuartz    = bool ("Quartz",        false)
    private val showAmethyst  = bool ("Amethyst",      false)

    private val fadeByDistance= bool ("Distance Fade", true)
    private val minAlpha      = int  ("Min Alpha",     40,   0,   255)
    private val smoothTracer  = bool ("Smooth Tracer", true)
    private val smoothFactor  = float("Smooth Factor", 0.35f,0.05f, 1f)
    private val updateRateMs  = int  ("Update Rate (ms)", 200, 50, 1000)
    private val shortcut      = bool ("Shortcut",      false)

    private val renderList = CopyOnWriteArrayList<RenderEntry>()
    private var updateJob: Job? = null
    private val smoothedScreenPos = ConcurrentHashMap<Long, FloatArray>()

    data class RenderEntry(
        val x: Float, val y: Float, val z: Float,
        val type: TrackedOreType,
        val distance: Float,
        val key: Long
    )

    override fun onEnable() {
        super.onEnable()
        smoothedScreenPos.clear()
        updateJob = launchTickLoop(updateRateMs.value.toLong()) { updateTick() }
    }

    override fun onDisable() {
        updateJob?.cancel()
        super.onDisable()
        renderList.clear()
        smoothedScreenPos.clear()
    }

    private fun updateTick() {
        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ
        val range = scanRange.value

        val entries = OreTracker.getAllInRange(cx, cy, cz, range)
            .filter { isTypeEnabled(it.type) }
            .map { o ->
                RenderEntry(
                    o.pos.x + 0.5f, o.pos.y + 0.5f, o.pos.z + 0.5f,
                    o.type,
                    MathUtil.dist3(o.pos.x + 0.5f, o.pos.y + 0.5f, o.pos.z + 0.5f, cx, cy, cz),
                    OreTracker.packKey(o.pos.x, o.pos.y, o.pos.z)
                )
            }
            .sortedBy { it.distance }
            .take(maxDisplay.value)

        renderList.clear()
        renderList.addAll(entries)
        val activeKeys = entries.mapTo(HashSet()) { it.key }
        smoothedScreenPos.keys.retainAll(activeKeys)
    }

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return
        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ
        val yaw   = EntityTracker.selfYaw
        val pitch = EntityTracker.selfPitch

        val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeCap   = Paint.Cap.ROUND
            strokeWidth = tracerWidth.value
        }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style     = Paint.Style.FILL
            color     = Color.WHITE
            textSize  = 26f
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val centerX = screenW / 2f
        val centerY = screenH / 2f
        val maxRange = scanRange.value

        for (entry in renderList) {
            val color = entry.type.colorArgb
            val rawPos = MathUtil.worldToScreen(
                entry.x, entry.y, entry.z, cx, cy, cz, yaw, pitch, screenW, screenH
            )
            if (rawPos == null) continue

            val alphaScale = if (fadeByDistance.value) {
                val t = (1f - (entry.distance / maxRange)).coerceIn(0f, 1f)
                (minAlpha.value + (255 - minAlpha.value) * t) / 255f
            } else 1f

            val screenPos = if (smoothTracer.value) smooth(entry.key, rawPos.first, rawPos.second) else rawPos
            val deltaX = screenPos.first  - rawPos.first
            val deltaY = screenPos.second - rawPos.second

            when (renderMode.value) {
                RenderMode.Tracer, RenderMode.Both -> {
                    tracerPaint.color = color
                    tracerPaint.alpha = (150 * alphaScale).toInt().coerceIn(0, 255)
                    canvas.drawLine(centerX, centerY, screenPos.first, screenPos.second, tracerPaint)
                }
                else -> {}
            }

            when (renderMode.value) {
                RenderMode.Box, RenderMode.Both -> {
                    drawBox3D(
                        canvas, entry.x, entry.y, entry.z, cx, cy, cz, yaw, pitch,
                        screenW, screenH, deltaX, deltaY, color, boxPaint, fillPaint, alphaScale
                    )
                }
                else -> {}
            }

            if (showLabels.value) {
                var label = entry.type.displayName
                if (showDistance.value) label += " §${entry.distance.toInt()}m"
                textPaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                canvas.drawText(label, screenPos.first, screenPos.second - 26f, textPaint)
            }
        }
    }

    private fun smooth(key: Long, x: Float, y: Float): Pair<Float, Float> {
        val f = smoothFactor.value
        val prev = smoothedScreenPos[key]
        return if (prev == null) {
            smoothedScreenPos[key] = floatArrayOf(x, y)
            Pair(x, y)
        } else {
            val nx = prev[0] + (x - prev[0]) * f
            val ny = prev[1] + (y - prev[1]) * f
            smoothedScreenPos[key] = floatArrayOf(nx, ny)
            Pair(nx, ny)
        }
    }

    private fun drawBox3D(
        canvas: Canvas,
        wx: Float, wy: Float, wz: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        deltaX: Float, deltaY: Float,
        colorArgb: Int,
        strokePaint: Paint,
        fillPaint: Paint,
        alphaScale: Float
    ) {
        val half = 0.5f
        val worldCorners = arrayOf(
            floatArrayOf(wx - half, wy - half, wz - half),
            floatArrayOf(wx + half, wy - half, wz - half),
            floatArrayOf(wx + half, wy - half, wz + half),
            floatArrayOf(wx - half, wy - half, wz + half),
            floatArrayOf(wx - half, wy + half, wz - half),
            floatArrayOf(wx + half, wy + half, wz - half),
            floatArrayOf(wx + half, wy + half, wz + half),
            floatArrayOf(wx - half, wy + half, wz + half)
        )

        val screenCorners = arrayOfNulls<FloatArray>(8)
        for (i in worldCorners.indices) {
            val c = worldCorners[i]
            val p = MathUtil.worldToScreen(
                c[0], c[1], c[2], selfX, selfY, selfZ, yaw, pitch, screenW, screenH
            ) ?: return
            screenCorners[i] = floatArrayOf(p.first + deltaX, p.second + deltaY)
        }

        strokePaint.color = colorArgb
        strokePaint.alpha = (170 * alphaScale).toInt().coerceIn(0, 255)
        strokePaint.strokeWidth = tracerWidth.value

        val edges = intArrayOf(
            0, 1,  1, 2,  2, 3,  3, 0,
            4, 5,  5, 6,  6, 7,  7, 4,
            0, 4,  1, 5,  2, 6,  3, 7
        )
        var i = 0
        while (i < edges.size) {
            val a = screenCorners[edges[i]]!!
            val b = screenCorners[edges[i + 1]]!!
            canvas.drawLine(a[0], a[1], b[0], b[1], strokePaint)
            i += 2
        }

        fillPaint.color = colorArgb
        fillPaint.alpha = (boxAlpha.value * alphaScale).toInt().coerceIn(0, 255)
        val topPath = Path().apply {
            val p4 = screenCorners[4]!!; moveTo(p4[0], p4[1])
            val p5 = screenCorners[5]!!; lineTo(p5[0], p5[1])
            val p6 = screenCorners[6]!!; lineTo(p6[0], p6[1])
            val p7 = screenCorners[7]!!; lineTo(p7[0], p7[1])
            close()
        }
        canvas.drawPath(topPath, fillPaint)
    }

    private fun isTypeEnabled(type: TrackedOreType): Boolean = when (type) {
        TrackedOreType.DIAMOND        -> showDiamond.value
        TrackedOreType.ANCIENT_DEBRIS -> showDebris.value
        TrackedOreType.EMERALD        -> showEmerald.value
        TrackedOreType.GOLD           -> showGold.value
        TrackedOreType.IRON           -> showIron.value
        TrackedOreType.REDSTONE       -> showRedstone.value
        TrackedOreType.LAPIS          -> showLapis.value
        TrackedOreType.COAL           -> showCoal.value
        TrackedOreType.COPPER         -> showCopper.value
        TrackedOreType.QUARTZ         -> showQuartz.value
        TrackedOreType.AMETHYST       -> showAmethyst.value
    }

    fun getRenderList(): List<RenderEntry> = renderList
    fun getOreCount(): Int = OreTracker.size()
}
