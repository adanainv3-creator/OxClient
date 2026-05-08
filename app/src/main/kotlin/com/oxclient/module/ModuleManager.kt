package com.oxclient.module

import com.oxclient.module.combat.AutoTotem
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.KillAura
import com.oxclient.module.movement.TPAura
import kotlinx.coroutines.*

/**
 * ModuleManager
 *
 * Tüm modüllerin merkezi kayıt ve yaşam döngüsü yöneticisi.
 */
object ModuleManager {

    private val modules = mutableListOf<BaseModule>()

    // ── Kayıtlı Modüller ──────────────────────────────────────────────────
    val killAura   = KillAura()
    val criticals  = Criticals()
    val autoTotem  = AutoTotem()
    val tpAura     = TPAura()

    private val tickScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────
    //  BAŞLATMA
    // ─────────────────────────────────────────────────────────────────────

    fun initAll() {
        modules.clear()
        modules.addAll(listOf(killAura, criticals, autoTotem, tpAura))
        tickJob = tickScope.launch {
            while (isActive) {
                modules.filter { it.enabled }.forEach {
                    try { it.onTick() } catch (_: Exception) {}
                }
                delay(50L)
            }
        }
    }

    fun disableAll() {
        modules.forEach { if (it.enabled) it.disable() }
        tickJob?.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SORGU
    // ─────────────────────────────────────────────────────────────────────

    fun getAll(): List<BaseModule>                = modules.toList()
    fun getEnabled(): List<BaseModule>            = modules.filter { it.enabled }
    fun getByName(name: String): BaseModule?      = modules.find { it.name.equals(name, ignoreCase = true) }
    fun getByCategory(cat: BaseModule.Category)  = modules.filter { it.category == cat }
}
