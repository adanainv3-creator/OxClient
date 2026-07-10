package com.oxclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.utils.MathUtil
import kotlin.math.*

class EnemyESP : BaseModule(
    name = "EnemyESP",
    category = ModuleCategory.VISUAL,
    description = "Oyuncuları ve düşmanları tracer ile gösterir"
) {
    enum class TargetMode { All, Players, Hostiles }

    private val targetMode = enum("Target Mode", TargetMode.All)
    private val scanRange = float("Scan Range", 64f, 8f, 512f)
    private val fov = float("FOV", 70f, 30f, 130f)
    private val tracerWidth = float("Tracer Width", 2f, 0.5f, 8f)
    private val showLabels = bool("Show Labels", true)
    private val showDistance = bool("Show Distance", true)
    private val showBox = bool("Show Box", true)
    private val showTracer = bool("Show Tracer", true)
    private val boxAlpha = int("Box Alpha", 60, 10, 200)
    private val fadeByDistance = bool("Distance Fade", true)
    private val minAlpha = int("Min Alpha", 40, 0, 255)
    private val smoothFactor = float("Smooth Factor", 0.35f, 0.05f, 1f)
    private val showRadar = bool("Off-Screen Radar", true)
    private val radarMargin = float("Radar Margin", 36f, 10f, 100f)

    data class TargetEntry(
        val runtimeId: Long,
        val name: String,
        val x: Float, val y: Float, val z: Float,
        val distance: Float,
        val isPlayer: Boolean,
        val health: Float,
        val maxHealth: Float
    )

    private val targetList = mutableListOf<TargetEntry>()
    private val smoothedScreenPos = mutableMapOf<Long, FloatArray>()

    override fun onEnable() {
        super.onEnable()
        smoothedScreenPos.clear()
    }

    override fun onDisable() {
        super.onDisable()
        targetList.clear()
        smoothedScreenPos.clear()
    }

    private fun updateTargets() {
        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ
        val range = scanRange.value

        val entities = when (targetMode.value) {
            TargetMode.All -> EntityTracker.getAll()
            TargetMode.Players -> EntityTracker.getPlayers(range)
            TargetMode.Hostiles -> EntityTracker.getHostiles(range)
        }

        targetList.clear()
        for (e in entities) {
            val dist = MathUtil.dist3(e.x, e.y, e.z, selfX, selfY, selfZ)
            if (dist > range) continue
            if (!EntityTracker.isInFov(e, fov.value) && fov.value < 360f) continue
            if (e.runtimeId == EntityTracker.selfRuntimeId) continue

            targetList.add(
                TargetEntry(
                    runtimeId = e.runtimeId,
                    name = if (e.name.isNotEmpty()) e.name else "Entity",
                    x = e.x, y = e.y, z = e.z,
                    distance = dist,
                    isPlayer = e.isPlayer,
                    health = e.health,
                    maxHealth = e.maxHealth
                )
            )
        }

        targetList.sortBy { it.distance }

        val activeIds = targetList.mapTo(hashSetOf()) { it.runtimeId }
        smoothedScreenPos.keys.retainAll(activeIds)
    }

    fun render(canvas: Canvas, screenW: Int, screenH: Int) {
        if (!isEnabled) return

        updateTargets()

        if (targetList.isEmpty()) return

        val selfX = EntityTracker.selfX
        val selfY = EntityTracker.selfY
        val selfZ = EntityTracker.selfZ
        val yaw = EntityTracker.selfYaw
        val pitch = EntityTracker.selfPitch
        val centerX = screenW / 2f
        val centerY = screenH / 2f
        val maxRange = scanRange.value

        val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
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
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val healthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        for (entry in targetList) {
            val color = if (entry.isPlayer) {
                Color.rgb(0, 200, 255)
            } else {
                Color.rgb(255, 50, 50)
            }

            val alphaScale = if (fadeByDistance.value) {
                val t = (1f - (entry.distance / maxRange)).coerceIn(0f, 1f)
                (minAlpha.value + (255 - minAlpha.value) * t) / 255f
            } else 1f

            val rawPos = MathUtil.worldToScreen(
                entry.x, entry.y + 1.0f, entry.z,
                selfX, selfY, selfZ,
                yaw, pitch,
                screenW, screenH,
                fov.value
            )

            val screenPos = if (rawPos != null) {
                smooth(entry.runtimeId, rawPos.first, rawPos.second)
            } else null

            if (screenPos != null) {
                if (showTracer.value) {
                    tracerPaint.color = color
                    tracerPaint.alpha = (200 * alphaScale).toInt().coerceIn(0, 255)
                    canvas.drawLine(centerX, centerY, screenPos.first, screenPos.second, tracerPaint)
                }

                if (showBox.value) {
                    drawTargetBox(canvas, entry, screenPos.first, screenPos.second, color, boxPaint, fillPaint, alphaScale)
                }

                if (showLabels.value) {
                    var label = entry.name
                    if (showDistance.value) {
                        label += " ${entry.distance.toInt()}m"
                    }
                    textPaint.color = Color.WHITE
                    textPaint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
                    val labelY = screenPos.second - 28f
                    canvas.drawText(label, screenPos.first, labelY, textPaint)

                    if (entry.maxHealth > 0) {
                        val healthPercent = (entry.health / entry.maxHealth).coerceIn(0f, 1f)
                        val barWidth = 60f
                        val barHeight = 4f
                        val barX = screenPos.first - barWidth / 2f
                        val barY = screenPos.second + 12f

                        healthPaint.color = Color.BLACK
                        healthPaint.alpha = (150 * alphaScale).toInt().coerceIn(0, 255)
                        canvas.drawRoundRect(barX, barY, barX + barWidth, barY + barHeight, 2f, 2f, healthPaint)

                        val healthColor = when {
                            healthPercent > 0.6f -> Color.rgb(0, 255, 0)
                            healthPercent > 0.3f -> Color.rgb(255, 200, 0)
                            else -> Color.rgb(255, 0, 0)
                        }
                        healthPaint.color = healthColor
                        healthPaint.alpha = (220 * alphaScale).toInt().coerceIn(0, 255)
                        canvas.drawRoundRect(
                            barX, barY,
                            barX + barWidth * healthPercent,
                            barY + barHeight,
                            2f, 2f,
                            healthPaint
                        )
                    }
                }
            } else if (showRadar.value) {
                drawRadarArrow(canvas, entry, centerX, centerY, screenW, screenH, yaw, color, radarPaint)
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

    private fun drawTargetBox(
        canvas: Canvas,
        entry: TargetEntry,
        sx: Float, sy: Float,
        color: Int,
        strokePaint: Paint,
        fillPaint: Paint,
        alphaScale: Float
    ) {
        val boxSize = 20f
        val left = sx - boxSize
        val top = sy - boxSize
        val right = sx + boxSize
        val bottom = sy + boxSize

        strokePaint.color = color
        strokePaint.alpha = (200 * alphaScale).toInt().coerceIn(0, 255)
        canvas.drawRect(left, top, right, bottom, strokePaint)

        fillPaint.color = color
        fillPaint.alpha = (boxAlpha.value * alphaScale).toInt().coerceIn(0, 255)
        canvas.drawRect(left, top, right, bottom, fillPaint)
    }

    private fun drawRadarArrow(
        canvas: Canvas,
        entry: TargetEntry,
        centerX: Float, centerY: Float,
        screenW: Int, screenH: Int,
        selfYaw: Float,
        color: Int,
        paint: Paint
    ) {
        val angleToTarget = Math.toDegrees(
            atan2(
                (EntityTracker.selfX - entry.x).toDouble(),
                (entry.z - EntityTracker.selfZ).toDouble()
            )
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
        val perpX = -dirY
        val perpY = dirX
        val tipX = px + dirX * size
        val tipY = py + dirY * size
        val leftX = px - dirX * size * 0.5f + perpX * size * 0.6f
        val leftY = py - dirY * size * 0.5f + perpY * size * 0.6f
        val rightX = px - dirX * size * 0.5f - perpX * size * 0.6f
        val rightY = py - dirY * size * 0.5f - perpY * size * 0.6f

        val path = android.graphics.Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.drawPath(path, paint)
    }

    fun getTargetList(): List<TargetEntry> = targetList.toList()
}
