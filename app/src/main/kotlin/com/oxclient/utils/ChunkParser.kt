package com.oxclient.utils

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket

object ChunkParser {

    private const val TAG = "ChunkParser"
    private const val MAX_SUBCHUNKS = 64
    private const val MAX_SCAN_ATTEMPTS = 512

    data class ParsedBlockEntity(val x: Int, val y: Int, val z: Int, val tag: NbtMap)

    fun extractBlockEntities(pkt: LevelChunkPacket, subChunkCount: Int? = null): List<ParsedBlockEntity> {
        val original = readDataField(pkt) ?: run {
            return emptyList()
        }

        val buf = original.duplicate()

        return try {
            val count = subChunkCount ?: reflectSubChunkCount(pkt) ?: run {
                -1
            }

            if (count >= 0) skipKnownSubChunks(buf, count) else skipSubChunksByVersionByte(buf)

            val direct = tryParseCompoundsFrom(buf.duplicate())
            if (direct.isNotEmpty()) return direct

            scanForCompounds(buf)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readDataField(pkt: LevelChunkPacket): ByteBuf? {

        try { return pkt.data } catch (_: Throwable) {}
        return try {
            val m = pkt.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterCount == 0 }
            m?.invoke(pkt) as? ByteBuf
        } catch (_: Exception) { null }
    }

    private fun reflectSubChunkCount(pkt: LevelChunkPacket): Int? {
        for (methodName in listOf("getSubChunkCount", "getSubChunkLimit", "getSectionCount")) {
            try {
                val m = pkt.javaClass.getMethod(methodName)
                val v = m.invoke(pkt)
                if (v is Int && v in 0..MAX_SUBCHUNKS) return v
            } catch (_: Exception) {}
        }
        return null
    }

    private fun skipKnownSubChunks(buf: ByteBuf, count: Int) {
        repeat(count.coerceAtMost(MAX_SUBCHUNKS)) {
            if (!buf.isReadable) return
            skipOneSubChunk(buf)
        }
    }

    private fun skipSubChunksByVersionByte(buf: ByteBuf) {
        var i = 0
        while (buf.isReadable && i < MAX_SUBCHUNKS) {
            buf.markReaderIndex()
            val version = buf.readUnsignedByte().toInt()
            if (version != 1 && version != 8 && version != 9) {
                buf.resetReaderIndex()
                return
            }
            buf.resetReaderIndex()
            if (!trySkipOneSubChunk(buf)) return
            i++
        }
    }

    private fun trySkipOneSubChunk(buf: ByteBuf): Boolean = try {
        skipOneSubChunk(buf); true
    } catch (_: Exception) { false }

    private fun skipOneSubChunk(buf: ByteBuf) {
        val version = buf.readUnsignedByte().toInt()
        when (version) {
            1 -> skipBlockStorage(buf)
            8, 9 -> {
                val storageCount = buf.readUnsignedByte().toInt()
                if (version == 9) buf.readByte()
                repeat(storageCount) { skipBlockStorage(buf) }
            }
            else -> throw IllegalStateException("Tanınmayan subchunk version=$version")
        }
    }

    private fun skipBlockStorage(buf: ByteBuf) {
        val header = buf.readUnsignedByte().toInt()
        val bitsPerBlock = header ushr 1
        val isPersistent = (header and 1) == 1

        if (isPersistent) {

            throw IllegalStateException("Persistent (NBT) palette formatı desteklenmiyor")
        }

        if (bitsPerBlock == 0) {

            readUnsignedVarInt(buf)
            return
        }

        val blocksPerWord = 32 / bitsPerBlock
        val wordCount = (4096 + blocksPerWord - 1) / blocksPerWord
        buf.skipBytes(wordCount * 4)

        val paletteSize = readUnsignedVarInt(buf)
        repeat(paletteSize) { readUnsignedVarInt(buf) }
    }

    private fun readUnsignedVarInt(buf: ByteBuf): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.readUnsignedByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IllegalStateException("VarInt çok uzun")
        }
        return result
    }

    private fun tryParseCompoundsFrom(buf: ByteBuf): List<ParsedBlockEntity> {
        val result = mutableListOf<ParsedBlockEntity>()
        try {
            while (buf.isReadable) {
                val tag = readOneCompound(buf) ?: break
                toBlockEntity(tag)?.let { result.add(it) }
            }
        } catch (_: Exception) {

        }
        return result
    }

    private fun scanForCompounds(buf: ByteBuf): List<ParsedBlockEntity> {
        val result = mutableListOf<ParsedBlockEntity>()
        var attempts = 0
        val start = buf.readerIndex()
        val end = buf.writerIndex()
        var pos = start

        while (pos < end - 1 && attempts < MAX_SCAN_ATTEMPTS) {

            if (buf.getByte(pos) == 0x0A.toByte() && buf.getByte(pos + 1) == 0x00.toByte()) {
                attempts++
                val dup = buf.duplicate()
                dup.readerIndex(pos)
                try {
                    val tag = readOneCompound(dup)
                    if (tag != null) {
                        toBlockEntity(tag)?.let { result.add(it) }
                        pos = dup.readerIndex()
                        continue
                    }
                } catch (_: Exception) {  }
            }
            pos++
        }
        return result
    }

    private fun readOneCompound(buf: ByteBuf): NbtMap? {
        if (!buf.isReadable) return null
        ByteBufInputStream(buf).use { stream ->
            NbtUtils.createNetworkReader(stream).use { reader ->
                val tag = reader.readTag()
                return tag as? NbtMap
            }
        }
    }

    private fun toBlockEntity(tag: NbtMap): ParsedBlockEntity? {
        val x = tag.getInt("x", Int.MIN_VALUE)
        val y = tag.getInt("y", Int.MIN_VALUE)
        val z = tag.getInt("z", Int.MIN_VALUE)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || z == Int.MIN_VALUE) return null
        return ParsedBlockEntity(x, y, z, tag)
    }
}
