package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import java.util.concurrent.ConcurrentHashMap

object OreTracker : PacketEventBus.PacketListener {

    enum class TrackedOreType(val displayName: String, val colorArgb: Int) {
        DIAMOND       ("Diamond",        0xFF4FD8D0.toInt()),
        ANCIENT_DEBRIS("Ancient Debris", 0xFF9C6B4F.toInt()),
        EMERALD       ("Emerald",        0xFF3FAE6E.toInt()),
        GOLD          ("Gold",           0xFFC9A227.toInt()),
        IRON          ("Iron",           0xFFB5967A.toInt()),
        REDSTONE      ("Redstone",       0xFFB23A3A.toInt()),
        LAPIS         ("Lapis",          0xFF3A5FA8.toInt()),
        COAL          ("Coal",           0xFF5A5A5A.toInt()),
        COPPER        ("Copper",         0xFFB0663E.toInt()),
        QUARTZ        ("Nether Quartz",  0xFFC9BFA8.toInt()),
        AMETHYST      ("Amethyst",       0xFF8A6FC2.toInt()),
    }

    data class TrackedOre(
        val pos: Vector3i,
        val type: TrackedOreType,
        val discoveredAt: Long = System.currentTimeMillis()
    )

    private val trackedOres = ConcurrentHashMap<Long, TrackedOre>()

    private const val CELL_SHIFT = 4
    private val cellIndex = ConcurrentHashMap<Long, MutableSet<Long>>()

    private fun cellKey(x: Int, y: Int, z: Int): Long {
        val cx = x shr CELL_SHIFT
        val cy = y shr CELL_SHIFT
        val cz = z shr CELL_SHIFT
        return ((cx.toLong() and 0x1FFFFFL) shl 42) or
               ((cy.toLong() and 0xFFFL)    shl 21) or
               (cz.toLong() and 0x1FFFFFL)
    }

    fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL) shl 26) or
        (z.toLong() and 0x3FFFFFFL)

    fun add(x: Int, y: Int, z: Int, type: TrackedOreType) {
        val key = packKey(x, y, z)
        trackedOres[key] = TrackedOre(Vector3i.from(x, y, z), type)
        cellIndex.getOrPut(cellKey(x, y, z)) { ConcurrentHashMap.newKeySet() }.add(key)
    }

    fun remove(x: Int, y: Int, z: Int) {
        val key = packKey(x, y, z)
        trackedOres.remove(key)
        cellIndex[cellKey(x, y, z)]?.remove(key)
    }

    // Chunk yeniden geldiğinde (yeniden yüklendiğinde) o chunk'a ait eski kayıtları temizler.
    // Not: cellIndex'i tam senkron tutmuyor (basitlik için) — pratikte eski key'ler
    // getAllInRange'de sadece boşa taranır, veri sızıntısı yaratmaz.
    fun clearChunk(chunkX: Int, chunkZ: Int) {
        val minX = chunkX shl 4; val maxX = minX + 15
        val minZ = chunkZ shl 4; val maxZ = minZ + 15
        trackedOres.entries.removeIf { (_, ore) ->
            ore.pos.x in minX..maxX && ore.pos.z in minZ..maxZ
        }
    }

    fun getAll(): Collection<TrackedOre> = trackedOres.values

    fun getAllInRange(cx: Float, cy: Float, cz: Float, range: Float): List<TrackedOre> {
        val r2 = range * range
        val cellRadius = (range.toInt() shr CELL_SHIFT) + 1
        val ccx = cx.toInt() shr CELL_SHIFT
        val ccy = cy.toInt() shr CELL_SHIFT
        val ccz = cz.toInt() shr CELL_SHIFT

        val result = ArrayList<TrackedOre>(64)
        for (dx in -cellRadius..cellRadius) {
            for (dy in -cellRadius..cellRadius) {
                for (dz in -cellRadius..cellRadius) {
                    val key = ((ccx + dx).toLong() and 0x1FFFFFL shl 42) or
                              ((ccy + dy).toLong() and 0xFFFL    shl 21) or
                              ((ccz + dz).toLong() and 0x1FFFFFL)
                    val cell = cellIndex[key] ?: continue
                    for (oreKey in cell) {
                        val o = trackedOres[oreKey] ?: continue
                        val bdx = o.pos.x + 0.5f - cx
                        val bdy = o.pos.y + 0.5f - cy
                        val bdz = o.pos.z + 0.5f - cz
                        if (bdx*bdx + bdy*bdy + bdz*bdz <= r2) result.add(o)
                    }
                }
            }
        }
        return result
    }

    fun clear() {
        trackedOres.clear()
        cellIndex.clear()
    }

    fun size(): Int = trackedOres.size

    fun countByType(): Map<TrackedOreType, Int> =
        trackedOres.values.groupingBy { it.type }.eachCount()

    fun init() { PacketEventBus.register(this) }

    override fun onPacket(event: PacketEvent) {
        if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
        when (val p = event.packet) {
            is StartGamePacket       -> clear()
            is ChangeDimensionPacket -> clear()
            is LevelChunkPacket      -> handleChunk(p)
            else -> {}
        }
    }

    private fun handleChunk(p: LevelChunkPacket) {
        val minSection = minSectionForDimension(EntityTracker.selfDimension)
        val hits = try {
            ChunkParser.extractOreBlocks(p, ::isOreRuntimeId, minSection)
        } catch (_: Exception) { emptyList() }
        for (hit in hits) {
            val type = resolveRuntimeId(hit.runtimeId) ?: continue
            add(hit.x, hit.y, hit.z, type)
        }
    }

    // Bedrock dimension id: 0=Overworld 1=Nether 2=End (EntityTracker.selfDimension ile aynı sıra)
    // Overworld 1.18+ dünya yüksekliği varsayılıyor (-64..320, section -4'ten başlar).
    // Eski (pre-1.18) dünyalarda bu yanlış olur — öyle bir sunucuda test edip doğrula.
    private fun minSectionForDimension(dimension: Int): Int = when (dimension) {
        1, 2 -> 0                  // Nether / End: Y 0'dan başlar
        else -> -4                 // Overworld (1.18+)
    }

    private val paletteMap = ConcurrentHashMap<Int, TrackedOreType>()
    private val nonOreIds  = ConcurrentHashMap.newKeySet<Int>()

    // Eskiden burada StartGamePacket üzerinde reflection ile ("getBlockPalette" vb.)
    // statik bir palet listesi aranıyordu. Bu, block-network-ID hashing kullanan
    // sunucularda (StartGamePacket'te ayrık bir palet listesi hiç gelmeyebilir) hep
    // başarısız oluyordu ve OreTracker'ı kalıcı olarak boş bırakıyordu (Xray'in hiç
    // çalışmamasının sebebi buydu). Şimdi her runtimeId, WorldBlockTracker'ın zaten
    // kanıtlanmış çalışan session-registry tabanlı çözümleyicisiyle ilk görüldüğünde
    // tembel (lazy) olarak çözülüp cache'leniyor — hashing modundan bağımsız çalışır.
    private fun isOreRuntimeId(runtimeId: Int): Boolean {
        if (paletteMap.containsKey(runtimeId)) return true
        if (nonOreIds.contains(runtimeId)) return false

        val identifier = WorldBlockTracker.resolveIdentifier(runtimeId)
        val type = identifier?.let { resolveOreName(it) }
        if (type != null) {
            paletteMap[runtimeId] = type
            return true
        }
        nonOreIds.add(runtimeId)
        return false
    }

    fun oreRuntimeIds(): Set<Int> = paletteMap.keys

    fun resolveRuntimeId(runtimeId: Int): TrackedOreType? = paletteMap[runtimeId]

    fun resolveOreName(name: String): TrackedOreType? {
        val n = name.lowercase()
        return when {
            n.contains("ancient_debris")   -> TrackedOreType.ANCIENT_DEBRIS
            n.contains("diamond_ore")      -> TrackedOreType.DIAMOND
            n.contains("emerald_ore")      -> TrackedOreType.EMERALD
            n.contains("gold_ore")         -> TrackedOreType.GOLD
            n.contains("iron_ore")         -> TrackedOreType.IRON
            n.contains("redstone_ore")     -> TrackedOreType.REDSTONE
            n.contains("lapis_ore")        -> TrackedOreType.LAPIS
            n.contains("coal_ore")         -> TrackedOreType.COAL
            n.contains("copper_ore")       -> TrackedOreType.COPPER
            n.contains("quartz_ore")       -> TrackedOreType.QUARTZ
            n.contains("amethyst_cluster") -> TrackedOreType.AMETHYST
            n.contains("budding_amethyst") -> TrackedOreType.AMETHYST
            else -> null
        }
    }
}
