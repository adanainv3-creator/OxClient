package com.nexoraclient.module.misc

import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.*
import com.nexoraclient.utils.InventoryUtil
import com.nexoraclient.utils.WorldBlockTracker
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import java.util.concurrent.ConcurrentHashMap

class InventoryHelper : BaseModule(
    name        = "InventoryHelper",
    category    = ModuleCategory.COMBAT,
    description = "Automatically fills/refreshes hotbar slots with selectable items"
) {
    companion object {
        private const val CHECK_INTERVAL_MS  = 300L
        private const val RESEND_COOLDOWN_MS = 300L
        private const val HOTBAR_SIZE        = 9

        // Yağmur veya su (fiziksel blok kontrolüyle) aktifken hangi slotun trident
        // için ayrıldığını dışarıya (KillAura/KillAuraPro) bildirmek için kullanılıyor.
        // İkisi de yoksa veya özellik kapalıysa null.
        @Volatile var currentTridentSlot: Int? = null
            private set

        // Yağmur/su yokken (yani "havada") normal kuralların kılıç için ayırdığı
        // slot — combat modülleri saldırıda hangi slotu kullanacağını buradan
        // gerçek konfigürasyona bakarak belirleyebilsin diye.
        @Volatile var currentSwordSlot: Int? = null
            private set

        @Volatile var currentCrystalSlot: Int? = null
            private set
    }

    enum class HotbarItem(val identifier: String?, val meta: Int) {
        None(null, -1),
        Custom(null, -1),

        Trident("minecraft:trident", -1),
        DiamondSword("minecraft:diamond_sword", -1),
        NetheriteSword("minecraft:netherite_sword", -1),
        DiamondPickaxe("minecraft:diamond_pickaxe", -1),
        NetheritePickaxe("minecraft:netherite_pickaxe", -1),
        Obsidian("minecraft:obsidian", -1),
        EndCrystal("minecraft:end_crystal", -1),
        GoldenApple("minecraft:golden_apple", -1),
        EnchantedGoldenApple("minecraft:enchanted_golden_apple", -1),

        PotionStrength("minecraft:potion", 31),
        PotionStrengthLong("minecraft:potion", 32),
        PotionStrengthEnhanced("minecraft:potion", 33),
        PotionHealing("minecraft:potion", 21),
        PotionHealingEnhanced("minecraft:potion", 22),
        PotionRegeneration("minecraft:potion", 28),
        PotionRegenerationLong("minecraft:potion", 29),
        PotionFireResistance("minecraft:potion", 12),
        PotionFireResistanceLong("minecraft:potion", 13),
        PotionSwiftness("minecraft:potion", 14),
        PotionSwiftnessLong("minecraft:potion", 15),
        PotionSlowFalling("minecraft:potion", 40),
        PotionSlowFallingLong("minecraft:potion", 41),
        PotionInvisibility("minecraft:potion", 7),
        PotionInvisibilityLong("minecraft:potion", 8),
        PotionWaterBreathing("minecraft:potion", 19),
        PotionWaterBreathingLong("minecraft:potion", 20)
    }

    private data class ItemRule(
        val item            : EnumSetting<HotbarItem>,
        val customIdentifier: StringSetting,
        val customMeta      : IntSetting,
        val slotCount       : IntSetting,
        val minCount        : IntSetting
    )

    private data class SlotTarget(val identifier: String, val meta: Int, val minCount: Int)

    private val defaultItems      = arrayOf(
        HotbarItem.Trident, HotbarItem.DiamondSword, HotbarItem.DiamondPickaxe,
        HotbarItem.PotionStrength, HotbarItem.EndCrystal, HotbarItem.GoldenApple,
        HotbarItem.None, HotbarItem.None, HotbarItem.None
    )
    private val defaultSlotCounts = intArrayOf(1, 1, 1, 3, 2, 1, 0, 0, 0)
    private val defaultMinCounts  = intArrayOf(1, 1, 1, 1, 20, 4, 1, 1, 1)

    private val rules: List<ItemRule> = (0 until HOTBAR_SIZE).map { i ->
        ItemRule(
            item             = enum  ("Rule ${i + 1} Item",       defaultItems[i]),
            customIdentifier = string("Rule ${i + 1} Custom Id",  ""),
            customMeta       = int   ("Rule ${i + 1} Custom Meta", -1, -1, 40),
            slotCount        = int   ("Rule ${i + 1} Slot Count",  defaultSlotCounts[i], 0, HOTBAR_SIZE),
            minCount         = int   ("Rule ${i + 1} Min Count",   defaultMinCounts[i], 1, 64)
        )
    }

    // ---------- Yağmur/Su durumunda trident önceliği ----------
    private val waterTridentEnabled = bool("Water/Rain Trident Priority", false)
    private val tridentSlot         = int ("Trident Slot",               8, 0, HOTBAR_SIZE - 1)

    private val lastSendMs = ConcurrentHashMap<Int, Long>()
    @Volatile private var tickJob: kotlinx.coroutines.Job? = null

    override fun onEnable() {
        super.onEnable()
        lastSendMs.clear()
        currentTridentSlot = null
        currentSwordSlot = null
        currentCrystalSlot = null
        tickJob = launchTickLoop(CHECK_INTERVAL_MS) { checkAndRefill() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        currentTridentSlot = null
        currentSwordSlot = null
        currentCrystalSlot = null
        super.onDisable()
    }

    private fun buildSlotPlan(): Array<SlotTarget?> {
        val plan = arrayOfNulls<SlotTarget>(HOTBAR_SIZE)
        var slot = 0
        for (rule in rules) {
            if (slot >= HOTBAR_SIZE) break
            val choice = rule.item.value
            if (choice == HotbarItem.None) continue

            val targetId = if (choice == HotbarItem.Custom) rule.customIdentifier.value.trim()
                           else choice.identifier ?: continue
            if (targetId.isEmpty()) continue
            val targetMeta = if (choice == HotbarItem.Custom) rule.customMeta.value else choice.meta

            val count = rule.slotCount.value.coerceAtMost(HOTBAR_SIZE - slot)
            repeat(count) {
                plan[slot] = SlotTarget(targetId, targetMeta, rule.minCount.value)
                slot++
            }
        }
        return plan
    }

    private fun checkAndRefill() {
        val session = PacketEventBus.currentSession ?: return
        val snapshot = EntityTracker.getInventorySnapshot()
        val plan = buildSlotPlan()

        // Kılıcın normal kurallara göre hangi slotta olduğunu belirle — bu,
        // yağmur/su yokken ("havada") combat modüllerinin döneceği slot.
        currentSwordSlot = plan.indexOfFirst { it != null && isSwordIdentifier(it.identifier) }
            .takeIf { it >= 0 }

        currentCrystalSlot = plan.indexOfFirst { it?.identifier == "minecraft:end_crystal" }
            .takeIf { it >= 0 }

        // Yağmur yağıyorsa VEYA gerçekten suya değiyorsak (WorldBlockTracker ile
        // blok bazlı kontrol) ve özellik açıksa, ayrılan slotu zorla trident yap —
        // o slot için tanımlı normal kuralın önüne geçer.
        val wetActive = EntityTracker.selfIsRaining || WorldBlockTracker.isPlayerInWater()
        if (waterTridentEnabled.value && wetActive) {
            val slot = tridentSlot.value.coerceIn(0, HOTBAR_SIZE - 1)
            plan[slot] = SlotTarget(HotbarItem.Trident.identifier!!, HotbarItem.Trident.meta, 1)
            currentTridentSlot = slot
        } else {
            currentTridentSlot = null
        }

        for (slot in 0 until HOTBAR_SIZE) {
            val target = plan[slot] ?: continue

            val current = snapshot[slot]
            val matches = itemMatches(current, target.identifier, target.meta)
            val count = current?.count ?: 0

            if (matches && count >= target.minCount) continue

            val now = System.currentTimeMillis()
            val last = lastSendMs[slot] ?: 0L
            if (now - last < RESEND_COOLDOWN_MS) continue

            val source = findBestSource(snapshot, slot, target.identifier, target.meta) ?: continue

            lastSendMs[slot] = now
            InventoryUtil.sendInventoryMove(
                session           = session,
                sourceContainer   = ContainerSlotType.HOTBAR_AND_INVENTORY,
                sourceContainerId = 0,
                sourceSlot        = source.first,
                sourceItem        = source.second,
                destContainer     = ContainerSlotType.HOTBAR_AND_INVENTORY,
                destContainerId   = 0,
                destSlot          = slot,
                destItem          = current ?: ItemData.AIR
            )
        }
    }

    private fun findBestSource(
        snapshot: Map<Int, ItemData>, excludeSlot: Int, targetId: String, meta: Int
    ): Pair<Int, ItemData>? {
        var best: Pair<Int, ItemData>? = null

        for (slot in InventoryUtil.INV_START..InventoryUtil.INV_END) {
            val item = snapshot[slot] ?: continue
            if (!itemMatches(item, targetId, meta)) continue
            if (best == null || item.count > best!!.second.count) best = slot to item
        }
        if (best == null) {
            for (slot in InventoryUtil.HOTBAR_START..InventoryUtil.HOTBAR_END) {
                if (slot == excludeSlot) continue
                val item = snapshot[slot] ?: continue
                if (!itemMatches(item, targetId, meta)) continue
                if (best == null || item.count > best!!.second.count) best = slot to item
            }
        }
        return best
    }

    private fun itemMatches(item: ItemData?, targetId: String, meta: Int): Boolean {
        if (InventoryUtil.isEmpty(item)) return false
        val identifier = runCatching { item!!.definition?.identifier }.getOrNull() ?: return false
        if (identifier != targetId) return false
        if (meta >= 0 && item!!.damage != meta) return false
        return true
    }

    // Herhangi bir kılıç türünü (wood/stone/iron/gold/diamond/netherite) yakalar,
    // kullanıcı hangi kılıcı kural olarak seçerse seçsin çalışsın diye.
    private fun isSwordIdentifier(identifier: String): Boolean =
        identifier.endsWith("_sword")
}
