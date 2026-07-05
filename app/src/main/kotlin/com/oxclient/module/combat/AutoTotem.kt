
package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Totemi sürekli sol ele takar"
) {
    companion object {
        private const val TAG = "AutoTotem"
    }

    @Volatile private var totemSlot       = -1
    @Volatile private var totemNetId      = 0
    @Volatile private var totemDefinition : ItemDefinition? = null
    @Volatile private var offhandHasTotem = false

    private var watchJob: kotlinx.coroutines.Job? = null

    // 🔍 DEBUG: tam envanter dump'ı spam yapmasın diye en fazla 2 saniyede bir yazılır
    @Volatile private var lastDumpMs = 0L

    override fun onEnable() {
        super.onEnable()
        totemSlot = -1
        totemNetId = 0
        totemDefinition = null
        offhandHasTotem = false

        scanCachedInventory()
        OverlayLogger.d(TAG, "Enabled: totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem")
        watchJob = launchTickLoop(20L) { watchTick() }
    }

    override fun onDisable() {
        watchJob?.cancel()
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled")
    }

    /**
     * 🔍 DEBUG (GEÇİCİ): Totem "görünürde envanterde var ama bulunamıyor" sorununu
     * teşhis etmek için, tarama sırasında envanterdeki HER item'ın gerçek
     * identifier/netId değerini bir kere dump ediyoruz. Bir sonraki enable
     * denemesinde logda "── Envanter taraması ──" bloğunu ara; totemin
     * gerçekte hangi identifier ile geldiğini orada göreceğiz.
     */
    private fun scanCachedInventory() {
        val snapshot = EntityTracker.getInventorySnapshot()
        totemSlot = -1; totemNetId = 0; totemDefinition = null
        offhandHasTotem = InventoryUtil.isTotem(snapshot[119])

        val now = System.currentTimeMillis()
        if (now - lastDumpMs > 2000L) {
            lastDumpMs = now
            OverlayLogger.d(TAG, "── Envanter taraması (${snapshot.size} item, offhand dahil) ──")
            if (snapshot.isEmpty()) {
                OverlayLogger.d(TAG, "  (snapshot BOŞ — EntityTracker henüz hiç InventoryContentPacket görmemiş olabilir)")
            } else {
                snapshot.toSortedMap().forEach { (slot, item) ->
                    val id = try { item.definition?.identifier ?: "null" } catch (e: Exception) { "ERR:${e.message}" }
                    val rid = try { item.definition?.runtimeId } catch (_: Exception) { null }
                    OverlayLogger.d(TAG, "  slot=$slot identifier=$id runtimeId=$rid netId=${item.netId} count=${item.count}")
                }
            }
        }

        snapshot.forEach { (slot, item) ->
            if (slot in 0..35 && totemSlot == -1 && InventoryUtil.isTotem(item)) {
                totemSlot = slot
                totemNetId = item.netId
                totemDefinition = item.definition
            }
        }
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (val pkt = event.packet) {
            is InventoryContentPacket -> {
                when (pkt.containerId) {
                    0 -> {
                        totemSlot = -1; totemNetId = 0; totemDefinition = null
                        pkt.contents.forEachIndexed { slot, item ->
                            if (InventoryUtil.isTotem(item) && totemSlot == -1) {
                                totemSlot = slot
                                totemNetId = item.netId
                                totemDefinition = item.definition
                            }
                        }
                    }
                    119 -> {
                        offhandHasTotem = InventoryUtil.isTotem(pkt.contents.firstOrNull())
                    }
                    else -> return
                }
            }

            is InventorySlotPacket -> {
                if (pkt.containerId == 119) {
                    offhandHasTotem = InventoryUtil.isTotem(pkt.item)
                } else if (pkt.containerId == 0 && pkt.slot in 0..35) {
                    if (InventoryUtil.isTotem(pkt.item)) {
                        totemSlot = pkt.slot
                        totemNetId = pkt.item.netId
                        totemDefinition = pkt.item.definition
                    } else if (totemSlot == pkt.slot) {
                        totemSlot = -1; totemNetId = 0; totemDefinition = null
                    }
                }
            }

            is EntityEventPacket -> {
                if (pkt.runtimeEntityId != EntityTracker.selfRuntimeId) return
                val type = try { pkt.type?.toString()?.uppercase() ?: "" } catch (_: Exception) { "" }
                if (type.contains("CONSUME") || type.contains("TOTEM")) {
                    offhandHasTotem = false
                    // Totem kullanıldı, hemen yeniden takmayı dene
                    scanCachedInventory()
                    if (totemSlot >= 0) {
                        equipTotem()
                    }
                }
            }

            else -> {}
        }
    }

    private fun watchTick() {
        if (offhandHasTotem) return
        if (totemSlot < 0) {
            scanCachedInventory()
            if (totemSlot < 0) return
        }
        equipTotem()
    }

    private fun equipTotem() {
        val slot = totemSlot
        if (slot < 0) return

        val session = PacketEventBus.currentSession ?: return
        val itemData = EntityTracker.getInventorySnapshot()[slot] ?: return

        if (!InventoryUtil.isTotem(itemData)) {
            scanCachedInventory()
            return
        }

        InventoryUtil.sendOffhandEquip(session, slot, itemData)
        offhandHasTotem = true
        OverlayLogger.v(TAG, "Totem takıldı: slot=$slot netId=${itemData.netId}")
    }
}