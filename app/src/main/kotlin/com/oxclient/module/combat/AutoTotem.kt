package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
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
    // ✅ FIX: netId yerine slottaki GERÇEK ItemData saklanıyor. MobEquipmentSerializer
    // helper.writeItem() kullanıyor — bu legacy yazıcı netId kısayolu tanımıyor,
    // item'ın gerçek definition/damage/tag alanlarını okuyor. Sadece netId göndermek
    // definition=null olan sahte bir item'a yol açıp encode'da çöküyordu.
    @Volatile private var totemItem: org.cloudburstmc.protocol.bedrock.data.inventory.ItemData? = null
    @Volatile private var offhandHasTotem = false
    @Volatile private var lastEquipMs     = 0L
    @Volatile private var lastDamageMs    = 0L

    private var watchJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        currentHealth = 20f; totemSlot = -1; offhandHasTotem = false
        // ✅ FIX: InventoryContentPacket sadece bağlantı başında/envanter açıldığında
        // gelir. Oyuna girdikten SONRA AutoTotem enable edilirse bu paket bir daha hiç
        // gelmeyebilir ve totemSlot hep -1 kalırdı. EntityTracker'ın her zaman güncel
        // tuttuğu envanter önbelleğinden burada anında tarama yapıyoruz.
        scanCachedInventory()
        OverlayLogger.d(TAG, "Enabled: triggerMode=${triggerMode.value} threshold=${healthThreshold.value} delay=${delay.value} (cache'ten totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem)")
        watchJob = launchTickLoop(20L) { watchTick() }
    }

    private fun scanCachedInventory() {
        val snapshot = EntityTracker.getInventorySnapshot()
        if (snapshot.isEmpty()) return
        snapshot.forEach { (slot, item) ->
            if (InventoryUtil.isTotem(item)) when {
                slot == InventoryUtil.OFFHAND_SLOT -> offhandHasTotem = true
                slot in 0..35 && totemSlot == -1 -> { totemSlot = slot; totemItem = item }
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
                totemSlot = -1; offhandHasTotem = false
                pkt.contents.forEachIndexed { slot, item ->
                    if (InventoryUtil.isTotem(item)) when {
                        slot == InventoryUtil.OFFHAND_SLOT && !offhandHasTotem -> offhandHasTotem = true
                        totemSlot == -1 -> { totemSlot = slot; totemItem = item }
                    }
                }
                OverlayLogger.v(TAG, "InventoryContent tarandı: totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem")
            }
            is InventorySlotPacket -> {
                if (pkt.containerId != 0) return
                when (pkt.slot) {
                    InventoryUtil.OFFHAND_SLOT -> offhandHasTotem = InventoryUtil.isTotem(pkt.item)
                    in 0..35 -> if (InventoryUtil.isTotem(pkt.item) && totemSlot == -1) {
                        totemSlot = pkt.slot; totemItem = pkt.item
                        OverlayLogger.v(TAG, "Yeni totem slotu bulundu: slot=$totemSlot")
                    }
                }
            }
            is EntityEventPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val t = try { pkt.type?.toString()?.uppercase() ?: "" } catch (_: Exception) { "" }
                if (t.contains("CONSUME") || t.contains("TOTEM")) {
                    offhandHasTotem = false
                    OverlayLogger.v(TAG, "Totem tüketildi (EntityEvent=$t), offhandHasTotem=false")
                }
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
        if (shouldEquip && totemSlot < 0) {
            OverlayLogger.v(TAG, "shouldEquip=true ama totemSlot=-1 (envanterde totem yok / InventoryContentPacket henüz gelmedi)")
        }
        if (shouldEquip && totemSlot >= 0) {
            val now = System.currentTimeMillis()
            if (now - lastEquipMs >= delay.value) {
                lastEquipMs = now; equipTotem()
            }
        }
    }

    private fun equipTotem() {
        val slot = totemSlot; if (slot < 0) return
        val item = totemItem ?: EntityTracker.getInventoryItem(slot) ?: run {
            OverlayLogger.w(TAG, "equipTotem: slot=$slot için gerçek ItemData bulunamadı — vazgeçildi")
            return
        }
        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "equipTotem: session null — relay bağlı değil")
            return
        }
        OverlayLogger.d(TAG, "Totem takılıyor slot=$slot")
        InventoryUtil.sendOffhandEquip(session, slot, item)
        offhandHasTotem = true
    }
}
