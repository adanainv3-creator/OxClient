package com.oxclient.core.relay

import android.content.Context
import com.oxclient.ui.overlay.OverlayLogger
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.common.DefinitionRegistry
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry

/**
 * Definitions — WRelay'in Definitions objesiyle aynı mantık, CodecRegistry ile
 * uyumlu şekilde ÇOK VERSİYONLU hale getirildi.
 *
 * Neden gerekli: StartGamePacket bazı protokol versiyonlarında itemDefinitions'ı
 * BOŞ gönderebiliyor (bkz. GamingPacketListener "StartGame.itemDefinitions boş —
 * mevcut codec item paleti korunuyor" uyarısı). O durumda burada negotiate edilen
 * protocolVersion'a TAM uyan lokal palet devreye giriyor. Yanlış versiyon paleti
 * kullanılırsa runtimeId'ler kayar → item hiç çözülemez (totem, vs. null kalır).
 *
 * block_palette.nbt son 5-6 protokol versiyonunda değişmediği için TEK dosya,
 * tüm item palette versiyonlarına ortak fallback olarak kullanılıyor.
 * runtime_item_states ise versiyona göre ayrı dosyalar: assets/nbt/ altında
 * "runtime_item_statesvXXX.json" adlandırmasıyla (XXX = protocolVersion).
 *
 * Kullanım:
 *   OxClientApp.onCreate() içinde Definitions.init(context) çağır.
 *   AutoCodecListener içinde: Definitions.getClosestDefinitions(codec.protocolVersion)
 */
object Definitions {

    private const val TAG = "Definitions"
    private val VERSION_REGEX = Regex("v(\\d+)")

    data class VersionedDefinitions(
        val protocolVersion: Int,
        val blockDefinitions: DefinitionRegistry<BlockDefinition>,
        val blockDefinitionsHashed: DefinitionRegistry<BlockDefinition>,
        val itemDefinitions: DefinitionRegistry<ItemDefinition>
    )

    var cameraPresetDefinitions: DefinitionRegistry<NamedDefinition> =
        SimpleDefinitionRegistry.builder<NamedDefinition>().build()
        private set

    private val registry = LinkedHashMap<Int, VersionedDefinitions>()
    private val sortedVersions = mutableListOf<Int>()

    @Volatile var loaded = false
        private set

    /**
     * App başlarken bir kez çağrılır.
     * assets/nbt/ altındaki tüm block_palette*.nbt + runtime_item_states*.json
     * dosyalarını tarar, versiyon numaralarına göre eşleştirip yükler.
     */
    fun init(context: Context) {
        if (loaded) return
        try {
            loadAllVersions(context)
            sortedVersions.clear()
            sortedVersions.addAll(registry.keys)
            sortedVersions.sortDescending()

            if (sortedVersions.isEmpty()) {
                OverlayLogger.w(TAG, "Hiçbir definitions versiyonu yüklenemedi — boş registry ile devam ediliyor")
            } else {
                OverlayLogger.i(TAG, "Definitions yüklendi ✓ (${sortedVersions.size} versiyon: $sortedVersions)")
            }
            loaded = true
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Definitions yüklenemedi: ${e.message}", e)
        }
    }

    private fun loadAllVersions(context: Context) {
        val files = try {
            context.assets.list("nbt") ?: emptyArray()
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "assets/nbt/ listelenemedi: ${e.message}")
            emptyArray()
        }

        val blockFiles = files.filter { it.startsWith("block_palette") && it.endsWith(".nbt") }
        val itemFiles = files.filter { it.startsWith("runtime_item_states") && it.endsWith(".json") }

        if (itemFiles.isEmpty()) {
            OverlayLogger.w(TAG, "runtime_item_states*.json bulunamadı — item definitions boş kalacak")
        }

        // Versiyonlu block_palette dosyası varsa onu kullan, yoksa versiyonsuz olanı
        // (örn. "block_palette.nbt") TÜM versiyonlara ortak fallback yap.
        val versionedBlockFiles = blockFiles.mapNotNull { f -> extractVersion(f)?.let { it to f } }.toMap()
        val fallbackBlockFile = blockFiles.firstOrNull { extractVersion(it) == null }
            ?: blockFiles.firstOrNull() // hiç versiyonsuz yoksa eldeki herhangi biri

        if (fallbackBlockFile == null && versionedBlockFiles.isEmpty()) {
            OverlayLogger.w(TAG, "block_palette*.nbt bulunamadı — block definitions boş kalacak")
        }

