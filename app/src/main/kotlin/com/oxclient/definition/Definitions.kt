package com.oxclient.definition

import android.util.Log
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.common.DefinitionRegistry
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry
import java.io.InputStream

/**
 * Definitions — Bedrock protokolü için gerekli tanım registry'leri.
 * WRelay Definitions'dan adapte edildi.
 *
 * block_palette.nbt assets/nbt/ altında bulunmalıdır.
 */
object Definitions {

    private const val TAG = "Definitions"

    var itemDefinitions: DefinitionRegistry<ItemDefinition> =
        SimpleDefinitionRegistry.builder<ItemDefinition>().build()

    var blockDefinitions: DefinitionRegistry<BlockDefinition> =
        SimpleDefinitionRegistry.builder<BlockDefinition>().build()

    var blockDefinitionsHashed: DefinitionRegistry<BlockDefinition> =
        SimpleDefinitionRegistry.builder<BlockDefinition>().build()

    var cameraPresetDefinitions: DefinitionRegistry<NamedDefinition> =
        SimpleDefinitionRegistry.builder<NamedDefinition>().build()

    /**
     * block_palette.nbt'den block tanımlarını yükler.
     * Application onCreate içinde çağır.
     *
     * Dosya: app/src/main/assets/nbt/block_palette.nbt (gzip NBT)
     */
    fun loadBlockPalette() {
        try {
            val stream: InputStream = Definitions::class.java.classLoader
                ?.getResourceAsStream("nbt/block_palette.nbt")
                ?: run {
                    Log.w(TAG, "block_palette.nbt bulunamadı, boş registry kullanılıyor")
                    return
                }

            val tag = stream.use { loadGzipNBT(it) }

            if (tag is NbtMap) {
                val blocks = tag.getList("blocks", NbtType.COMPOUND)
                blockDefinitions        = NbtBlockDefinitionRegistry(blocks, hashed = false)
                blockDefinitionsHashed  = NbtBlockDefinitionRegistry(blocks, hashed = true)
                Log.i(TAG, "Block palette yüklendi: ${blocks.size} blok")
            } else {
                Log.w(TAG, "block_palette.nbt beklenen formatta değil")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Block palette yükleme hatası: ${e.message}", e)
        }
    }

    private fun loadGzipNBT(stream: InputStream): Any =
        NbtUtils.createGZIPReader(stream).use { it.readTag() }
}
