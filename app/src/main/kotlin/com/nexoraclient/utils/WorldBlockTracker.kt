package com.rubidiumclient.utils

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import io.netty.buffer.ByteBuf
import com.rubidiumclient.core.proxy.EntityTracker
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor

object WorldBlockTracker : PacketEventBus.PacketListener {

    private const val TAG = "WorldBlockTracker"
    private const val SECTION_BLOCKS = 4096

    private val sections = ConcurrentHashMap<Long, IntArray>()

    // FIX: persistent/NBT-paletli subchunk'lar için palet indexi -> identifier
    // string eşlemesi. Eskiden bu tip subchunk'lar tamamen "-1 = bilinmiyor"
    // olarak atılıyordu (aşağıdaki readBlockStorage yorumuna bak) — artık NBT
    // palette'i gerçekten okuyup "name" alanını çıkarıyoruz, o yüzden bu
    // subchunk'lardaki bloklar da normal şekilde sorgulanabiliyor.
    private val persistentPalettes = ConcurrentHashMap<Long, Array<String?>>()

    private val insertOrder = ConcurrentLinkedQueue<Long>()

    private const val MAX_SECTIONS = 4096

    private val overrides = ConcurrentHashMap<Long, Int>()

    private val identifierCache = ConcurrentHashMap<Int, String>()

    init {
        // NOT: init() çağrısının unutulması/gecikmesi durumuna karşı savunma —
        // WorldBlockTracker referans alınır alınmaz (örn. hasAnyTerrainData()
        // çağrısıyla) kendini otomatik kaydeder. register() idempotent olduğu
        // için dışarıdan ayrıca init() çağırmak hâlâ güvenli ve tavsiye edilir
        // (asıl kritik nokta: PacketEventBus.setSession(session) her yeni
        // bağlantıda çağrıldığında WorldBlockTracker.init() de çağrılmalı —
        // aksi halde PacketEventBus.clear() sonrası (reconnect) bu obje bir
        // daha ASLA yeniden kaydolmaz çünkü Kotlin object'i sadece bir kez
        // initialize olur).
        register()
    }

    fun init() = register()

    private fun register() {
        PacketEventBus.register(this)
    }

    fun reset() {
        sections.clear(); insertOrder.clear(); overrides.clear(); persistentPalettes.clear()
    }

    @Volatile private var loggedCacheOverride = false

    // KRİTİK FIX: KillAura.kt'deki headlock fix'iyle birebir aynı kök sebep —
    // event.cancelAndReplace(p) çağrılmadan yapılan mutation'lar hiçbir zaman
    // server'a ulaşmıyor, relay ham (decode edilmemiş) wire byte'larını gönderiyor.
    // Bu yüzden isSupported = false ataması burada yapılsa bile server hâlâ
    // isSupported = true olarak görüyordu, blob-cache modunda kalmaya devam
    // ediyordu ve LevelChunkPacket'ler cachingEnabled=true olarak gelmeye devam
    // ediyordu — handleLevelChunkPacket bu durumda hiçbir şeyi decode etmeden
    // return ediyor, yani sections HİÇBİR ZAMAN dolmuyordu (AutoMapArt "No chunk
    // data" hatasının asıl kaynağı buydu).
    private fun handleClientCacheStatus(event: PacketEvent, p: ClientCacheStatusPacket) {
        if (p.isSupported) {
            p.isSupported = false
            if (!loggedCacheOverride) {
                loggedCacheOverride = true
            }
            event.cancelAndReplace(p)
        }
    }

