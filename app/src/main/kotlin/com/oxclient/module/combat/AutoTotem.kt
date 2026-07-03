package com.oxclient.module.combat

import android.util.Log
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.InventoryUtil
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Ölümsüzlük totemini otomatik takar"
) {
    enum class TriggerMode { HealthBased, Always, OnDamage }

    private val healthThreshold = float("Health Threshold", 10f, 1f,  20f)
    private val delay           = int  ("Delay",            50,  0,   500)
    private val triggerMode     = enum ("Trigger Mode",     TriggerMode.HealthBased)
    private val reEquipAlways   = bool ("Re-Equip Always",  false)
    private val shortcut        = bool ("Shortcut",         false)

    companion object {
        private const val TAG = "AutoTotem"
    }

    @Volatile private var currentHealth   = 20f
    @Volatile private var totemSlot       = -1
    // ✅ FIX: sabit "702" yerine slottaki gerçek totem stack'inin netId'si saklanıyor.
    // MobEquipmentPacket, server'ın doğru stack'i tanıyabilmesi için o stack'e ait
    // gerçek netId'yi bekler — sabit bir sayı göndermek isteği başarısız kılıyordu.
    @Volatile private var totemNetId      = 0
    @Volatile private var offhandHasTotem = false
    @Volatile private var lastEquipMs     = 0L
    @Volatile private var lastDamageMs    = 0L

    private var watchJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        currentHealth = 20f; totemSlot = -1; offhandHasTotem = false
        watchJob = launchTickLoop(20L) { watchTick() }
    }

    override fun onDisable() { watchJob?.cancel(); super.onDisable() }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is UpdateAttributesPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val prev = currentHealth
                pkt.attributes.firstOrNull { it.name == "minecraft:health" }?.let {
                    if (it.value < prev) lastDamageMs = System.currentTimeMillis()
                    currentHealth = it.value
                }
            }
            is InventoryContentPacket -> {
                if (pkt.containerId != 0) return
                totemSlot = -1; offhandHasTotem = false
                pkt.contents.forEachIndexed { slot, item ->
                    if (InventoryUtil.isTotem(item)) when {
                        slot == InventoryUtil.OFFHAND_SLOT && !offhandHasTotem -> offhandHasTotem = true
                        totemSlot == -1 -> { totemSlot = slot; totemNetId = item.netId }
                    }
                }
            }
            is InventorySlotPacket -> {
                if (pkt.containerId != 0) return
                when (pkt.slot) {
                    InventoryUtil.OFFHAND_SLOT -> offhandHasTotem = InventoryUtil.isTotem(pkt.item)
                    in 0..35 -> if (InventoryUtil.isTotem(pkt.item) && totemSlot == -1) {
                        totemSlot = pkt.slot; totemNetId = pkt.item.netId
                    }
                }
            }
            is EntityEventPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val t = try { pkt.type?.toString()?.uppercase() ?: "" } catch (_: Exception) { "" }
                if (t.contains("CONSUME") || t.contains("TOTEM")) offhandHasTotem = false
            }
            else -> {}
        }
    }

    private fun watchTick() {
        val shouldEquip = when (triggerMode.value) {
            TriggerMode.HealthBased -> currentHealth <= healthThreshold.value && !offhandHasTotem
            TriggerMode.Always      -> !offhandHasTotem || reEquipAlways.value
            TriggerMode.OnDamage    -> !offhandHasTotem && (System.currentTimeMillis() - lastDamageMs < 2000L)
        }
        if (shouldEquip && totemSlot >= 0) {
            val now = System.currentTimeMillis()
            if (now - lastEquipMs >= delay.value) {
                lastEquipMs = now; equipTotem()
            }
        }
    }

    private fun equipTotem() {
        val slot    = totemSlot; if (slot < 0) return
        val session = PacketEventBus.currentSession ?: return
        Log.d(TAG, "Totem takılıyor slot=$slot netId=$totemNetId")
        InventoryUtil.sendOffhandEquip(session, slot, totemNetId)
        offhandHasTotem = true
    }
}
