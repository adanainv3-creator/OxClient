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

        // FIX (KillAura/KillAuraPro "bazen yumruk atıyor" sorunu): yukarıdaki
        // cache'ler yalnızca InventoryHelper modülü AÇIKKEN ve tick attığında
        // güncelleniyor. Modül kapalıyken ya da henüz ilk taramasını yapmadan
        // önce, kombat modülleri hâlâ "elde kılıç var mı" diye canlı bakabilsin
        // diye buradan bağımsız, o anki envanter snapshot'ını taze şekilde
        // tarayan yardımcılar sağlanıyor. Bunlar rule/plan'a değil, doğrudan
        // gerçek envanterdeki item identifier'larına bakar.
        fun findSwordSlotInHotbar(): Int? {
            val snapshot = runCatching { EntityTracker.getInventorySnapshot() }.getOrNull() ?: return null
            for (slot in 0 until HOTBAR_SIZE) {
                val item = snapshot[slot] ?: continue
                val id = InventoryUtil.resolveIdentifier(item) ?: continue
                if (id.endsWith("_sword")) return slot
            }
            return null
        }

        fun findTridentSlotInHotbar(): Int? {
            val snapshot = runCatching { EntityTracker.getInventorySnapshot() }.getOrNull() ?: return null
            for (slot in 0 until HOTBAR_SIZE) {
                val item = snapshot[slot] ?: continue
                val id = InventoryUtil.resolveIdentifier(item) ?: continue
                if (id == "minecraft:trident") return slot
            }
            return null
        }
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

    private data class SlotTarget(val identifier: String, val meta: Int, val minCount: Int)

    // ---------- Net isimlendirilmiş item grupları ----------
    // Eskiden "Rule 1 Item", "Rule 2 Item"... diye kafa karıştıran, hangi
    // slotun neye karşılık geldiği belli olmayan genel kurallar vardı.
    // Artık her item türü kendi adıyla ayrı bir grup: Sword, Trident,
    // Pickaxe, Gapple, End Crystal, Strength Potion — her biri kendi
    // "Slot Count" (kaç hotbar slotu ayrılsın) ve "Min Count" (slot başına
    // minimum adet) ayarına sahip. Dashboard'da artık "Sword Slot Count",
    // "Gapple Slot Count", "Trident Slot Count", "Strength Potion Slot Count"
    // gibi anlaşılır isimler görünür.
    private data class ItemGroup(
        val label     : String,
        val tierChoice: EnumSetting<HotbarItem>?,
        val fixedItem : HotbarItem?,
        val slotCount : IntSetting,
        val minCount  : IntSetting
    ) {
        val resolved: HotbarItem get() = fixedItem ?: tierChoice!!.value
    }

    private val swordTier = enum("Sword Type", HotbarItem.DiamondSword)
    private val pickaxeTier = enum("Pickaxe Type", HotbarItem.DiamondPickaxe)
    private val gappleTier = enum("Gapple Type", HotbarItem.GoldenApple)
    private val potionTier = enum("Strength Potion Type", HotbarItem.PotionStrength)

    private val groups: List<ItemGroup> = listOf(
        ItemGroup("Trident",        null,       HotbarItem.Trident,   int("Trident Slot Count", 1, 0, 5), int("Trident Min Count", 1, 1, 64)),
        ItemGroup("Sword",          swordTier,  null,                 int("Sword Slot Count",   1, 0, 5), int("Sword Min Count",   1, 1, 64)),
        ItemGroup("Pickaxe",        pickaxeTier,null,                 int("Pickaxe Slot Count", 1, 0, 5), int("Pickaxe Min Count", 1, 1, 64)),
        ItemGroup("Strength Potion",potionTier, null,                 int("Strength Potion Slot Count", 3, 0, 5), int("Strength Potion Min Count", 1, 1, 64)),
        ItemGroup("End Crystal",    null,       HotbarItem.EndCrystal,int("End Crystal Slot Count", 2, 0, 5), int("End Crystal Min Count", 16, 1, 64)),
        ItemGroup("Gapple",         gappleTier, null,                 int("Gapple Slot Count",  1, 0, 5), int("Gapple Min Count",  4, 1, 64))
    )

    // ---------- Serbest ekstra slot için (opsiyonel) özel item ----------
    private val customEnabled    = bool  ("Custom Item Enabled", false)
    private val customIdentifier = string("Custom Item Id",      "")
    private val customMeta       = int   ("Custom Item Meta",   -1, -1, 40)
    private val customSlotCount  = int   ("Custom Item Slot Count", 0, 0, 5)
    private val customMinCount   = int   ("Custom Item Min Count",  1, 1, 64)

    // ---------- Yağmur/Su durumunda trident önceliği ----------
    private val waterTridentEnabled = bool("Water/Rain Trident Priority", false)
    private val tridentSlot         = int ("Trident Priority Slot",       8, 0, HOTBAR_SIZE - 1)

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

        for (group in groups) {
            if (slot >= HOTBAR_SIZE) break
            val choice = group.resolved
            if (choice == HotbarItem.None) continue
            val targetId = choice.identifier ?: continue

            val count = group.slotCount.value.coerceAtMost(HOTBAR_SIZE - slot)
            repeat(count) {
                plan[slot] = SlotTarget(targetId, choice.meta, group.minCount.value)
                slot++
            }
        }

        if (customEnabled.value && slot < HOTBAR_SIZE) {
            val id = customIdentifier.value.trim()
            if (id.isNotEmpty()) {
                val count = customSlotCount.value.coerceAtMost(HOTBAR_SIZE - slot)
                repeat(count) {
                    plan[slot] = SlotTarget(id, customMeta.value, customMinCount.value)
                    slot++
                }
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
        val identifier = InventoryUtil.resolveIdentifier(item!!) ?: return false
        if (identifier != targetId) return false
        if (meta >= 0 && item.damage != meta) return false
        return true
    }

    // Herhangi bir kılıç türünü (wood/stone/iron/gold/diamond/netherite) yakalar,
    // kullanıcı hangi kılıcı kural olarak seçerse seçsin çalışsın diye.
    private fun isSwordIdentifier(identifier: String): Boolean =
        identifier.endsWith("_sword")
}
