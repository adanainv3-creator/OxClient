package com.nexoraclient.config

import android.graphics.Bitmap
import com.nexoraclient.utils.BlockPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MapArtPlan {

    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()

    private val _grid = MutableStateFlow<Array<Array<String>>?>(null)
    val grid: StateFlow<Array<Array<String>>?> = _grid.asStateFlow()

    private val _requiredCounts = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val requiredCounts: StateFlow<List<Pair<String, Int>>> = _requiredCounts.asStateFlow()

    fun analyze(bitmap: Bitmap, gridSize: Int) {
        val scaled = Bitmap.createScaledBitmap(bitmap, gridSize, gridSize, true)

        val grid = Array(gridSize) { row ->
            Array(gridSize) { col ->
                val pixel = scaled.getPixel(col, row)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                BlockPalette.closestBlock(r, g, b)
            }
        }

        if (scaled !== bitmap) scaled.recycle()

        val counts = LinkedHashMap<String, Int>()
        for (row in grid) for (id in row) counts[id] = (counts[id] ?: 0) + 1

        _grid.value = grid
        _size.value = gridSize
        _requiredCounts.value = counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun clear() {
        _grid.value = null
        _size.value = 0
        _requiredCounts.value = emptyList()
    }
}
