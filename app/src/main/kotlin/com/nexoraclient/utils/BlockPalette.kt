package com.nexoraclient.utils

object BlockPalette {

    data class BlockEntry(val identifier: String, val rgb: Int)

    val ALL: List<BlockEntry> = listOf(
        BlockEntry("minecraft:white_concrete",       rgb(207, 213, 214)),
        BlockEntry("minecraft:orange_concrete",      rgb(224, 97,  1  )),
        BlockEntry("minecraft:magenta_concrete",     rgb(169, 48,  159)),
        BlockEntry("minecraft:light_blue_concrete",  rgb(36,  137, 199)),
        BlockEntry("minecraft:yellow_concrete",      rgb(241, 175, 21 )),
        BlockEntry("minecraft:lime_concrete",        rgb(94,  168, 24 )),
        BlockEntry("minecraft:pink_concrete",        rgb(214, 101, 143)),
        BlockEntry("minecraft:gray_concrete",        rgb(54,  57,  61 )),
        BlockEntry("minecraft:light_gray_concrete",  rgb(125, 125, 115)),
        BlockEntry("minecraft:cyan_concrete",        rgb(21,  119, 136)),
        BlockEntry("minecraft:purple_concrete",      rgb(100, 32,  156)),
        BlockEntry("minecraft:blue_concrete",        rgb(45,  47,  143)),
        BlockEntry("minecraft:brown_concrete",       rgb(96,  60,  32 )),
        BlockEntry("minecraft:green_concrete",       rgb(73,  91,  36 )),
        BlockEntry("minecraft:red_concrete",         rgb(142, 32,  32 )),
        BlockEntry("minecraft:black_concrete",       rgb(8,   10,  15 )),
        BlockEntry("minecraft:white_wool",           rgb(233, 236, 236)),
        BlockEntry("minecraft:orange_wool",          rgb(240, 118, 19 )),
        BlockEntry("minecraft:magenta_wool",         rgb(189, 68,  179)),
        BlockEntry("minecraft:light_blue_wool",      rgb(58,  175, 217)),
        BlockEntry("minecraft:yellow_wool",          rgb(248, 198, 39 )),
        BlockEntry("minecraft:lime_wool",            rgb(112, 185, 25 )),
        BlockEntry("minecraft:pink_wool",            rgb(237, 141, 172)),
        BlockEntry("minecraft:gray_wool",            rgb(62,  68,  71 )),
        BlockEntry("minecraft:light_gray_wool",      rgb(142, 142, 134)),
        BlockEntry("minecraft:cyan_wool",            rgb(21,  137, 145)),
        BlockEntry("minecraft:purple_wool",          rgb(121, 42,  172)),
        BlockEntry("minecraft:blue_wool",            rgb(53,  57,  157)),
        BlockEntry("minecraft:brown_wool",           rgb(114, 71,  40 )),
        BlockEntry("minecraft:green_wool",           rgb(84,  109, 27 )),
        BlockEntry("minecraft:red_wool",             rgb(160, 39,  34 )),
        BlockEntry("minecraft:black_wool",           rgb(20,  21,  25 )),
        BlockEntry("minecraft:white_terracotta",     rgb(209, 178, 161)),
        BlockEntry("minecraft:orange_terracotta",    rgb(161, 83,  37 )),
        BlockEntry("minecraft:magenta_terracotta",   rgb(149, 88,  108)),
        BlockEntry("minecraft:light_blue_terracotta",rgb(113, 108, 137)),
        BlockEntry("minecraft:yellow_terracotta",    rgb(186, 133, 36 )),
        BlockEntry("minecraft:lime_terracotta",      rgb(103, 117, 53 )),
        BlockEntry("minecraft:pink_terracotta",      rgb(161, 78,  78 )),
        BlockEntry("minecraft:gray_terracotta",      rgb(57,  42,  35 )),
        BlockEntry("minecraft:light_gray_terracotta",rgb(135, 107, 98 )),
        BlockEntry("minecraft:cyan_terracotta",      rgb(87,  91,  91 )),
        BlockEntry("minecraft:purple_terracotta",    rgb(118, 70,  86 )),
        BlockEntry("minecraft:blue_terracotta",      rgb(74,  59,  91 )),
        BlockEntry("minecraft:brown_terracotta",     rgb(77,  51,  36 )),
        BlockEntry("minecraft:green_terracotta",     rgb(76,  83,  42 )),
        BlockEntry("minecraft:red_terracotta",       rgb(143, 61,  46 )),
        BlockEntry("minecraft:black_terracotta",     rgb(37,  22,  16 )),
        BlockEntry("minecraft:terracotta",           rgb(152, 94,  67 )),
        BlockEntry("minecraft:quartz_block",         rgb(235, 229, 222)),
        BlockEntry("minecraft:coal_block",           rgb(16,  16,  16 )),
        BlockEntry("minecraft:iron_block",           rgb(220, 220, 215)),
        BlockEntry("minecraft:gold_block",           rgb(247, 209, 72 )),
        BlockEntry("minecraft:redstone_block",       rgb(172, 25,  17 )),
        BlockEntry("minecraft:lapis_block",          rgb(30,  66,  134)),
        BlockEntry("minecraft:emerald_block",        rgb(42,  166, 89 )),
        BlockEntry("minecraft:diamond_block",        rgb(100, 220, 209)),
        BlockEntry("minecraft:netherrack",           rgb(96,  39,  37 )),
        BlockEntry("minecraft:obsidian",             rgb(15,  12,  22 )),
        BlockEntry("minecraft:sand",                 rgb(219, 206, 160)),
        BlockEntry("minecraft:gravel",               rgb(130, 127, 126)),
        BlockEntry("minecraft:dirt",                 rgb(121, 85,  58 )),
        BlockEntry("minecraft:oak_planks",           rgb(162, 130, 78 )),
        BlockEntry("minecraft:stone",                rgb(125, 125, 125)),
        BlockEntry("minecraft:cobblestone",          rgb(122, 122, 122)),
    )

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    /** Palette renk değeri → Compose Color'a çevirme (preview için) */
    fun colorOf(identifier: String): Int {
        return ALL.firstOrNull { it.identifier == identifier }?.rgb ?: 0x888888
    }

    /**
     * Tüm paletten en yakın bloğu döndürür.
     * [available] boşsa yine tüm paleti kullanır (geriye dönük uyumluluk).
     */
    fun closestBlock(r: Int, g: Int, b: Int, available: Set<String> = emptySet()): String {
        val pool = if (available.isEmpty()) ALL else ALL.filter { it.identifier in available }
        if (pool.isEmpty()) return ALL[0].identifier
        var best = pool[0].identifier
        var bestDist = Int.MAX_VALUE
        for ((id, packed) in pool) {
            val pr = (packed shr 16) and 0xFF
            val pg = (packed shr 8) and 0xFF
            val pb = packed and 0xFF
            val dr = r - pr; val dg = g - pg; val db = b - pb
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) { bestDist = dist; best = id }
        }
        return best
    }

    fun displayName(identifier: String): String =
        identifier.removePrefix("minecraft:").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
