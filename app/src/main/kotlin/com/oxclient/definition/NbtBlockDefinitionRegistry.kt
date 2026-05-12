package com.oxclient.definition

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.common.DefinitionRegistry

/**
 * NbtBlockDefinitionRegistry — NBT block palette listesinden
 * runtime ID tabanlı BlockDefinition registry'si oluşturur.
 *
 * hashed=false → sıralı integer ID (vanilla)
 * hashed=true  → FNV-1a hash tabanlı ID (network hash modu)
 *
 * WRelay NbtBlockDefinitionRegistry'den birebir adapte edildi.
 */
class NbtBlockDefinitionRegistry(
    definitions: List<NbtMap>,
    hashed: Boolean
) : DefinitionRegistry<BlockDefinition> {

    private val map = Int2ObjectOpenHashMap<NbtBlockDefinition>()

    init {
        var counter = 0
        for (def in definitions) {
            val runtimeId = if (hashed) computeHash(def) else counter++
            map.put(runtimeId, NbtBlockDefinition(runtimeId, def))
        }
    }

    override fun getDefinition(runtimeId: Int): BlockDefinition? = map[runtimeId]

    override fun isRegistered(definition: BlockDefinition?): Boolean =
        definition != null && map[definition.runtimeId] === definition

    // ── FNV-1a hash (Bedrock network hash modu) ───────────────────────────

    private fun computeHash(tag: NbtMap): Int {
        val name    = tag.getString("name", "")
        val states  = tag.getCompound("states") ?: NbtMap.EMPTY
        val stateStr = states.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        val full = "$name:$stateStr"

        // FNV-1a 32-bit
        var hash = 0x811c9dc5.toInt()
        for (c in full) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return hash
    }

    // ── İç tanım sınıfı ───────────────────────────────────────────────────

    data class NbtBlockDefinition(
        private val _runtimeId: Int,
        val tag: NbtMap
    ) : BlockDefinition {
        override fun getRuntimeId(): Int = _runtimeId
    }
}
