package com.rubidiumclient.utils

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
            val count = subChunkCount ?: resolveSubChunkCount(pkt) ?: run {
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

    // FIX (Xray'in hiçbir sey bulamamasinin asil sebebi): burada sadece reflection
    // method isimleri deneniyordu (getSubChunkCount/getSubChunkLimit/getSectionCount),
    // gercek LevelChunkPacket sinifinda bu isimler yok — WorldBlockTracker'in
    // resolveSubChunkCount'unda oldugu gibi asil calisan kaynak direkt
    // pkt.subChunksLength property'si. Bu deneme hic yapilmadigi icin bu fonksiyon
    // her zaman null donuyor ve extractOreBlocks/extractBlockEntities her paket
    // icin bos liste ile hemen cikiyordu.
    private fun resolveSubChunkCount(pkt: LevelChunkPacket): Int? {
        val direct = try { pkt.subChunksLength } catch (_: Throwable) { 0 }
        if (direct in 1..MAX_SUBCHUNKS) return direct

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

    data class OreHit(val x: Int, val y: Int, val z: Int, val runtimeId: Int)

    // Overworld (1.18+) section indeksi -4'ten başlar => dünya Y -64.
    // Nether/End farklı taban kullanır (0'dan başlar) — dimension'a göre bunu
    // çağıran taraf override etmeli, burada default Overworld varsayılıyor.
    const val OVERWORLD_MIN_SECTION = -4

    /**
     * Chunk içindeki hedef bloklarını (isTarget(runtimeId) == true olanları) world
     * koordinatlarıyla döner. Set<Int> yerine predicate kullanıyoruz çünkü ore runtime
     * ID'leri artık StartGamePacket'ten önceden statik bir palet listesiyle değil,
     * OreTracker tarafından ilk görüldüğünde tembel (lazy) çözülüyor — bu da
     * block-network-ID hashing kullanan sunucularda (StartGamePacket'te palet listesi
     * hiç gelmeyebilir) doğru çalışır. Predicate her subchunk'ın palette dizisindeki
     * benzersiz girişler için bir kez çağrılır (4096 blok için değil), bu yüzden
     * maliyeti ihmal edilebilir düzeydedir.
     * Persistent (NBT) palette formatlı subchunk'lar desteklenmiyor (skipBlockStorage ile aynı kısıt).
     */
    fun extractOreBlocks(
        pkt: LevelChunkPacket,
        isTarget: (Int) -> Boolean,
        minSectionIndex: Int = OVERWORLD_MIN_SECTION,
        subChunkCount: Int? = null
    ): List<OreHit> {
        val original = readDataField(pkt) ?: return emptyList()
        val buf = original.duplicate()

        val chunkX = pkt.chunkX
        val chunkZ = pkt.chunkZ
        val count = subChunkCount ?: resolveSubChunkCount(pkt) ?: return emptyList()

        val result = mutableListOf<OreHit>()
        return try {
            var sectionIndex = minSectionIndex
            repeat(count.coerceAtMost(MAX_SUBCHUNKS)) {
                if (buf.isReadable) {
                    decodeOneSubChunk(buf, chunkX, chunkZ, sectionIndex * 16, isTarget, result)
                }
                sectionIndex++
            }
            result
        } catch (e: Exception) {
            result // o ana kadar toplanan sonuçları döndür
        }
    }

    private fun decodeOneSubChunk(
        buf: ByteBuf, chunkX: Int, chunkZ: Int, baseY: Int,
        isTarget: (Int) -> Boolean, out: MutableList<OreHit>
    ) {
        val version = buf.readUnsignedByte().toInt()
        when (version) {
            1 -> decodeBlockStorage(buf, chunkX, chunkZ, baseY, isTarget, out)
            8, 9 -> {
                val storageCount = buf.readUnsignedByte().toInt()
                if (version == 9) buf.readByte()
                repeat(storageCount) { layer ->
                    // 0. katman gerçek bloklar, sonraki katmanlar genelde su/waterlogging — atla
                    if (layer == 0) decodeBlockStorage(buf, chunkX, chunkZ, baseY, isTarget, out)
                    else skipBlockStorage(buf)
                }
            }
            else -> throw IllegalStateException("Tanınmayan subchunk version=$version")
        }
    }

    private fun decodeBlockStorage(
        buf: ByteBuf, chunkX: Int, chunkZ: Int, baseY: Int,
        isTarget: (Int) -> Boolean, out: MutableList<OreHit>
    ) {
        val header = buf.readUnsignedByte().toInt()
        val bitsPerBlock = header ushr 1
        val isPersistent = (header and 1) == 1

        if (isPersistent) {
            throw IllegalStateException("Persistent (NBT) palette formatı desteklenmiyor")
        }

        if (bitsPerBlock == 0) {
            // Tüm subchunk tek bir blok (genelde air/stone) — cevher olma ihtimali pratikte yok, atla.
            readUnsignedVarInt(buf)
            return
        }

        val blocksPerWord = 32 / bitsPerBlock
        val wordCount = (4096 + blocksPerWord - 1) / blocksPerWord
        val mask = (1 shl bitsPerBlock) - 1

        val words = IntArray(wordCount)
        for (w in 0 until wordCount) words[w] = buf.readIntLE()

        val paletteSize = readUnsignedVarInt(buf)
        val palette = IntArray(paletteSize)
        for (p in 0 until paletteSize) palette[p] = readUnsignedVarInt(buf)

        // Predicate'i sadece paletin benzersiz girişleri için çağırıyoruz (tipik olarak
        // birkaç ile birkaç yüz arası), 4096 voksel için değil.
        val paletteIsTarget = BooleanArray(paletteSize) { isTarget(palette[it]) }

        for (idx in 0 until 4096) {
            val w = idx / blocksPerWord
            val slot = idx % blocksPerWord
            val paletteIndex = (words[w] ushr (slot * bitsPerBlock)) and mask
            if (paletteIndex >= palette.size) continue
            if (!paletteIsTarget[paletteIndex]) continue

            // idx -> yerel (lx,ly,lz): WorldBlockTracker.getBlockIdentifier'daki
            // (ly shl 8) or (lz shl 4) or lx kodlamasinin tersi — Y en anlamlı bit,
            // sonra Z, sonra X (eskiden X en anlamlı bit sanilip tersten okunuyordu,
            // bulunan her cevherin konumu tamamen yanlis hesaplaniyordu).
            val ly = idx shr 8
            val lz = (idx shr 4) and 0xF
            val lx = idx and 0xF

            out.add(OreHit(chunkX * 16 + lx, baseY + ly, chunkZ * 16 + lz, palette[paletteIndex]))
        }
    }
}
