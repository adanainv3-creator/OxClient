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

    @Volatile private var currentHealth   = 20f
    @Volatile private var totemSlot       = -1
    @Volatile private var offhandHasTotem = false
    @Volatile private var lastEquipMs     = 0L
    @Volatile private var lastDamageMs    = 0L

    private var watchJob: Job? = null

    companion object {
        private const val TAG           = "AutoTotem"
        private const val TOTEM_ITEM_ID = 702
    }

    override fun onEnable() {
        super.onEnable()
        currentHealth   = 20f
        totemSlot       = -1
        offhandHasTotem = false
        watchJob = scope.launch { watchLoop() }
    }

    override fun onDisable() {
        watchJob?.cancel()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is UpdateAttributesPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val prev = currentHealth
                pkt.attributes.firstOrNull { it.name == "minecraft:health" }
                    ?.let {
                        if (it.value < prev) lastDamageMs = System.currentTimeMillis()
                        currentHealth = it.value
                    }
            }
            is InventoryContentPacket -> parseInventoryContent(pkt)
            is InventorySlotPacket    -> parseInventorySlot(pkt)
            is EntityEventPacket      -> {
                if (pkt.runtimeEntityId == EntityTracker.selfRuntimeId && pkt.type == 57) {
                    offhandHasTotem = false
                }
            }
            else -> {}
        }
    }

    private fun parseInventoryContent(pkt: InventoryContentPacket) {
        if (pkt.containerId != 0) return
        totemSlot = -1
        offhandHasTotem = false
        pkt.contents.forEachIndexed { slot, item ->
            if (item.id == TOTEM_ITEM_ID) {
                when {
                    slot == InventoryUtil.OFFHAND_SLOT && !offhandHasTotem -> offhandHasTotem = true
                    totemSlot == -1 -> totemSlot = slot
                }
            }
        }
    }

    private fun parseInventorySlot(pkt: InventorySlotPacket) {
        if (pkt.containerId != 0) return
        when (pkt.slot) {
            InventoryUtil.OFFHAND_SLOT -> {
                offhandHasTotem = (pkt.item.id == TOTEM_ITEM_ID)
            }
            in 0..35 -> {
                if (pkt.item.id == TOTEM_ITEM_ID && totemSlot == -1) totemSlot = pkt.slot
            }
        }
    }

    private suspend fun watchLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                val shouldEquip = when (triggerMode.value) {
                    TriggerMode.HealthBased -> currentHealth <= healthThreshold.value && !offhandHasTotem
                    TriggerMode.Always      -> !offhandHasTotem || reEquipAlways.value
                    TriggerMode.OnDamage    -> {
                        val now = System.currentTimeMillis()
                        !offhandHasTotem && (now - lastDamageMs < 2000L)
                    }
                }
                if (shouldEquip && totemSlot >= 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastEquipMs >= delay.value) {
                        lastEquipMs = now
                        equipTotem()
                    }
                }
            }
            delay(20L)
        }
    }

    private fun equipTotem() {
        val slot = totemSlot
        if (slot < 0) return
        val session = PacketEventBus.currentSession ?: return
        Log.d(TAG, "Totem takılıyor (slot=$slot)")
        InventoryUtil.sendOffhandEquip(session, slot, TOTEM_ITEM_ID)
        offhandHasTotem = true
    }
}
