package com.oxclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.BlockTracker
import com.oxclient.utils.BlockTracker.TrackedBlockType
import com.oxclient.utils.MathUtil
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class ESP : BaseModule(
    name        = "ESP",
    category    = ModuleCategory.VISUAL,
    description = "Etraftaki sandık/shulker/spawner ve depolama bloklarını tracer ile gösterir"
) {
    enum class RenderMode  { Tracer, Box, Both }
    enum class SortMode    { Distance, Type }

    private val renderMode    = enum ("Render Mode",   RenderMode.Both)
    private val sortMode      = enum ("Sort Mode",     SortMode.Distance)
    private val scanRange     = float("Scan Range",    64f,  8f, 256f)
    private val maxDisplay    = int  ("Max Display",   100,  10, 500)
    private val tracerWidth   = float("Tracer Width",  2f,   0.5f, 8f)
    private val boxAlpha      = int  ("Box Alpha",     80,   10,  200)
    private val showLabels    = bool ("Show Labels",   true)
    private val showDistance  = bool ("Show Distance", true)
    private val showChest     = bool ("Chest",         true)
    private val showShulker   = bool ("Shulker",       true)
    private val showEnder     = bool ("Ender Chest",   true)
    private val showSpawner   = bool ("Spawner",       true)
    private val showHopper    = bool ("Hopper",        true)
    private val showBarrel    = bool ("Barrel",        true)
    private val fadeByDistance= bool ("Distance Fade", true)
    private val minAlpha      = int  ("Min Alpha",     40,   0,   255)
    private val showRadar     = bool ("Off-Screen Radar", true)
    private val radarMargin   = float("Radar Margin",  36f,  10f, 100f)
    private val showSummary   = bool ("Summary Panel", true)
    private val smoothTracer  = bool ("Smooth Tracer", true)
    private val smoothFactor  = float("Smooth Factor", 0.35f,0.05f, 1f)
    private val nearestGlow   = bool ("Highlight Nearest", true)
    private val updateRateMs  = int  ("Update Rate (ms)", 150, 50, 1000)
    private val shortcut      = bool ("Shortcut",      false)

    private val renderList = CopyOnWriteArrayList<RenderEntry>()
    private var updateJob: Job? = null

    // Ekran koordinatlarında yumuşatma (jitter azaltma) için önceki pozisyon önbelleği
    private val smoothedScreenPos = ConcurrentHashMap<Long, FloatArray>()

    data class RenderEntry(
        val x: Float, val y: Float, val z: Float,
        val type: TrackedBlockType,
        val distance: Float,
        val key: Long
    )

    override fun onEnable() {
        super.onEnable()
        // ✅ FIX: BlockTracker.clear() artık BURADA çağrılmıyor. BlockTracker, EntityTracker
        // gibi oturum boyunca her zaman aktif (bkz. GamingPacketListener) — modül enable/disable
        // durumundan bağımsız veri topluyor. ESP burada temizlerse, ESP kapalıyken toplanmış
        // tüm bloklar (chunk yüklenmesi vb.) enable anında silinip render için hiç kalmıyordu.
        smoothedScreenPos.clear()
        updateJob = launchTickLoop(updateRateMs.value.toLong()) { updateTick() }
    }

    override fun onDisable() {
        updateJob?.cancel()
        super.onDisable()
        renderList.clear()
        smoothedScreenPos.clear()
        // NOT: BlockTracker.clear() burada da çağrılmıyor — veri kaynağı artık ESP'nin
        // enable durumuna bağlı değil, session sonlanana kadar (SessionManager) kalıcı.
    }

    private fun updateTick() {
        rebuildRenderList()
    }

    private fun rebuildRenderList() {
        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY + 1.62f
        val cz = EntityTracker.selfZ
        val range = scanRange.value

        val entries = BlockTracker.getAllInRange(cx, cy, cz, range)
            .filter { isTypeEnabled(it.type) }
            .map { b ->
                RenderEntry(
                    b.pos.x + 0.5f,
                    b.pos.y + 0.5f,
                    b.pos.z + 0.5f,
                    b.type,
                    MathUtil.dist3(b.pos.x + 0.5f, b.pos.y + 0.5f, b.pos.z + 0.5f, cx, cy, cz),
                    BlockTracker.packKey(b.pos.x, b.pos.y, b.pos.z)
                )
            }

        val sorted = when (sortMode.value) {
            SortMode.Distance -> entries.sortedBy { it.distance }
            SortMode.Type     -> entries.sortedBy { it.type.ordinal }
        }

        val trimmed = sorted.take(maxDisplay.value)
        renderList.clear()
        renderList.addAll(trimmed)

        // Görünürlüğü kalmayan bloklar için smoothing önbelleğini temizle (bellek sızıntısını önler)
        val activeKeys = trimmed.mapTo(HashSet()) { it.key }
        smoothedScreenPos.keys.retainAll(activeKeys)
    }

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return
        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY + 1.62f
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

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style     = Paint.Style.FILL
            color     = Color.WHITE
            textSize  = 28f
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val centerX = screenW / 2f
        val centerY = screenH / 2f
        val maxRange = scanRange.value
        val nearest = if (nearestGlow.value) renderList.minByOrNull { it.distance } else null

        for (entry in renderList) {
            val color = entry.type.colorArgb
            val rawPos = MathUtil.worldToScreen(
                entry.x, entry.y, entry.z,
                cx, cy, cz,
                yaw, pitch,
                screenW, screenH
            )

            // Mesafeye göre saydamlık: yakın bloklar tam opak, uzak bloklar (minAlpha'ya kadar) sönük
            val alphaScale = if (fadeByDistance.value) {
                val t = (1f - (entry.distance / maxRange)).coerceIn(0f, 1f)
                (minAlpha.value + (255 - minAlpha.value) * t) / 255f
            } else 1f

            val screenPos = if (rawPos != null && smoothTracer.value) {
                smooth(entry.key, rawPos.first, rawPos.second)
            } else rawPos

            val isNearest = nearestGlow.value && entry === nearest

            if (screenPos != null) {
                when (renderMode.value) {
                    RenderMode.Tracer, RenderMode.Both -> {
                        tracerPaint.color = color
                        tracerPaint.alpha = (200 * alphaScale).toInt().coerceIn(0, 255)
                        tracerPaint.strokeWidth = if (isNearest) tracerWidth.value + 1.5f else tracerWidth.value
                        canvas.drawLine(centerX, centerY, screenPos.first, screenPos.second, tracerPaint)
                    }
                    else -> {}
                }

                when (renderMode.value) {
                    RenderMode.Box, RenderMode.Both -> {
                        drawBox(canvas, screenPos.first, screenPos.second,
                            entry.distance, color, boxPaint, fillPaint, alphaScale, isNearest)
                    }
                    else -> {}
                }

                if (showLabels.value) {
                    var label = entry.type.displayName
                    if (isNearest) label = "» $label «"
                    if (showDistance.value) label += " §${entry.distance.toInt()}m"
                    textPaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                    val labelY = screenPos.second - 28f
                    canvas.drawText(label, screenPos.first, labelY, textPaint)
                }
            } else if (showRadar.value) {
                drawRadarArrow(canvas, entry, centerX, centerY, screenW, screenH, yaw, color, radarPaint)
            }
        }

        if (showSummary.value) drawSummaryPanel(canvas, screenW)
    }

    /** Ekran koordinatlarını üstel yumuşatma ile titremeyi (jitter) azaltır. */
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

    /** Görüş alanı dışındaki bloklar için ekran kenarında yön oku çizer (radar tarzı). */
    private fun drawRadarArrow(
        canvas: Canvas, entry: RenderEntry,
        centerX: Float, centerY: Float,
        screenW: Int, screenH: Int,
        selfYaw: Float, color: Int, paint: Paint
    ) {
        val angleToTarget = Math.toDegrees(
            atan2((entry.x - EntityTracker.selfX).toDouble(), (entry.z - EntityTracker.selfZ).toDouble())
        ).toFloat()
        val relative = ((angleToTarget - selfYaw) % 360f + 540f) % 360f - 180f
        val rad = Math.toRadians(relative.toDouble())

        val margin = radarMargin.value
        val maxX = screenW / 2f - margin
        val maxY = screenH / 2f - margin
        val dirX = sin(rad).toFloat()
        val dirY = -cos(rad).toFloat()

        val scale = min(
            if (abs(dirX) > 0.0001f) maxX / abs(dirX) else Float.MAX_VALUE,
            if (abs(dirY) > 0.0001f) maxY / abs(dirY) else Float.MAX_VALUE
        )
        val px = centerX + dirX * scale
        val py = centerY + dirY * scale

        paint.color = color
        paint.alpha = 220

        val size = 14f
        val perpX = -dirY; val perpY = dirX
        val tipX = px + dirX * size; val tipY = py + dirY * size
        val leftX = px - dirX * size * 0.5f + perpX * size * 0.6f
        val leftY = py - dirY * size * 0.5f + perpY * size * 0.6f
        val rightX = px - dirX * size * 0.5f - perpX * size * 0.6f
        val rightY = py - dirY * size * 0.5f - perpY * size * 0.6f

        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.drawPath(path, paint)
    }

    /** Ekranın köşesinde bulunan blokların türe göre sayısını gösteren özet panel. */
    private fun drawSummaryPanel(canvas: Canvas, screenW: Int) {
        val counts = renderList.groupingBy { it.type }.eachCount()
        if (counts.isEmpty()) return

        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
            alpha = 130
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize  = 24f
            textAlign = Paint.Align.LEFT
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        val entries = counts.entries.sortedByDescending { it.value }
        val lineHeight = 30f
        val paddingX = 14f
        val paddingY = 10f
        val panelW = 190f
        val panelH = paddingY * 2 + lineHeight * entries.size
        val startX = screenW - panelW - 16f
        val startY = 16f

        canvas.drawRoundRect(startX, startY, startX + panelW, startY + panelH, 10f, 10f, panelPaint)

        entries.forEachIndexed { i, (type, count) ->
            linePaint.color = type.colorArgb
            canvas.drawText(
                "${type.displayName}: $count",
                startX + paddingX,
                startY + paddingY + lineHeight * (i + 1) - 8f,
                linePaint
            )
        }
    }

    private fun drawBox(
        canvas: Canvas,
        sx: Float, sy: Float,
        distance: Float,
        colorArgb: Int,
        strokePaint: Paint,
        fillPaint: Paint,
        alphaScale: Float = 1f,
        isNearest: Boolean = false
    ) {
        val size = (40f / (distance * 0.1f + 1f)).coerceIn(6f, 40f)
        val half = size / 2f

        strokePaint.color = colorArgb
        strokePaint.alpha = (220 * alphaScale).toInt().coerceIn(0, 255)

        fillPaint.color = colorArgb
        fillPaint.alpha = (boxAlpha.value * alphaScale).toInt().coerceIn(0, 255)

        val left   = sx - half
        val top    = sy - half
        val right  = sx + half
        val bottom = sy + half

        canvas.drawRect(left, top, right, bottom, fillPaint)
        canvas.drawRect(left, top, right, bottom, strokePaint)

        val cornerLen = size * 0.3f
        strokePaint.strokeWidth = if (isNearest) tracerWidth.value + 2f else tracerWidth.value + 1f
        canvas.drawLine(left,  top,    left  + cornerLen, top,            strokePaint)
        canvas.drawLine(left,  top,    left,              top + cornerLen, strokePaint)
        canvas.drawLine(right, top,    right - cornerLen, top,            strokePaint)
        canvas.drawLine(right, top,    right,             top + cornerLen, strokePaint)
        canvas.drawLine(left,  bottom, left  + cornerLen, bottom,         strokePaint)
        canvas.drawLine(left,  bottom, left,              bottom - cornerLen, strokePaint)
        canvas.drawLine(right, bottom, right - cornerLen, bottom,         strokePaint)
        canvas.drawLine(right, bottom, right,             bottom - cornerLen, strokePaint)

        if (isNearest) {
            val glowPaint = Paint(strokePaint)
            glowPaint.color = colorArgb
            glowPaint.alpha = (90 * alphaScale).toInt().coerceIn(0, 255)
            glowPaint.strokeWidth = strokePaint.strokeWidth + 3f
            canvas.drawRect(left - 3f, top - 3f, right + 3f, bottom + 3f, glowPaint)
        }
    }

    private fun isTypeEnabled(type: TrackedBlockType): Boolean = when (type) {
        TrackedBlockType.CHEST,
        TrackedBlockType.TRAPPED_CHEST -> showChest.value
        TrackedBlockType.SHULKER_BOX   -> showShulker.value
        TrackedBlockType.ENDER_CHEST   -> showEnder.value
        TrackedBlockType.SPAWNER       -> showSpawner.value
        TrackedBlockType.HOPPER        -> showHopper.value
        TrackedBlockType.BARREL        -> showBarrel.value
        TrackedBlockType.FURNACE,
        TrackedBlockType.BLAST_FURNACE,
        TrackedBlockType.SMOKER        -> true
        TrackedBlockType.BREWING_STAND -> true
        TrackedBlockType.DISPENSER,
        TrackedBlockType.DROPPER       -> true
    }

    fun getRenderList(): List<RenderEntry> = renderList
    fun getBlockCount(): Int = BlockTracker.size()
}
