package com.nexoraclient.utils

object BlockPalette {

    private val COLORS: List<Triple<String, Int, Int>> = listOf(
        Triple("minecraft:white_concrete", rgb(207, 213, 214), 0),
        Triple("minecraft:orange_concrete", rgb(224, 97, 1), 0),
        Triple("minecraft:magenta_concrete", rgb(169, 48, 159), 0),
        Triple("minecraft:light_blue_concrete", rgb(36, 137, 199), 0),
        Triple("minecraft:yellow_concrete", rgb(241, 175, 21), 0),
        Triple("minecraft:lime_concrete", rgb(94, 168, 24), 0),
        Triple("minecraft:pink_concrete", rgb(214, 101, 143), 0),
        Triple("minecraft:gray_concrete", rgb(54, 57, 61), 0),
        Triple("minecraft:light_gray_concrete", rgb(125, 125, 115), 0),
        Triple("minecraft:cyan_concrete", rgb(21, 119, 136), 0),
        Triple("minecraft:purple_concrete", rgb(100, 32, 156), 0),
        Triple("minecraft:blue_concrete", rgb(45, 47, 143), 0),
        Triple("minecraft:brown_concrete", rgb(96, 60, 32), 0),
        Triple("minecraft:green_concrete", rgb(73, 91, 36), 0),
        Triple("minecraft:red_concrete", rgb(142, 32, 32), 0),
        Triple("minecraft:black_concrete", rgb(8, 10, 15), 0),
        Triple("minecraft:white_wool", rgb(233, 236, 236), 0),
        Triple("minecraft:orange_wool", rgb(240, 118, 19), 0),
        Triple("minecraft:magenta_wool", rgb(189, 68, 179), 0),
        Triple("minecraft:light_blue_wool", rgb(58, 175, 217), 0),
        Triple("minecraft:yellow_wool", rgb(248, 198, 39), 0),
        Triple("minecraft:lime_wool", rgb(112, 185, 25), 0),
        Triple("minecraft:pink_wool", rgb(237, 141, 172), 0),
        Triple("minecraft:gray_wool", rgb(62, 68, 71), 0),
        Triple("minecraft:light_gray_wool", rgb(142, 142, 134), 0),
        Triple("minecraft:cyan_wool", rgb(21, 137, 145), 0),
        Triple("minecraft:purple_wool", rgb(121, 42, 172), 0),
        Triple("minecraft:blue_wool", rgb(53, 57, 157), 0),
        Triple("minecraft:brown_wool", rgb(114, 71, 40), 0),
        Triple("minecraft:green_wool", rgb(84, 109, 27), 0),
        Triple("minecraft:red_wool", rgb(160, 39, 34), 0),
        Triple("minecraft:black_wool", rgb(20, 21, 25), 0),
        Triple("minecraft:white_terracotta", rgb(209, 178, 161), 0),
        Triple("minecraft:orange_terracotta", rgb(161, 83, 37), 0),
        Triple("minecraft:magenta_terracotta", rgb(149, 88, 108), 0),
        Triple("minecraft:light_blue_terracotta", rgb(113, 108, 137), 0),
        Triple("minecraft:yellow_terracotta", rgb(186, 133, 36), 0),
        Triple("minecraft:lime_terracotta", rgb(103, 117, 53), 0),
        Triple("minecraft:pink_terracotta", rgb(161, 78, 78), 0),
        Triple("minecraft:gray_terracotta", rgb(57, 42, 35), 0),
        Triple("minecraft:light_gray_terracotta", rgb(135, 107, 98), 0),
        Triple("minecraft:cyan_terracotta", rgb(87, 91, 91), 0),
        Triple("minecraft:purple_terracotta", rgb(118, 70, 86), 0),
        Triple("minecraft:blue_terracotta", rgb(74, 59, 91), 0),
        Triple("minecraft:brown_terracotta", rgb(77, 51, 36), 0),
        Triple("minecraft:green_terracotta", rgb(76, 83, 42), 0),
        Triple("minecraft:red_terracotta", rgb(143, 61, 46), 0),
        Triple("minecraft:black_terracotta", rgb(37, 22, 16), 0),
        Triple("minecraft:terracotta", rgb(152, 94, 67), 0),
        Triple("minecraft:quartz_block", rgb(235, 229, 222), 0),
        Triple("minecraft:coal_block", rgb(16, 16, 16), 0),
        Triple("minecraft:iron_block", rgb(220, 220, 215), 0),
        Triple("minecraft:gold_block", rgb(247, 209, 72), 0),
        Triple("minecraft:redstone_block", rgb(172, 25, 17), 0),
        Triple("minecraft:lapis_block", rgb(30, 66, 134), 0),
        Triple("minecraft:emerald_block", rgb(42, 166, 89), 0),
        Triple("minecraft:diamond_block", rgb(100, 220, 209), 0),
        Triple("minecraft:netherrack", rgb(96, 39, 37), 0),
        Triple("minecraft:obsidian", rgb(15, 12, 22), 0),
        Triple("minecraft:sand", rgb(219, 206, 160), 0),
        Triple("minecraft:gravel", rgb(130, 127, 126), 0),
        Triple("minecraft:dirt", rgb(121, 85, 58), 0),
        Triple("minecraft:oak_planks", rgb(162, 130, 78), 0),
        Triple("minecraft:stone", rgb(125, 125, 125), 0),
        Triple("minecraft:cobblestone", rgb(122, 122, 122), 0)
    )

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    fun closestBlock(r: Int, g: Int, b: Int): String {
        var best = COLORS[0].first
        var bestDist = Int.MAX_VALUE
        for ((id, packed, _) in COLORS) {
            val pr = (packed shr 16) and 0xFF
            val pg = (packed shr 8) and 0xFF
            val pb = packed and 0xFF
            val dr = r - pr
            val dg = g - pg
            val db = b - pb
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = id
            }
        }
        return best
    }

    fun displayName(identifier: String): String =
        identifier.removePrefix("minecraft:").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
