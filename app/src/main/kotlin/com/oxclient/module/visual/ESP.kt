package com.oxclient.module.visual

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.BlockTracker
import com.oxclient.utils.BlockTracker.TrackedBlockType
import com.oxclient.utils.MathUtil
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket
import java.util.concurrent.CopyOnWriteArrayList
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
    private val shortcut      = bool ("Shortcut",      false)

    private val renderList = CopyOnWriteArrayList<RenderEntry>()
    private var updateJob: Job? = null

    data class RenderEntry(
        val x: Float, val y: Float, val z: Float,
        val type: TrackedBlockType,
        val distance: Float
    )

    override fun onEnable() {
        super.onEnable()
        BlockTracker.clear()
        updateJob = scope.launch { updateLoop() }
    }

    override fun onDisable() {
        updateJob?.cancel()
        super.onDisable()
        renderList.clear()
        BlockTracker.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is UpdateBlockPacket -> handleUpdateBlock(pkt)
            is BlockEntityDataPacket -> handleBlockEntity(pkt)
            is LevelChunkPacket -> handleChunk(pkt)
            else -> {}
        }
    }

    private fun handleUpdateBlock(pkt: UpdateBlockPacket) {
        val pos = pkt.blockPosition
        val runtimeId = pkt.definition?.runtimeId ?: return
        val type = BlockTracker.resolveBlockId(runtimeId)
        if (type != null) {
            if (isTypeEnabled(type)) {
                BlockTracker.add(pos.x, pos.y, pos.z, type)
            }
        } else {
            BlockTracker.remove(pos.x, pos.y, pos.z)
        }
    }

    private fun handleBlockEntity(pkt: BlockEntityDataPacket) {
        val pos = pkt.blockPosition
        val tag = pkt.data ?: return
        val id  = tag.getString("id") ?: return
        val type = BlockTracker.resolveBlockName(id) ?: return
        if (isTypeEnabled(type)) {
            BlockTracker.add(pos.x, pos.y, pos.z, type)
        }
    }

    private fun handleChunk(pkt: LevelChunkPacket) {
    }

    private suspend fun updateLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) rebuildRenderList()
            delay(200L)
        }
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
                    MathUtil.dist3(b.pos.x + 0.5f, b.pos.y + 0.5f, b.pos.z + 0.5f, cx, cy, cz)
                )
            }

        val sorted = when (sortMode.value) {
            SortMode.Distance -> entries.sortedBy { it.distance }
            SortMode.Type     -> entries.sortedBy { it.type.ordinal }
        }

        renderList.clear()
        renderList.addAll(sorted.take(maxDisplay.value))
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

        val centerX = screenW / 2f
        val centerY = screenH / 2f

        for (entry in renderList) {
            val color = entry.type.colorArgb
            val screenPos = MathUtil.worldToScreen(
                entry.x, entry.y, entry.z,
                cx, cy, cz,
                yaw, pitch,
                screenW, screenH
            )

            when (renderMode.value) {
                RenderMode.Tracer, RenderMode.Both -> {
                    tracerPaint.color = color
                    tracerPaint.alpha = 200
                    if (screenPos != null) {
                        canvas.drawLine(centerX, centerY, screenPos.first, screenPos.second, tracerPaint)
                    }
                }
                else -> {}
            }

            if (screenPos != null) {
                when (renderMode.value) {
                    RenderMode.Box, RenderMode.Both -> {
                        drawBox(canvas, screenPos.first, screenPos.second,
                            entry.distance, color, boxPaint, fillPaint)
                    }
                    else -> {}
                }

                if (showLabels.value) {
                    var label = entry.type.displayName
                    if (showDistance.value) label += " §${entry.distance.toInt()}m"
                    val labelY = screenPos.second - 28f
                    canvas.drawText(label, screenPos.first, labelY, textPaint)
                }
            }
        }
    }

    private fun drawBox(
        canvas: Canvas,
        sx: Float, sy: Float,
        distance: Float,
        colorArgb: Int,
        strokePaint: Paint,
        fillPaint: Paint
    ) {
        val size = (40f / (distance * 0.1f + 1f)).coerceIn(6f, 40f)
        val half = size / 2f

        strokePaint.color = colorArgb
        strokePaint.alpha = 220

        fillPaint.color = colorArgb
        fillPaint.alpha = boxAlpha.value

        val left   = sx - half
        val top    = sy - half
        val right  = sx + half
        val bottom = sy + half

        canvas.drawRect(left, top, right, bottom, fillPaint)
        canvas.drawRect(left, top, right, bottom, strokePaint)

        val cornerLen = size * 0.3f
        strokePaint.strokeWidth = tracerWidth.value + 1f
        canvas.drawLine(left,  top,    left  + cornerLen, top,            strokePaint)
        canvas.drawLine(left,  top,    left,              top + cornerLen, strokePaint)
        canvas.drawLine(right, top,    right - cornerLen, top,            strokePaint)
        canvas.drawLine(right, top,    right,             top + cornerLen, strokePaint)
        canvas.drawLine(left,  bottom, left  + cornerLen, bottom,         strokePaint)
        canvas.drawLine(left,  bottom, left,              bottom - cornerLen, strokePaint)
        canvas.drawLine(right, bottom, right - cornerLen, bottom,         strokePaint)
        canvas.drawLine(right, bottom, right,             bottom - cornerLen, strokePaint)
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
