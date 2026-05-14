package com.oxclient.utils

import org.cloudburstmc.math.vector.Vector3i
import java.util.concurrent.ConcurrentHashMap

object BlockTracker {

    enum class TrackedBlockType(val displayName: String, val colorArgb: Int) {
        CHEST        ("Sandık",      0xFFFF8C00.toInt()),
        SHULKER_BOX  ("Shulker",     0xFFAA00FF.toInt()),
        ENDER_CHEST  ("Ender Sandık",0xFF00BFFF.toInt()),
        SPAWNER      ("Spawner",     0xFFFF2020.toInt()),
        HOPPER       ("Huni",        0xFF888888.toInt()),
        BARREL       ("Varil",       0xFF8B4513.toInt()),
        TRAPPED_CHEST("Tuzak Sandık",0xFFFF4400.toInt()),
        FURNACE      ("Fırın",       0xFFCCCCCC.toInt()),
        BLAST_FURNACE("Patlama Fırın",0xFFFFAA00.toInt()),
        SMOKER       ("Tütücü",      0xFFAAFFAA.toInt()),
        BREWING_STAND("Demleme Sehpası",0xFF9966FF.toInt()),
        DISPENSER    ("Dağıtıcı",    0xFFFFFF00.toInt()),
        DROPPER      ("Düşürücü",    0xFFFFCC00.toInt()),
    }

    data class TrackedBlock(
        val pos: Vector3i,
        val type: TrackedBlockType,
        val discoveredAt: Long = System.currentTimeMillis()
    )

    private val trackedBlocks = ConcurrentHashMap<Long, TrackedBlock>()

    fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL) shl 26) or
        (z.toLong() and 0x3FFFFFFL)

    fun add(x: Int, y: Int, z: Int, type: TrackedBlockType) {
        val key = packKey(x, y, z)
        trackedBlocks[key] = TrackedBlock(Vector3i.from(x, y, z), type)
    }

    fun remove(x: Int, y: Int, z: Int) {
        trackedBlocks.remove(packKey(x, y, z))
    }

    fun getAll(): Collection<TrackedBlock> = trackedBlocks.values

    fun getAllInRange(cx: Float, cy: Float, cz: Float, range: Float): List<TrackedBlock> {
        val r2 = range * range
        return trackedBlocks.values.filter { b ->
            val dx = b.pos.x + 0.5f - cx
            val dy = b.pos.y + 0.5f - cy
            val dz = b.pos.z + 0.5f - cz
            dx*dx + dy*dy + dz*dz <= r2
        }
    }

    fun clear() = trackedBlocks.clear()

    fun size(): Int = trackedBlocks.size

    fun resolveBlockId(runtimeId: Int): TrackedBlockType? = BLOCK_ID_MAP[runtimeId]

    fun resolveBlockName(name: String): TrackedBlockType? {
        val lower = name.lowercase()
        return when {
            lower.contains("chest") && lower.contains("ender") -> TrackedBlockType.ENDER_CHEST
            lower.contains("chest") && lower.contains("trapped") -> TrackedBlockType.TRAPPED_CHEST
            lower.contains("chest")        -> TrackedBlockType.CHEST
            lower.contains("shulker")      -> TrackedBlockType.SHULKER_BOX
            lower.contains("spawner")      -> TrackedBlockType.SPAWNER
            lower.contains("hopper")       -> TrackedBlockType.HOPPER
            lower.contains("barrel")       -> TrackedBlockType.BARREL
            lower.contains("blast_furnace")-> TrackedBlockType.BLAST_FURNACE
            lower.contains("smoker")       -> TrackedBlockType.SMOKER
            lower.contains("furnace")      -> TrackedBlockType.FURNACE
            lower.contains("brewing")      -> TrackedBlockType.BREWING_STAND
            lower.contains("dispenser")    -> TrackedBlockType.DISPENSER
            lower.contains("dropper")      -> TrackedBlockType.DROPPER
            else -> null
        }
    }

    private val BLOCK_ID_MAP: Map<Int, TrackedBlockType> = mapOf(
        54   to TrackedBlockType.CHEST,
        146  to TrackedBlockType.TRAPPED_CHEST,
        130  to TrackedBlockType.ENDER_CHEST,
        218  to TrackedBlockType.SHULKER_BOX,
        219  to TrackedBlockType.SHULKER_BOX,
        220  to TrackedBlockType.SHULKER_BOX,
        221  to TrackedBlockType.SHULKER_BOX,
        222  to TrackedBlockType.SHULKER_BOX,
        223  to TrackedBlockType.SHULKER_BOX,
        224  to TrackedBlockType.SHULKER_BOX,
        225  to TrackedBlockType.SHULKER_BOX,
        226  to TrackedBlockType.SHULKER_BOX,
        227  to TrackedBlockType.SHULKER_BOX,
        228  to TrackedBlockType.SHULKER_BOX,
        229  to TrackedBlockType.SHULKER_BOX,
        230  to TrackedBlockType.SHULKER_BOX,
        231  to TrackedBlockType.SHULKER_BOX,
        232  to TrackedBlockType.SHULKER_BOX,
        233  to TrackedBlockType.SHULKER_BOX,
        52   to TrackedBlockType.SPAWNER,
        154  to TrackedBlockType.HOPPER,
        58   to TrackedBlockType.BARREL,
        61   to TrackedBlockType.FURNACE,
        62   to TrackedBlockType.FURNACE,
        450  to TrackedBlockType.BLAST_FURNACE,
        451  to TrackedBlockType.SMOKER,
        117  to TrackedBlockType.BREWING_STAND,
        23   to TrackedBlockType.DISPENSER,
        158  to TrackedBlockType.DROPPER,
    )
}
