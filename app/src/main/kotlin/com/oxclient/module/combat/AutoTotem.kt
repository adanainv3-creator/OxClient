package com.oxclient.module.combat

import android.util.Log
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Ölümsüzlük totemini otomatik takar"
) {
    private val healthThreshold = float("HealthThreshold", 10f, 1f,  20f)
    private val delay           = int  ("Delay",           50,  0,   500)
    private val shortcut        = bool ("Shortcut",        false)

    @Volatile private var currentHealth   = 20f
    @Volatile private var totemSlot       = -1
    @Volatile private var offhandHasTotem = false
    @Volatile private var lastEquipMs     = 0L

    private var watchJob: Job? = null

    companion object {
        private const val TAG           = "AutoTotem"
        private const val TOTEM_ITEM_ID = 702
        private const val OFFHAND_SLOT  = 119
    }

    override fun onEnable() {
        super.onEnable()
        currentHealth = 20f; totemSlot = -1; offhandHasTotem = false
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
                pkt.attributes.firstOrNull { it.name == "minecraft:health" }
                    ?.let { currentHealth = it.value }
            }
            is InventoryContentPacket -> parseInventoryContent(pkt)
            is InventorySlotPacket    -> parseInventorySlot(pkt)
            is EntityEventPacket      -> {
                if (pkt.runtimeEntityId == EntityTracker.selfRuntimeId && pkt.type == 57)
                    offhandHasTotem = false
            }
            else -> {}
        }
    }

    private fun parseInventoryContent(pkt: InventoryContentPacket) {
        if (pkt.containerId != 0) return
        totemSlot = -1; offhandHasTotem = false
        pkt.contents.forEachIndexed { slot, item ->
            if (item.id == TOTEM_ITEM_ID) {
                when {
                    slot == OFFHAND_SLOT && !offhandHasTotem -> offhandHasTotem = true
                    totemSlot == -1                          -> totemSlot = slot
                }
            }
        }
    }

    private fun parseInventorySlot(pkt: InventorySlotPacket) {
        if (pkt.containerId != 0) return
        when (pkt.slot) {
            OFFHAND_SLOT -> offhandHasTotem = (pkt.item.id == TOTEM_ITEM_ID)
            in 0..35     -> if (pkt.item.id == TOTEM_ITEM_ID && totemSlot == -1) totemSlot = pkt.slot
        }
    }

    private suspend fun watchLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled && currentHealth <= healthThreshold.value && !offhandHasTotem && totemSlot >= 0) {
                val now = System.currentTimeMillis()
                if (now - lastEquipMs >= delay.value) {
                    lastEquipMs = now
                    equipTotem()
                }
            }
            delay(50L)
        }
    }

    private fun equipTotem() {
        val slot = totemSlot; if (slot < 0) return
        val session = PacketEventBus.currentSession ?: return
        Log.d(TAG, "Totem takılıyor (slot=$slot)")
        val pkt = MobEquipmentPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            containerId     = 0
            this.slot       = slot
            hotbarSlot      = OFFHAND_SLOT
            item            = ItemData.builder().id(TOTEM_ITEM_ID).count(1).damage(0).build()
        }
        session.serverBound(pkt)
    }
}