        for (itemFile in itemFiles) {
            val version = extractVersion(itemFile)
            if (version == null) {
                OverlayLogger.w(TAG, "$itemFile içinde versiyon numarası bulunamadı (vXXX formatı bekleniyor) — atlanıyor")
                continue
            }

            val blockFile = versionedBlockFiles[version] ?: fallbackBlockFile
            if (blockFile == null) {
                OverlayLogger.w(TAG, "protocol=$version için block palette bulunamadı — atlanıyor")
                continue
            }

            try {
                val (blocks, blocksHashed) = loadBlockPalette(context, blockFile)
                val items = loadItemPalette(context, itemFile)
                registry[version] = VersionedDefinitions(version, blocks, blocksHashed, items)
                OverlayLogger.i(TAG, "protocol=$version yüklendi (block=$blockFile, item=$itemFile)")
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "protocol=$version yüklenemedi: ${e.message}", e)
            }
        }
    }

    private fun extractVersion(filename: String): Int? =
        VERSION_REGEX.find(filename)?.groupValues?.get(1)?.toIntOrNull()

    private fun loadBlockPalette(
        context: Context,
        filename: String
    ): Pair<DefinitionRegistry<BlockDefinition>, DefinitionRegistry<BlockDefinition>> {
        val stream = context.assets.open("nbt/$filename")
        val tag = NbtUtils.createGZIPReader(stream).use { it.readTag() }
        require(tag is NbtMap) { "$filename geçersiz format (NbtMap bekleniyor)" }

        val blocks = tag.getList("blocks", NbtType.COMPOUND)
        val normal = NbtBlockDefinitionRegistry(blocks, hashed = false)
        val hashed = NbtBlockDefinitionRegistry(blocks, hashed = true)
        return normal to hashed
    }

    private fun loadItemPalette(context: Context, filename: String): DefinitionRegistry<ItemDefinition> {
        val stream = context.assets.open("nbt/$filename")
        val json = stream.bufferedReader().use { it.readText() }
        val array = org.json.JSONArray(json)

        val map = Int2ObjectOpenHashMap<NbtItemDefinition>()
        var totemIndex = -1
        var totemRawId = -1
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            map.put(i, NbtItemDefinition(i, name)) // sıra = runtimeId, block_palette ile aynı mantık

            // ✅ TANI: totem_of_undying'in dosyada gerçekten nerede olduğunu logla.
            // Oyun içinde offhand/inventory log'larındaki runtimeId bununla eşleşmiyorsa,
            // bu dosya bu sunucunun (protokol=$filename) gerçek paletiyle uyuşmuyor demektir.
            if (name == "minecraft:totem_of_undying") {
                totemIndex = i
                totemRawId = obj.optInt("id", -1)
            }
        }
        OverlayLogger.i(
            TAG,
            "$filename: ${array.length()} item yüklendi | totem_of_undying → index(runtimeId)=$totemIndex rawId=$totemRawId " +
                if (totemIndex == -1) "(DOSYADA YOK — dosya eksik/yanlış!)" else ""
        )
        return NbtItemDefinitionRegistry(map)
    }

    /**
     * Tam eşleşme varsa onu, yoksa CodecRegistry.getClosestCodec ile AYNI mantıkla
     * talep edilenden küçük en yakın versiyonu, o da yoksa kayıtlı en eskiyi döner.
     */
    fun getClosestDefinitions(protocolVersion: Int): VersionedDefinitions {
        registry[protocolVersion]?.let { return it }

        check(sortedVersions.isNotEmpty()) {
            "Hiçbir definitions versiyonu yüklenemedi — assets/nbt/ içeriğini kontrol et"
        }

        val closest = sortedVersions.firstOrNull { it <= protocolVersion } ?: sortedVersions.last()
        if (closest != protocolVersion) {
            OverlayLogger.w(TAG, "protocol=$protocolVersion için tam eşleşme yok — en yakın versiyon kullanılıyor: $closest")
        }
        return registry[closest]!!
    }

    // ── NbtBlockDefinitionRegistry ────────────────────────────────────────

    class NbtBlockDefinitionRegistry(
        definitions: List<NbtMap>,
        hashed: Boolean
    ) : DefinitionRegistry<BlockDefinition> {

        private val map = Int2ObjectOpenHashMap<NbtBlockDefinition>()

        init {
            var counter = 0
            for (def in definitions) {
                val runtimeId = if (hashed) createHash(def) else counter++
                map.put(runtimeId, NbtBlockDefinition(runtimeId, def))
            }
        }

        override fun getDefinition(runtimeId: Int): BlockDefinition? = map.get(runtimeId)

        override fun isRegistered(definition: BlockDefinition?): Boolean =
            definition != null && map.get(definition.runtimeId) == definition

        @JvmRecord
        data class NbtBlockDefinition(val runtimeId: Int, val tag: NbtMap) : BlockDefinition {
            override fun getRuntimeId(): Int = runtimeId
        }

        private fun createHash(map: NbtMap): Int {
            // FNV-1a hash — WRelay BlockPaletteUtils ile aynı
            val name = map.getString("name") ?: ""
            val states = map.getCompound("states")
            val stateStr = states?.entries
                ?.sortedBy { it.key }
                ?.joinToString(",") { "${it.key}=${it.value}" } ?: ""
            val key = "$name:$stateStr"
            var hash = -0x7ee3779b
            for (c in key) {
                hash = hash xor c.code
                hash *= 0x01000193.toInt()
            }
            return hash
        }
    }

    // ── NbtItemDefinitionRegistry ─────────────────────────────────────────

    class NbtItemDefinitionRegistry(
        private val map: Int2ObjectOpenHashMap<NbtItemDefinition>
    ) : DefinitionRegistry<ItemDefinition> {
        override fun getDefinition(runtimeId: Int): ItemDefinition? = map.get(runtimeId)
        override fun isRegistered(definition: ItemDefinition?): Boolean =
            definition != null && map.get(definition.runtimeId) == definition
    }

    @JvmRecord
    data class NbtItemDefinition(val runtimeId: Int, val identifier: String) : ItemDefinition {
        override fun getRuntimeId(): Int = runtimeId
        override fun getIdentifier(): String = identifier
        override fun isComponentBased(): Boolean = false
    }
}
