package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.InventoryUtil
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
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
        private const val TOTEM_IDENTIFIER = "minecraft:totem_of_undying"
    }

    @Volatile private var currentHealth   = 20f
    @Volatile private var totemSlot       = -1
    @Volatile private var totemNetId      = 0
    @Volatile private var totemDefinition : ItemDefinition? = null
    @Volatile private var offhandHasTotem = false
    @Volatile private var lastEquipMs     = 0L
    @Volatile private var lastDamageMs    = 0L
    @Volatile private var lastVerifyMs    = 0L

    private var watchJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        currentHealth = 20f
        totemSlot = -1
        totemNetId = 0
        totemDefinition = null
        offhandHasTotem = false
        
        scanCachedInventory()
        logInventoryState()
        
        OverlayLogger.d(TAG, "Enabled: triggerMode=${triggerMode.value} threshold=${healthThreshold.value} delay=${delay.value}")
        watchJob = launchTickLoop(20L) { watchTick() }
    }

    private fun logInventoryState() {
        val snapshot = EntityTracker.getInventorySnapshot()
        if (snapshot.isEmpty()) {
            OverlayLogger.d(TAG, "Envanter snapshot boş")
            return
        }
        OverlayLogger.d(TAG, "=== ENVANTER DURUMU ===")
        snapshot.forEach { (slot, item) ->
            val id = try { item.definition?.identifier } catch (_: Exception) { "unknown" }
            OverlayLogger.d(TAG, "  Slot $slot: $id (netId=${item.netId})")
        }
        OverlayLogger.d(TAG, "Totem slot: $totemSlot, Offhand: $offhandHasTotem")
    }

    private fun scanCachedInventory() {
        val snapshot = EntityTracker.getInventorySnapshot()
        if (snapshot.isEmpty()) return
        
        snapshot.forEach { (slot, item) ->
            if (InventoryUtil.isTotem(item)) {
                when {
                    slot == InventoryUtil.OFFHAND_SLOT -> {
                        offhandHasTotem = true
                        OverlayLogger.v(TAG, "Offhand'de totem var: netId=${item.netId}")
                    }
                    slot in 0..35 && totemSlot == -1 -> {
                        totemSlot = slot
                        totemNetId = item.netId
                        totemDefinition = item.definition
                        OverlayLogger.v(TAG, "Totem bulundu: slot=$slot, netId=$totemNetId, id=${item.definition?.identifier}")
                    }
                }
            }
        }
    }

    override fun onDisable() {
        watchJob?.cancel()
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        
        when (val pkt = event.packet) {
            is UpdateAttributesPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val prev = currentHealth
                pkt.attributes.firstOrNull { it.name == "minecraft:health" }?.let {
                    if (it.value < prev) lastDamageMs = System.currentTimeMillis()
                    currentHealth = it.value
                    OverlayLogger.v(TAG, "Health güncellendi: $prev -> ${it.value}")
                }
            }
            
            is InventoryContentPacket -> {
                if (pkt.containerId != 0) return
                
                totemSlot = -1
                totemNetId = 0
                totemDefinition = null
                offhandHasTotem = false
                
                pkt.contents.forEachIndexed { slot, item ->
                    if (InventoryUtil.isTotem(item)) {
                        when {
                            slot == InventoryUtil.OFFHAND_SLOT -> {
                                offhandHasTotem = true
                                OverlayLogger.v(TAG, "Offhand'de totem var (InventoryContent): netId=${item.netId}")
                            }
                            totemSlot == -1 -> {
                                totemSlot = slot
                                totemNetId = item.netId
                                totemDefinition = item.definition
                                OverlayLogger.v(TAG, "Totem bulundu (InventoryContent): slot=$slot, netId=$totemNetId, id=${item.definition?.identifier}")
                            }
                        }
                    }
                }
                OverlayLogger.v(TAG, "InventoryContent tarandı: totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem")
            }
            
            is InventorySlotPacket -> {
                if (pkt.containerId != 0) return
                
                if (pkt.slot == InventoryUtil.OFFHAND_SLOT) {
                    offhandHasTotem = InventoryUtil.isTotem(pkt.item)
                    OverlayLogger.v(TAG, "Offhand güncellendi: $offhandHasTotem (netId=${pkt.item?.netId})")
                } else if (pkt.slot in 0..35) {
                    if (InventoryUtil.isTotem(pkt.item)) {
                        totemSlot = pkt.slot
                        totemNetId = pkt.item.netId
                        totemDefinition = pkt.item.definition
                        OverlayLogger.v(TAG, "Yeni totem: slot=$totemSlot, netId=$totemNetId, id=${pkt.item?.definition?.identifier}")
                    } else if (totemSlot == pkt.slot) {
                        totemSlot = -1
                        totemNetId = 0
                        totemDefinition = null
                        OverlayLogger.v(TAG, "Totem slot $pkt.slot boşaldı")
                    }
                }
            }
            
            is EntityEventPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val type = try { pkt.type?.toString()?.uppercase() ?: "" } catch (_: Exception) { "" }
                
                // Totem tüketimi tespiti
                if (type.contains("CONSUME") || type.contains("TOTEM") || type == "CONSUME_TOTEM") {
                    offhandHasTotem = false
                    OverlayLogger.d(TAG, "Totem tüketildi (EntityEvent=$type)")
                }
            }
            
            else -> {}
        }
    }

    private fun watchTick() {
        val now = System.currentTimeMillis()
        
        // Periyodik offhand doğrulama (her 500ms)
        if (now - lastVerifyMs > 500) {
            lastVerifyMs = now
            verifyOffhandState()
        }
        
        val shouldEquip = when (triggerMode.value) {
            TriggerMode.HealthBased -> currentHealth <= healthThreshold.value && !offhandHasTotem
            TriggerMode.Always      -> !offhandHasTotem || reEquipAlways.value
            TriggerMode.OnDamage    -> !offhandHasTotem && (now - lastDamageMs < 2000L)
        }
        
        if (shouldEquip && totemSlot < 0) {
            scanCachedInventory()
            if (totemSlot < 0) {
                OverlayLogger.v(TAG, "Envanterde totem yok")
                return
            }
        }
        
        if (shouldEquip && totemSlot >= 0) {
            if (now - lastEquipMs >= delay.value) {
                lastEquipMs = now
                equipTotem()
            }
        }
    }

    private fun verifyOffhandState() {
        val offhandItem = EntityTracker.getInventoryItem(InventoryUtil.OFFHAND_SLOT)
        val hasTotem = InventoryUtil.isTotem(offhandItem)
        
        if (hasTotem != offhandHasTotem) {
            OverlayLogger.d(TAG, "Offhand state değişti: $offhandHasTotem -> $hasTotem")
            offhandHasTotem = hasTotem
        }
        
        if (!offhandHasTotem && totemSlot >= 0) {
            val item = EntityTracker.getInventoryItem(totemSlot)
            if (!InventoryUtil.isTotem(item)) {
                OverlayLogger.d(TAG, "Totem slot $totemSlot artık geçerli değil, resetleniyor")
                totemSlot = -1
                totemNetId = 0
                totemDefinition = null
                scanCachedInventory()
            }
        }
    }

    private fun equipTotem() {
        val slot = totemSlot
        if (slot < 0) {
            OverlayLogger.w(TAG, "equipTotem: totemSlot < 0")
            return
        }
        
        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "equipTotem: session null")
            return
        }
        
        val snapshot = EntityTracker.getInventorySnapshot()
        val itemData = snapshot[slot]
        
        if (itemData == null || !InventoryUtil.isTotem(itemData)) {
            OverlayLogger.w(TAG, "Totem item bulunamadı veya geçersiz: slot=$slot")
            scanCachedInventory()
            return
        }
        
        OverlayLogger.d(TAG, "Totem takılıyor: slot=$slot, netId=${itemData.netId}, id=${itemData.definition?.identifier}")
        
        // ✅ Tam ItemData ile gönder
        InventoryUtil.sendOffhandEquip(session, slot, itemData)
        
        offhandHasTotem = true
        lastEquipMs = System.currentTimeMillis()
        
        OverlayLogger.d(TAG, "Totem başarıyla takıldı!")
    }
}