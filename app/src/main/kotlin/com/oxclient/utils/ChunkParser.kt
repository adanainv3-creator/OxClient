package com.oxclient.utils

import com.oxclient.ui.overlay.OverlayLogger
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket

/**
 * ChunkParser — LevelChunkPacket'in ham (undecoded) payload'ından block-entity
 * NBT verisini çıkarır (sandık/spawner/shulker vb. — chunk ilk yüklendiğinde
 * zaten var olan bloklar için).
 *
 * ═══════════════════════════════════════════════════════════════════════
 * DÜRÜST UYARI — protokol versiyonuna göre kırılganlık:
 * ──────────────────────────────
 * Block SECTION (bitsPerBlock + palette) formatı 1.13'ten beri oldukça
 * stabil ve burada güvenle parse ediliyor. Ama section'lardan SONRA gelen
 * biome verisi ve "border blocks" kısmı, 1.18 öncesi/sonrası arasında
 * (2D biome ↔ 3D biome section) farklılaşıyor ve tam formatını protokol
 * versiyonuna göre burada garanti edemiyorum.
 *
 * Bu yüzden block section'ları KESİN olarak atladıktan sonra, geri kalan
 * bayt aralığında block-entity NBT compound'larını iki aşamalı arıyoruz:
 *   1) Doğrudan mevcut pozisyondan NBT parse dene (biome/border boşsa çalışır)
 *   2) Olmazsa, kalan baytlarda "TAG_Compound + boş isim" imzasını tarayıp
 *      her adayda NBT parse dene (biome verisi arada kalsa da entity'leri
 *      genelde yakalar).
 * Hiçbiri tutmazsa sessizce boş liste döner + log düşer — relay'in kendisi
 * (ham buffer passthrough) BUNDAN HİÇ ETKİLENMEZ, sadece ESP o chunk için
 * "ilk yükte" veri alamaz (UpdateBlockPacket/BlockEntityDataPacket ile
 * sonradan yine yakalar).
 * ═══════════════════════════════════════════════════════════════════════
 */
object ChunkParser {

    private const val TAG = "ChunkParser"
    private const val MAX_SUBCHUNKS = 64      // güvenlik sınırı — sonsuz döngü engeli
    private const val MAX_SCAN_ATTEMPTS = 512 // heuristic taramada deneme sınırı

    data class ParsedBlockEntity(val x: Int, val y: Int, val z: Int, val tag: NbtMap)

    /**
     * @param subChunkCount Paketten okunabiliyorsa ver (daha güvenilir). Bilinmiyorsa
     *                       null geç — o zaman section'lar "tanınan version byte'ı
     *                       bitene kadar" okunur (daha az güvenilir fallback).
     */
    fun extractBlockEntities(pkt: LevelChunkPacket, subChunkCount: Int? = null): List<ParsedBlockEntity> {
        val original = readDataField(pkt) ?: run {
            OverlayLogger.v(TAG, "LevelChunkPacket.data alanı okunamadı (API adı değişmiş olabilir)")
            return emptyList()
        }

        // Orijinal buffer'ı ASLA mutasyona uğratma — passthrough ondan bağımsız
        // olsa bile (relay ham byte kopyası kullanıyor), yine de savunmacı davran.
        val buf = original.duplicate()

        return try {
            val count = subChunkCount ?: reflectSubChunkCount(pkt) ?: run {
                OverlayLogger.v(TAG, "subChunkCount alınamadı, version-byte fallback kullanılacak")
                -1
            }

            if (count >= 0) skipKnownSubChunks(buf, count) else skipSubChunksByVersionByte(buf)

            val direct = tryParseCompoundsFrom(buf.duplicate())
            if (direct.isNotEmpty()) return direct

            OverlayLogger.v(TAG, "Doğrudan NBT parse boş döndü, tarama moduna geçiliyor")
            scanForCompounds(buf)
        } catch (e: Exception) {
            OverlayLogger.v(TAG, "Chunk parse edilemedi (${pkt.subChunkCount.let { "" }}): ${e.message}")
            emptyList()
        }
    }

    // ── Data alanına erişim (field adı sürüm/kütüphane değişikliğine karşı reflection) ──

    private fun readDataField(pkt: LevelChunkPacket): ByteBuf? {
        // Bilinen alan adı — doğrudan dene, olmazsa reflection'a düş.
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

    // ── Block section atlama (stabil kısım) ──────────────────────────────

    private fun skipKnownSubChunks(buf: ByteBuf, count: Int) {
        repeat(count.coerceAtMost(MAX_SUBCHUNKS)) {
            if (!buf.isReadable) return
            skipOneSubChunk(buf)
        }
    }

    /** subChunkCount bilinmiyorsa: tanınan version byte'ı görene kadar oku, ilk
     *  tanınmayan byte'ta dur ve reader'ı 1 geri sar (o byte biome/entity verisinin
     *  başlangıcı olabilir). Bu, count bilinen yönteme göre daha az güvenilir. */
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
            1 -> skipBlockStorage(buf) // legacy: tek örtük storage, storageCount byte'ı yok
            8, 9 -> {
                val storageCount = buf.readUnsignedByte().toInt()
                if (version == 9) buf.readByte() // subchunk Y index (signed)
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
            // Disk formatı ihtimaline karşı savunmacı dal — network'te normalde görülmez.
            throw IllegalStateException("Persistent (NBT) palette formatı desteklenmiyor")
        }

        if (bitsPerBlock == 0) {
            // Tek değerli storage: index yok, doğrudan 1 palette entry (VarInt).
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

    // ── Block entity NBT çıkarımı ─────────────────────────────────────────

    private fun tryParseCompoundsFrom(buf: ByteBuf): List<ParsedBlockEntity> {
        val result = mutableListOf<ParsedBlockEntity>()
        try {
            while (buf.isReadable) {
                val tag = readOneCompound(buf) ?: break
                toBlockEntity(tag)?.let { result.add(it) }
            }
        } catch (_: Exception) {
            // Bu yoldan devam edilemiyor — çağıran taraf scanForCompounds'a düşecek.
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
            // TAG_Compound(0x0A) + boş isim (VarInt uzunluk 0x00) imzasını ara.
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
                } catch (_: Exception) { /* yanlış pozitif — devam et */ }
            }
            pos++
        }
        if (result.isEmpty()) OverlayLogger.v(TAG, "Tarama modu da block entity bulamadı ($attempts deneme)")
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
