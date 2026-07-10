package com.oxclient.core.relay

import android.content.Context
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.common.DefinitionRegistry
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry

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

    fun init(context: Context) {
        if (loaded) return
        try {
            loadAllVersions(context)
            sortedVersions.clear()
            sortedVersions.addAll(registry.keys)
            sortedVersions.sortDescending()
            loaded = true
        } catch (e: Exception) {
        }
    }

    private fun loadAllVersions(context: Context) {
        val files = try {
            context.assets.list("nbt") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }

        val blockFiles = files.filter { it.startsWith("block_palette") && it.endsWith(".nbt") }
        val itemFiles = files.filter { it.startsWith("runtime_item_states") && it.endsWith(".json") }

        val versionedBlockFiles = blockFiles.mapNotNull { f -> extractVersion(f)?.let { it to f } }.toMap()
        val fallbackBlockFile = blockFiles.firstOrNull { extractVersion(it) == null }
            ?: blockFiles.firstOrNull()

        for (itemFile in itemFiles) {
            val version = extractVersion(itemFile)
            if (version == null) {
                continue
            }

            val blockFile = versionedBlockFiles[version] ?: fallbackBlockFile
            if (blockFile == null) {
                continue
            }

            try {
                val (blocks, blocksHashed) = loadBlockPalette(context, blockFile)
                val items = loadItemPalette(context, itemFile)
                registry[version] = VersionedDefinitions(version, blocks, blocksHashed, items)
            } catch (e: Exception) {
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
            map.put(i, NbtItemDefinition(i, name))

            if (name == "minecraft:totem_of_undying") {
                totemIndex = i
                totemRawId = obj.optInt("id", -1)
            }
        }
        return NbtItemDefinitionRegistry(map)
    }

    fun getClosestDefinitions(protocolVersion: Int): VersionedDefinitions {
        registry[protocolVersion]?.let { return it }

        check(sortedVersions.isNotEmpty()) {
            "Hiçbir definitions versiyonu yüklenemedi — assets/nbt/ içeriğini kontrol et"
        }

        val closest = sortedVersions.firstOrNull { it <= protocolVersion } ?: sortedVersions.last()
        return registry[closest]!!
    }

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
