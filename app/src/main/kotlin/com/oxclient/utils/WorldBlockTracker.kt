package com.oxclient.utils

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.ui.overlay.OverlayLogger
import io.netty.buffer.ByteBuf
import com.oxclient.core.proxy.EntityTracker
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object WorldBlockTracker : PacketEventBus.PacketListener {

    private const val TAG = "WorldBlockTracker"
    private const val SECTION_BLOCKS = 4096

    // Cache for storing block data per section (chunk column + sub-chunk Y level)
    private val sections = ConcurrentHashMap<Long, IntArray>()
    // FIFO queue to track insertion order for cache eviction
    private val insertOrder = ConcurrentLinkedQueue<Long>()
    // Maximum number of sections to keep in memory
    private const val MAX_SECTIONS = 4096

    // Override cache for individual block updates (e.g., UpdateBlockPacket)
    private val overrides = ConcurrentHashMap<Long, Int>()
    // Cache for resolving runtime IDs to block identifiers
    private val identifierCache = ConcurrentHashMap<Int, String>()

    @Volatile private var loggedFirstSuccess = false
    @Volatile private var loggedFirstFailure = false

    fun init() {
        PacketEventBus.register(this)
        OverlayLogger.i(TAG, "WorldBlockTracker initialized")
    }

    fun reset() {
        sections.clear(); insertOrder.clear(); overrides.clear()
        loggedFirstSuccess = false; loggedFirstFailure = false
        OverlayLogger.i(TAG, "WorldBlockTracker reset")
    }

    /**
     * When the client first connects, it informs the server about its blob cache preference.
     * A real Bedrock client typically sends this with enabled=true; the server then sends
     * LevelChunkPacket in blob format (hash references) instead of block-by-block.
     *
     * Our decodeSubChunkBlocks does NOT understand this format (it requires a different
     * structure: blob hash list + separate BlobCacheMissResponse/BlobCacheAckPacket flow).
     * Therefore, handleLevelChunkPacket silently returns when cachingEnabled=true,
     * leaving the terrain empty.
     *
     * We intercept this client->server packet here and force it to false, preventing
     * the server from sending blob-format chunks. This ensures the server always sends
     * raw/inline chunk data, which our existing decode code can handle.
     */
    @Volatile private var loggedCacheOverride = false

    private fun handleClientCacheStatus(p: ClientCacheStatusPacket) {
        if (p.isSupported) {
            p.isSupported = false
            if (!loggedCacheOverride) {
                loggedCacheOverride = true
                OverlayLogger.i(TAG, "Forced ClientCacheStatusPacket enabled=true -> false (blob-chunk disabled)")
            }
        }
    }

    override fun onPacket(event: PacketEvent) {
        when (val p = event.packet) {
            is SubChunkPacket -> handleSubChunkPacket(p)
            is LevelChunkPacket -> handleLevelChunkPacket(p)
            is UpdateBlockPacket -> handleUpdateBlock(p)
            is UpdateSubChunkBlocksPacket -> handleUpdateSubChunkBlocks(p)
            is ClientCacheStatusPacket -> handleClientCacheStatus(p)
            is ChangeDimensionPacket -> reset()
            else -> {}
        }
    }

    fun hasAnyTerrainData(): Boolean = sections.isNotEmpty()

    fun getBlockIdentifier(x: Int, y: Int, z: Int): String? {
        // Check overrides first (individual block updates take precedence)
        val posKey = blockPosKey(x, y, z)
        overrides[posKey]?.let { return resolveIdentifier(it) }

        // Calculate section coordinates
        val cx = x shr 4
        val cz = z shr 4
        val sy = y shr 4
        val arr = sections[sectionKey(cx, sy, cz)] ?: return null

        // Get local coordinates within the section
        val lx = x and 15
        val ly = y and 15
        val lz = z and 15
        val idx = (ly shl 8) or (lz shl 4) or lx
        val runtimeId = arr[idx]
        return resolveIdentifier(runtimeId)
    }

    fun isBlock(x: Int, y: Int, z: Int, vararg identifiers: String): Boolean {
        val id = getBlockIdentifier(x, y, z) ?: return false
        return identifiers.any { it == id }
    }

    fun hasData(x: Int, y: Int, z: Int): Boolean {
        if (overrides.containsKey(blockPosKey(x, y, z))) return true
        val cx = x shr 4; val cz = z shr 4; val sy = y shr 4
        return sections.containsKey(sectionKey(cx, sy, cz))
    }

    private fun handleUpdateBlock(p: UpdateBlockPacket) {
        if (p.dataLayer != 0) return
        val runtimeId = runCatching { p.definition?.runtimeId }.getOrElse { null } ?: return
        val pos = p.blockPosition ?: return
        overrides[blockPosKey(pos.x, pos.y, pos.z)] = runtimeId
    }

    private fun handleUpdateSubChunkBlocks(p: UpdateSubChunkBlocksPacket) {
        for (entry in p.standardBlocks) {
            val runtimeId = runCatching { entry.definition?.runtimeId }.getOrElse { null } ?: continue
            val pos = entry.position ?: continue
            overrides[blockPosKey(pos.x, pos.y, pos.z)] = runtimeId
        }
    }

    private fun handleSubChunkPacket(p: SubChunkPacket) {
        for (sub in p.subChunks) {
            try {
                val pos = sub.position ?: continue
                val buf = sub.data ?: continue
                if (!buf.isReadable) continue

                val blocks = decodeSubChunkBlocks(buf.duplicate())
                if (blocks == null) {
                    if (!loggedFirstFailure) {
                        loggedFirstFailure = true
                        OverlayLogger.w(TAG, "SubChunk block decode failed (pos=$pos)")
                    }
                    continue
                }

                storeSection(pos.x, pos.y, pos.z, blocks)

                if (!loggedFirstSuccess) {
                    loggedFirstSuccess = true
                    OverlayLogger.i(TAG, "First SubChunk block decode successful: pos=$pos (${blocks.count { it != 0 }} non-air blocks)")
                }
            } catch (e: Exception) {
                OverlayLogger.v(TAG, "Error processing SubChunk: ${e.message}")
            }
        }
    }

    private fun handleLevelChunkPacket(p: LevelChunkPacket) {
        try {
            val subChunksLength = p.subChunksLength
            if (subChunksLength <= 0) return

            val cachingEnabled = p.isCachingEnabled()
            if (cachingEnabled) {
                // If we still reach this point, the ClientCacheStatusPacket override
                // didn't work (e.g., the relay might be creating/sending this packet
                // internally without passing through our bus). Now we log explicitly.
                if (!loggedFirstFailure) {
                    loggedFirstFailure = true
                    OverlayLogger.w(TAG, "LevelChunk received in blob-cache mode (isCachingEnabled=true) — decode skipped, check ClientCacheStatusPacket override")
                }
                return
            }

            val buf = p.data ?: return
            if (!buf.isReadable) return

            val cx = p.chunkX
            val cz = p.chunkZ

            val dim = EntityTracker.selfDimension
            // Overworld has sections starting at Y=-4, nether/end start at 0
            val minSectionY = if (dim == 0) -4 else 0

            val dup = buf.duplicate()
            var decodedCount = 0
            for (i in 0 until subChunksLength) {
                val sy = minSectionY + i
                val blocks = decodeSubChunkBlocks(dup)
                if (blocks == null) {
                    if (!loggedFirstFailure) {
                        loggedFirstFailure = true
                        OverlayLogger.w(TAG, "LevelChunk subchunk decode failed (chunk=($cx,$cz) i=$i/$subChunksLength)")
                    }
                    break
                }
                storeSection(cx, sy, cz, blocks)
                decodedCount++
            }

            if (decodedCount > 0 && !loggedFirstSuccess) {
                loggedFirstSuccess = true
                OverlayLogger.i(TAG, "First LevelChunk block decode successful: chunk=($cx,$cz) $decodedCount/$subChunksLength sections, minSectionY=$minSectionY")
            }
        } catch (e: Exception) {
            OverlayLogger.v(TAG, "Error processing LevelChunk: ${e.message}")
        }
    }

    private fun storeSection(cx: Int, sy: Int, cz: Int, blocks: IntArray) {
        val key = sectionKey(cx, sy, cz)
        sections[key] = blocks
        insertOrder.add(key)
        // Evict oldest sections if cache exceeds maximum size
        while (insertOrder.size > MAX_SECTIONS) {
            val old = insertOrder.poll() ?: break
            sections.remove(old)
        }
    }

    private fun decodeSubChunkBlocks(buf: ByteBuf): IntArray? {
        return try {
            buf.markReaderIndex()
            tryDecode(buf, skipYByte = false)
        } catch (_: Exception) {
            buf.resetReaderIndex()
            try {
                tryDecode(buf, skipYByte = true)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun tryDecode(buf: ByteBuf, skipYByte: Boolean): IntArray? {
        val version = buf.readUnsignedByte().toInt()
        val storageCount: Int
        when (version) {
            1 -> storageCount = 1
            8, 9 -> {
                storageCount = buf.readUnsignedByte().toInt()
                if (skipYByte) buf.readByte() // Skip the Y byte for version 8/9
            }
            else -> return null
        }
        if (storageCount <= 0 || storageCount > 8) return null

        var primary: IntArray? = null
        repeat(storageCount) { idx ->
            val storage = readBlockStorage(buf) ?: return null
            if (idx == 0) primary = storage
        }
        return primary
    }

    private fun readBlockStorage(buf: ByteBuf): IntArray? {
        val header = buf.readUnsignedByte().toInt()
        val bitsPerBlock = header ushr 1
        val isPersistent = (header and 1) == 1
        if (isPersistent) return null // Persistent storage is not supported
        if (bitsPerBlock !in intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 16)) return null

        if (bitsPerBlock == 0) {
            // All blocks are the same ID
            val id = readUnsignedVarInt(buf)
            return IntArray(SECTION_BLOCKS) { id }
        }

        // Read the block indices
        val blocksPerWord = 32 / bitsPerBlock
        val wordCount = (SECTION_BLOCKS + blocksPerWord - 1) / blocksPerWord
        val indices = IntArray(SECTION_BLOCKS)
        val mask = (1 shl bitsPerBlock) - 1
        var bi = 0
        repeat(wordCount) {
            val word = buf.readIntLE()
            var w = word
            var c = 0
            while (c < blocksPerWord && bi < SECTION_BLOCKS) {
                indices[bi] = w and mask
                w = w ushr bitsPerBlock
                bi++; c++
            }
        }

        // Read the palette
        val paletteSize = readUnsignedVarInt(buf)
        if (paletteSize <= 0 || paletteSize > 8192) return null
        val palette = IntArray(paletteSize) { readUnsignedVarInt(buf) }

        // Map indices to runtime IDs using the palette
        return IntArray(SECTION_BLOCKS) { i ->
            val p = indices[i]
            if (p < palette.size) palette[p] else 0
        }
    }

    private fun readUnsignedVarInt(buf: ByteBuf): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.readUnsignedByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IllegalStateException("VarInt too long")
        }
        return result
    }

    private fun resolveIdentifier(runtimeId: Int): String? {
        identifierCache[runtimeId]?.let { return it }
        val session = PacketEventBus.currentSession ?: return null
        val identifier = runCatching {
            when (val def = session.clientSession.peer.codecHelper.blockDefinitions?.getDefinition(runtimeId)) {
                is org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition -> def.identifier
                is com.oxclient.core.relay.Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
                else -> null
            }
        }.getOrElse { null } ?: return null
        identifierCache[runtimeId] = identifier
        return identifier
    }

    private fun sectionKey(cx: Int, sy: Int, cz: Int): Long {
        val cxL = cx.toLong() and 0xFFFFFFL
        val syL = (sy + 128).toLong() and 0xFFL // Offset Y by 128 to handle negative values
        val czL = cz.toLong() and 0xFFFFFFL
        return (cxL shl 32) or (syL shl 24) or czL
    }

    private fun blockPosKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL) shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}