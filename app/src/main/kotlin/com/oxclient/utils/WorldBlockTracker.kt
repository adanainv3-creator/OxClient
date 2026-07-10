package com.oxclient.utils

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
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

    private val sections = ConcurrentHashMap<Long, IntArray>()

    private val insertOrder = ConcurrentLinkedQueue<Long>()

    private const val MAX_SECTIONS = 4096

    private val overrides = ConcurrentHashMap<Long, Int>()

    private val identifierCache = ConcurrentHashMap<Int, String>()

    @Volatile private var loggedFirstSuccess = false
    @Volatile private var loggedFirstFailure = false

    fun init() {
        PacketEventBus.register(this)
    }

    fun reset() {
        sections.clear(); insertOrder.clear(); overrides.clear()
        loggedFirstSuccess = false; loggedFirstFailure = false
    }

    @Volatile private var loggedCacheOverride = false

    private fun handleClientCacheStatus(p: ClientCacheStatusPacket) {
        if (p.isSupported) {
            p.isSupported = false
            if (!loggedCacheOverride) {
                loggedCacheOverride = true
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

        val posKey = blockPosKey(x, y, z)
        overrides[posKey]?.let { return resolveIdentifier(it) }

        val cx = x shr 4
        val cz = z shr 4
        val sy = y shr 4
        val arr = sections[sectionKey(cx, sy, cz)] ?: return null

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
                    }
                    continue
                }

                storeSection(pos.x, pos.y, pos.z, blocks)

                if (!loggedFirstSuccess) {
                    loggedFirstSuccess = true
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun handleLevelChunkPacket(p: LevelChunkPacket) {
        try {
            val subChunksLength = resolveSubChunkCount(p)
            if (subChunksLength <= 0) {
                if (!loggedFirstFailure) {
                    loggedFirstFailure = true
                }
                return
            }

            val cachingEnabled = p.isCachingEnabled()
            if (cachingEnabled) {

                if (!loggedFirstFailure) {
                    loggedFirstFailure = true
                }
                return
            }

            val buf = p.data ?: return
            if (!buf.isReadable) return

            val cx = p.chunkX
            val cz = p.chunkZ

            val dim = EntityTracker.selfDimension

            val minSectionY = if (dim == 0) -4 else 0

            val dup = buf.duplicate()
            var decodedCount = 0
            for (i in 0 until subChunksLength) {
                val sy = minSectionY + i
                val blocks = decodeSubChunkBlocks(dup)
                if (blocks == null) {
                    if (!loggedFirstFailure) {
                        loggedFirstFailure = true
                    }
                    break
                }
                storeSection(cx, sy, cz, blocks)
                decodedCount++
            }

            if (decodedCount > 0 && !loggedFirstSuccess) {
                loggedFirstSuccess = true
            }
        } catch (e: Exception) {
        }
    }

    private fun resolveSubChunkCount(p: LevelChunkPacket): Int {
        val direct = try { p.subChunksLength } catch (_: Throwable) { 0 }
        if (direct > 0) return direct

        for (methodName in listOf("getSubChunkLimit", "getSubChunkCount", "getSectionCount")) {
            try {
                val m = p.javaClass.getMethod(methodName)
                val v = m.invoke(p)
                if (v is Int && v > 0) return v
            } catch (_: Exception) {}
        }
        return direct
    }

    private fun storeSection(cx: Int, sy: Int, cz: Int, blocks: IntArray) {
        val key = sectionKey(cx, sy, cz)
        sections[key] = blocks
        insertOrder.add(key)

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
                if (skipYByte) buf.readByte()
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
        if (isPersistent) return null
        if (bitsPerBlock !in intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 16)) return null

        if (bitsPerBlock == 0) {

            val id = readUnsignedVarInt(buf)
            return IntArray(SECTION_BLOCKS) { id }
        }

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

        val paletteSize = readUnsignedVarInt(buf)
        if (paletteSize <= 0 || paletteSize > 8192) return null
        val palette = IntArray(paletteSize) { readUnsignedVarInt(buf) }

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
        val syL = (sy + 128).toLong() and 0xFFL
        val czL = cz.toLong() and 0xFFFFFFL
        return (cxL shl 32) or (syL shl 24) or czL
    }

    private fun blockPosKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL) shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