    override fun onPacket(event: PacketEvent) {
        when (val p = event.packet) {
            is SubChunkPacket -> handleSubChunkPacket(p)
            is LevelChunkPacket -> handleLevelChunkPacket(p)
            is UpdateBlockPacket -> handleUpdateBlock(p)
            is UpdateSubChunkBlocksPacket -> handleUpdateSubChunkBlocks(p)
            is ClientCacheStatusPacket -> handleClientCacheStatus(event, p)
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
        val key = sectionKey(cx, sy, cz)
        val arr = sections[key] ?: return null

        val lx = x and 15
        val ly = y and 15
        val lz = z and 15
        val idx = (ly shl 8) or (lz shl 4) or lx
        val runtimeId = arr[idx]

        // FIX: persistent-palette sentinel (<= -2). Normal runtime id'ler her
        // zaman >= 0 olduğu için bu aralık çakışmıyor. -1 eski "bilinmiyor"
        // sentinel'i olarak geriye dönük uyumluluk için hâlâ null döndürüyor.
        if (runtimeId <= -2) {
            val paletteIdx = -(runtimeId + 2)
            val names = persistentPalettes[key] ?: return null
            val name = names.getOrNull(paletteIdx)
            return if (name.isNullOrBlank()) null else name
        }
        if (runtimeId < 0) return null

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

    private val WATER_IDS = arrayOf(
        "minecraft:water", "minecraft:flowing_water", "minecraft:bubble_column"
    )

    // Oyuncunun gerçekten suya değip değmediğini dünya blok verisinden (gerçek
    // zemin/blok kontrolü) okuyoruz — sadece yağmur/hava durumuna değil,
    // fiziksel olarak suda olup olmamaya bakan güvenilir kontrol bu.
    // Hem ayak hem göz hizası kontrol edilir: yüzeyde yüzerken (baş dışarıda)
    // veya tamamen dalmışken de "suda" sayılsın diye.
    fun isPlayerInWater(): Boolean {
        val bx = floor(EntityTracker.selfX).toInt()
        val bz = floor(EntityTracker.selfZ).toInt()
        val feetY = floor(EntityTracker.selfY).toInt()
        val eyeY  = floor(EntityTracker.selfY + 1.2f).toInt()

        if (!hasData(bx, feetY, bz) && !hasData(bx, eyeY, bz)) return false

        return isBlock(bx, feetY, bz, *WATER_IDS) || isBlock(bx, eyeY, bz, *WATER_IDS)
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
        // KRİTİK FIX: sub.position mutlak section koordinatı DEĞİL — paketin
        // origin'ine (isteğin atıldığı merkez konum) göre RELATİF bir offset
        // (dx,dy,dz). Bunu doğrudan mutlak kabul edip storeSection'a vermek,
        // bloğu tamamen yanlış bir section key'i altında saklıyordu (origin'e
        // yakın küçük delta değerleri altında — yani pratikte çoğunlukla
        // self'in kendi konumuna yakın bir yerde). Sonucunda hedefin GERÇEK
        // yüksekliği sorgulandığında ya hiç veri bulunamıyordu (aynı seviye:
        // delta farklı bir key'e denk geliyordu) ya da tesadüfen self'in kendi
        // seviyesindeki veriyle eşleşiyordu (hedef bir seviye fark edince) —
        // CrystalAura'nın "hedefin seviyesi yerine benim seviyeme kristal
        // koyuyor" bulgusunun asıl kök nedeni buydu.
        val origin = resolveSubChunkOrigin(p)
        for (sub in p.subChunks) {
            try {
                val rel = sub.position ?: continue
                val buf = sub.data ?: continue
                if (!buf.isReadable) continue

                val decoded = decodeSubChunkBlocks(buf.duplicate())
                if (decoded == null) continue

                storeSection(origin.x + rel.x, origin.y + rel.y, origin.z + rel.z, decoded.first, decoded.second)
            } catch (e: Exception) {
            }
        }
    }

    // Kütüphane sürümüne göre alan adı değişebildiğinden resolveSubChunkCount
    // ile aynı savunmacı reflection yaklaşımı: birkaç olası isim deneniyor.
    private fun resolveSubChunkOrigin(p: SubChunkPacket): org.cloudburstmc.math.vector.Vector3i {
        for (name in listOf("centerPosition", "position", "origin", "basePosition")) {
            try {
                val m = p.javaClass.getMethod(name)
                val v = m.invoke(p)
                if (v is org.cloudburstmc.math.vector.Vector3i) return v
            } catch (_: Exception) {}
        }
        return org.cloudburstmc.math.vector.Vector3i.ZERO
    }

    private fun handleLevelChunkPacket(p: LevelChunkPacket) {
        try {
            val subChunksLength = resolveSubChunkCount(p)
            if (subChunksLength <= 0) return

            val cachingEnabled = p.isCachingEnabled()
            if (cachingEnabled) return

            val buf = p.data ?: return
            if (!buf.isReadable) return

            val cx = p.chunkX
            val cz = p.chunkZ

            val dim = EntityTracker.selfDimension

            val minSectionY = if (dim == 0) -4 else 0

            val dup = buf.duplicate()
            for (i in 0 until subChunksLength) {
                val sy = minSectionY + i
                val decoded = decodeSubChunkBlocks(dup) ?: break
                storeSection(cx, sy, cz, decoded.first, decoded.second)
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

    private fun storeSection(cx: Int, sy: Int, cz: Int, blocks: IntArray, names: Array<String?>?) {
        val key = sectionKey(cx, sy, cz)
        sections[key] = blocks
        if (names != null) persistentPalettes[key] = names else persistentPalettes.remove(key)
        insertOrder.add(key)

        while (insertOrder.size > MAX_SECTIONS) {
            val old = insertOrder.poll() ?: break
            sections.remove(old)
            persistentPalettes.remove(old)
        }
    }

    // FIX: artık (IntArray, Array<String?>?) çifti dönüyor. İkinci değer sadece
    // primary storage persistent formatındaysa dolu oluyor, aksi halde null.
    private fun decodeSubChunkBlocks(buf: ByteBuf): Pair<IntArray, Array<String?>?>? {
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

    private fun tryDecode(buf: ByteBuf, skipYByte: Boolean): Pair<IntArray, Array<String?>?>? {
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
        var primaryNames: Array<String?>? = null
        repeat(storageCount) { idx ->
            val storage = readBlockStorage(buf) ?: return null
            if (idx == 0) { primary = storage.first; primaryNames = storage.second }
        }
        val p = primary ?: return null
        return p to primaryNames
    }

    // FIX (ANA DÜZELTME): Eskiden persistent (NBT paletli) storage tamamen
    // atlanıp bloğun TÜMÜ (4096 blok) "-1 = bilinmiyor" olarak işaretleniyordu.
    // Bu, subchunk içinde chest/kapı/tabela gibi TEK bir özel bloklu blok
    // storage'ı NBT paletine geçirdiğinde, o subchunk'taki obsidian/taş/her
    // şeyin de "bilinmiyor" sayılması demekti — AnchorAura ve CrystalAura'nın
    // "below block" ve "searchPlaceBase" kontrolleri bu yüzden sürekli null
    // dönüyor, iki modül de PvP haritalarında (chest/kapı/tabela çok yaygın)
    // neredeyse hiç çalışmıyordu.
    //
    // Artık: index dizisini normal (word-packed) formatla AYNI ŞEKİLDE okuyoruz,
    // ardından palette'i NBT reader ile gerçekten parse edip her compound'dan
    // "name" alanını çıkarıyoruz. Sonuç, indices[i] -> paletteNames[indices[i]]
    // eşlemesiyle gerçek identifier'lara ulaşılabiliyor. Bunu negatif sentinel
    // (-(paletteIndex+2)) ile aynı IntArray içinde encode edip getBlockIdentifier
    // içinde çözüyoruz — storage formatını değiştirmeden geriye dönük uyumlu.
    private fun readBlockStorage(buf: ByteBuf): Pair<IntArray, Array<String?>?>? {
        val header = buf.readUnsignedByte().toInt()
        val bitsPerBlock = header ushr 1
        val isPersistent = (header and 1) == 1
        if (bitsPerBlock !in intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 16)) return null

        // bitsPerBlock == 0 && !persistent: tek runtimeId'lik "uniform" storage,
        // index array'i yok, palette de tek elemanlı VarInt.
        if (bitsPerBlock == 0 && !isPersistent) {
            val id = readUnsignedVarInt(buf)
            return IntArray(SECTION_BLOCKS) { id } to null
        }

        // Index dizisini oku (persistent olsun olmasın format aynı word-packed
        // yapı). bitsPerBlock == 0 && persistent ise (tek elemanlı persistent
        // palette) indices hep 0 kalır (IntArray default).
        val indices = IntArray(SECTION_BLOCKS)
        if (bitsPerBlock > 0) {
            val blocksPerWord = 32 / bitsPerBlock
            val wordCount = (SECTION_BLOCKS + blocksPerWord - 1) / blocksPerWord
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
        }

        if (isPersistent) {
            val paletteSize = readUnsignedVarInt(buf)
            if (paletteSize <= 0 || paletteSize > 8192) return null
            val names = arrayOfNulls<String>(paletteSize)
            repeat(paletteSize) { pIdx ->
                names[pIdx] = try {
                    io.netty.buffer.ByteBufInputStream(buf).use { stream ->
                        org.cloudburstmc.nbt.NbtUtils.createNetworkReader(stream).use { reader ->
                            val tag = reader.readTag()
                            (tag as? org.cloudburstmc.nbt.NbtMap)?.getString("name")
                        }
                    }
                } catch (_: Exception) { null }
            }
            val encoded = IntArray(SECTION_BLOCKS) { i ->
                val p = indices[i]
                if (p < names.size) -(p + 2) else -1
            }
            return encoded to names
        }

        val paletteSize = readUnsignedVarInt(buf)
        if (paletteSize <= 0 || paletteSize > 8192) return null
        val palette = IntArray(paletteSize) { readUnsignedVarInt(buf) }

        val result = IntArray(SECTION_BLOCKS) { i ->
            val p = indices[i]
            if (p < palette.size) palette[p] else 0
        }
        return result to null
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

    internal fun resolveIdentifier(runtimeId: Int): String? {
        identifierCache[runtimeId]?.let { return it }
        val session = PacketEventBus.currentSession ?: return null

        fun extract(def: Any?): String? = when (def) {
            is org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition -> def.identifier
            is com.rubidiumclient.core.relay.Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
            else -> null
        }

        // Birincil kaynak: aktif session'ın kendi registry'si
        val primary = runCatching {
            extract(session.clientSession.peer.codecHelper.blockDefinitions?.getDefinition(runtimeId))
        }.getOrNull()

        if (primary != null) {
            identifierCache[runtimeId] = primary
            return primary
        }

        // Fallback: CrystalAura.getBlockDefinition() ile aynı mantık —
        // session registry'si boş/uyumsuzsa en yakın protokol tanımına düş
        val fallback = runCatching {
            extract(
                com.rubidiumclient.core.relay.Definitions
                    .getClosestDefinitions(session.activeCodec.protocolVersion)
                    .blockDefinitions
                    ?.getDefinition(runtimeId)
            )
        }.getOrNull() ?: return null

        identifierCache[runtimeId] = fallback
        return fallback
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
