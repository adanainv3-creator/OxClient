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

    // Son analyze edilen ham bitmap (reanalyze için saklanır)
    @Volatile private var sourceBitmap: Bitmap? = null
    @Volatile private var sourceGridSize: Int = 0

    /**
     * Görüntüyü analiz eder ve grid oluşturur.
     * [available] boşsa tüm BlockPalette kullanılır.
     */
    fun analyze(bitmap: Bitmap, gridSize: Int, available: Set<String> = emptySet()) {
        sourceBitmap = bitmap
        sourceGridSize = gridSize

        buildGrid(bitmap, gridSize, available)
    }

    /**
     * Mevcut görüntüyü farklı bir available seti ile yeniden analiz eder.
     * Resim seçilmemişse hiçbir şey yapmaz.
     */
    fun reanalyze(available: Set<String>) {
        val bmp = sourceBitmap ?: return
        val size = sourceGridSize.takeIf { it > 0 } ?: return
        buildGrid(bmp, size, available)
    }

    private fun buildGrid(bitmap: Bitmap, gridSize: Int, available: Set<String>) {
        val scaled = Bitmap.createScaledBitmap(bitmap, gridSize, gridSize, true)

        val grid = Array(gridSize) { row ->
            Array(gridSize) { col ->
                val pixel = scaled.getPixel(col, row)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8)  and 0xFF
                val b = pixel and 0xFF
                BlockPalette.closestBlock(r, g, b, available)
            }
        }

        if (scaled !== bitmap) scaled.recycle()

        val counts = LinkedHashMap<String, Int>()
        for (row in grid) for (id in row) counts[id] = (counts[id] ?: 0) + 1

        _grid.value = grid
        _size.value = gridSize
        _requiredCounts.value = counts.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    fun clear() {
        _grid.value = null
        _size.value = 0
        _requiredCounts.value = emptyList()
        sourceBitmap = null
        sourceGridSize = 0
    }
}
