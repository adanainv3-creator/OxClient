package com.nexoraclient.module.misc

import com.nexoraclient.module.BaseModule
import com.nexoraclient.module.ModuleCategory
import com.nexoraclient.module.ModuleManager

/**
 * ComboShortcut
 *
 * Kendisi sıradan bir modül gibi davranır: açıldığında "Modules" ayarında
 * virgülle ayrılmış modül isimlerini birlikte açar, kapatıldığında birlikte
 * kapatır (ör. "Modules" = "KillAura, CrystalAura, AutoTotem").
 *
 * "Shortcut" ayarını açarsan, diğer modüllerdeki "Shortcut" ayarıyla birebir
 * aynı şekilde ModuleManager.shortcutModules() tarafından HUD kısayol
 * çubuğuna eklenir — yani 2, 3 veya daha fazla modülü tek dokunuşla birlikte
 * açıp kapatabilirsin. "Name" ayarına istediğin ismi yazabilirsin.
 *
 * NexoraClientApp.registerModules() içine istediğin sayıda örnek eklemen
 * yeterli, ör: ComboShortcut(1), ComboShortcut(2), ComboShortcut(3) ...
 */
class ComboShortcut(index: Int) : BaseModule(
    name        = "ComboShortcut$index",
    category    = ModuleCategory.MISC,
    description = "Birden fazla modülü tek kısayolla birlikte aç/kapat"
) {
    private val comboName = string("Name",    "Combo $index")
    private val targets   = string("Modules", "")
    private val shortcut  = bool  ("Shortcut", false)

    override fun onEnable() {
        super.onEnable()
        applyToTargets(true)
    }

    override fun onDisable() {
        applyToTargets(false)
        super.onDisable()
    }

    private fun applyToTargets(enabled: Boolean) {
        targets.value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { targetName ->
                val target = ModuleManager.byName(targetName) ?: return@forEach
                if (target === this) return@forEach
                if (enabled) ModuleManager.enable(target) else ModuleManager.disable(target)
            }
    }
}
