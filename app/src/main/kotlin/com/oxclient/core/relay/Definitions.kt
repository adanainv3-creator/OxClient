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
 * Definitions — WRelay'in Definitions objesiyle aynı mantık.
 *
 * RequestNetworkSettingsPacket gelince codec helper'a set edilir.
 * Bu sayede paketi decode etmeden önce definitions hazır olur
 * ve "çok oyuncuya bağlanılıyor" ekranında takılma olmaz.
 *
 * Kullanım:
 *   OxClientApp.onCreate() içinde Definitions.init(context) çağır.
 */
object Definitions {

    private const val TAG = "Definitions"

    var itemDefinitions: DefinitionRegistry<ItemDefinition> =
        SimpleDefinitionRegistry.builder<ItemDefinition>().build()
        private set

    var blockDefinitions: DefinitionRegistry<BlockDefinition> =
        SimpleDefinitionRegistry.builder<BlockDefinition>().build()
        private set

    var blockDefinitionsHashed: DefinitionRegistry<BlockDefinition> =
        SimpleDefinitionRegistry.builder<BlockDefinition>().build()
        private set

    var cameraPresetDefinitions: DefinitionRegistry<NamedDefinition> =
        SimpleDefinitionRegistry.builder<NamedDefinition>().build()
        private set

    @Volatile var loaded = false
        private set

    /**
     * App başlarken bir kez çağrılır.
     * block_palette.nbt dosyasını assets/nbt/ klasöründen yükler.
     */
    fun init(context: Context) {
        if (loaded) return
        try {
            loadBlockPalette(context)
            loaded = true
            OverlayLogger.i(TAG, "Definitions yüklendi ✓")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Definitions yüklenemedi: ${e.message}", e)
        }
    }

    private fun loadBlockPalette(context: Context) {
        // assets/nbt/block_palette.nbt dosyasını yükle
        val stream = try {
            context.assets.open("nbt/block_palette.nbt")
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "block_palette.nbt bulunamadı: ${e.message} — boş definitions kullanılıyor")
            return
        }

        val tag = NbtUtils.createGZIPReader(stream).use { it.readTag() }
        if (tag !is NbtMap) {
            OverlayLogger.w(TAG, "block_palette.nbt geçersiz format")
            return
        }

        val blocks = tag.getList("blocks", NbtType.COMPOUND)
        blockDefinitions       = NbtBlockDefinitionRegistry(blocks, hashed = false)
        blockDefinitionsHashed = NbtBlockDefinitionRegistry(blocks, hashed = true)

        OverlayLogger.i(TAG, "Block palette yüklendi: ${blocks.size} blok")
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
            val name    = map.getString("name") ?: ""
            val states  = map.getCompound("states")
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
}
