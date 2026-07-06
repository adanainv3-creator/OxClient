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
    @Volatile private var offhandHasTotem = false

    override fun onEnable() {
        super.onEnable()
        totemSlot = -1
        offhandHasTotem = false
        OverlayLogger.i(TAG, "=== AutoTotem ENABLE ===")
        OverlayLogger.d(TAG, "selfRuntimeId=${EntityTracker.selfRuntimeId}")
        scanCachedInventory()
        OverlayLogger.i(TAG, "Enable sonrası durum: totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem")
        if (!offhandHasTotem && totemSlot >= 0) {
            OverlayLogger.d(TAG, "Enable anında totem bulundu, direkt takılıyor")
            equipTotem()
        } else if (totemSlot < 0 && !offhandHasTotem) {
            OverlayLogger.w(TAG, "Enable anında envanterde totem bulunamadı")
        } else if (offhandHasTotem) {
            OverlayLogger.d(TAG, "Enable anında offhand zaten dolu, bekleniyor")
        }
    }

    override fun onDisable() {
        super.onDisable()
        OverlayLogger.i(TAG, "=== AutoTotem DISABLE === (son durum: totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem)")
    }

    override fun onTick() {
        if (!isEnabled) return
        if (offhandHasTotem) return
        if (totemSlot < 0) {
            OverlayLogger.v(TAG, "onTick: offhand boş ama totemSlot=-1, envanter taranıyor")
            scanCachedInventory()
            if (totemSlot < 0) {
                OverlayLogger.v(TAG, "onTick: scan sonrası da totem yok, bekleniyor")
                return
            }
        }
        OverlayLogger.d(TAG, "onTick: offhand boş, totemSlot=$totemSlot → equipTotem()")
        equipTotem()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        when (val pkt = event.packet) {
            is InventoryContentPacket -> {
                OverlayLogger.v(TAG, "InventoryContentPacket: containerId=${pkt.containerId} itemCount=${pkt.contents?.size}")
                when (pkt.containerId) {
                    0 -> {
                        val prevSlot = totemSlot
                        totemSlot = -1
                        var totemCount = 0
                        pkt.contents.forEachIndexed { slot, item ->
                            val isT = InventoryUtil.isTotem(item)
                            if (isT) {
                                totemCount++
                                OverlayLogger.v(TAG, "  slot=$slot totem=true netId=${item?.netId} count=${item?.count} defId=${runCatching { item?.definition?.identifier }.getOrElse { "ERR" }}")
                                if (totemSlot == -1) totemSlot = slot
                            }
                        }
                        OverlayLogger.d(TAG, "InventoryContent(0): totemCount=$totemCount totemSlot=$totemSlot (önceki=$prevSlot)")
                    }
                    119 -> {
                        val item = pkt.contents.firstOrNull()
                        val prev = offhandHasTotem
                        offhandHasTotem = InventoryUtil.isTotem(item)
                        OverlayLogger.d(TAG, "InventoryContent(119/offhand): isTotem=$offhandHasTotem (önceki=$prev) netId=${item?.netId} count=${item?.count} defId=${runCatching { item?.definition?.identifier }.getOrElse { "ERR" }}")
                        if (!offhandHasTotem && totemSlot >= 0) {
                            OverlayLogger.d(TAG, "Offhand boşaldı (content), equipTotem() tetikleniyor")
                            equipTotem()
                        }
                    }
                    else -> OverlayLogger.v(TAG, "InventoryContent: bilinmeyen containerId=${pkt.containerId}, atlanıyor")
                }
            }

            is InventorySlotPacket -> {
                OverlayLogger.v(TAG, "InventorySlotPacket: containerId=${pkt.containerId} slot=${pkt.slot} netId=${pkt.item?.netId} count=${pkt.item?.count} defId=${runCatching { pkt.item?.definition?.identifier }.getOrElse { "ERR" }}")
                if (pkt.containerId == 119) {
                    val prev = offhandHasTotem
                    offhandHasTotem = InventoryUtil.isTotem(pkt.item)
                    OverlayLogger.d(TAG, "InventorySlot(119/offhand): isTotem=$offhandHasTotem (önceki=$prev)")
                    if (!offhandHasTotem && totemSlot >= 0) {
                        OverlayLogger.d(TAG, "Offhand boşaldı (slot), equipTotem() tetikleniyor")
                        equipTotem()
                    }
                } else if (pkt.containerId == 0 && pkt.slot in 0..35) {
                    val isT = InventoryUtil.isTotem(pkt.item)
                    if (isT) {
                        if (totemSlot < 0) {
                            totemSlot = pkt.slot
                            OverlayLogger.d(TAG, "Yeni totem bulundu slot=${pkt.slot}")
                        } else {
                            OverlayLogger.v(TAG, "Totem zaten var slot=$totemSlot, yeni slot=${pkt.slot} görmezden gelindi")
                        }
                    } else if (totemSlot == pkt.slot) {
                        OverlayLogger.d(TAG, "Totem slotu (slot=${pkt.slot}) boşaldı, envanter taranıyor")
                        totemSlot = -1
                        scanCachedInventory()
                        OverlayLogger.d(TAG, "Scan sonrası: yeni totemSlot=$totemSlot")
                    }
                }
            }

            is EntityEventPacket -> {
                val isSelf = pkt.runtimeEntityId == EntityTracker.selfRuntimeId
                val type = try { pkt.type?.toString()?.uppercase() ?: "" } catch (_: Exception) { "" }
                OverlayLogger.v(TAG, "EntityEventPacket: runtimeId=${pkt.runtimeEntityId} selfId=${EntityTracker.selfRuntimeId} isSelf=$isSelf type=$type")
                if (isSelf) {
                    if (type.contains("CONSUME") || type.contains("TOTEM")) {
                        OverlayLogger.i(TAG, "TOTEM TÜKETİLDİ (event=$type), yeniden taranıyor")
                        offhandHasTotem = false
                        totemSlot = -1
                        scanCachedInventory()
                        OverlayLogger.d(TAG, "Tüketim sonrası: totemSlot=$totemSlot")
                        if (totemSlot >= 0) {
                            OverlayLogger.d(TAG, "Yeni totem bulundu, direkt takılıyor")
                            equipTotem()
                        } else {
                            OverlayLogger.w(TAG, "Totem tüketildi ama envanterde yedek yok!")
                        }
                    }
                }
            }
        }
    }

    private fun scanCachedInventory() {
        val snapshot = EntityTracker.getInventorySnapshot()
        OverlayLogger.d(TAG, "scanCachedInventory: snapshot boyutu=${snapshot.size}")

        if (snapshot.isEmpty()) {
            OverlayLogger.w(TAG, "Snapshot BOŞ — EntityTracker henüz InventoryContentPacket almamış olabilir")
        }

        totemSlot = -1
        val offhandItem = snapshot[119]
        offhandHasTotem = InventoryUtil.isTotem(offhandItem)
        OverlayLogger.d(TAG, "Offhand (slot 119): item=${offhandItem != null} isTotem=$offhandHasTotem netId=${offhandItem?.netId} count=${offhandItem?.count} defId=${runCatching { offhandItem?.definition?.identifier }.getOrElse { "ERR" }}")

        var scanned = 0
        snapshot.forEach { (slot, item) ->
            if (slot in 0..35) {
                scanned++
                val isT = InventoryUtil.isTotem(item)
                if (isT) {
                    OverlayLogger.d(TAG, "  [scan] slot=$slot TOTEM netId=${item.netId} count=${item.count} defId=${runCatching { item.definition?.identifier }.getOrElse { "ERR" }}")
                    if (totemSlot == -1) totemSlot = slot
                }
            }
        }
        OverlayLogger.d(TAG, "scanCachedInventory tamamlandı: ${scanned} slot tarandı, totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem")
    }

    private fun equipTotem() {
        val slot = totemSlot
        OverlayLogger.d(TAG, "equipTotem() çağrıldı: slot=$slot")

        if (slot < 0) {
            OverlayLogger.w(TAG, "equipTotem: totemSlot<0, iptal")
            return
        }

        val snapshot = EntityTracker.getInventorySnapshot()
        val itemData = snapshot[slot]
        OverlayLogger.d(TAG, "equipTotem: slot=$slot item=${itemData != null} netId=${itemData?.netId} count=${itemData?.count} defId=${runCatching { itemData?.definition?.identifier }.getOrElse { "ERR" }}")

        if (itemData == null) {
            OverlayLogger.w(TAG, "equipTotem: slot=$slot snapshot'ta null, totemSlot sıfırlanıyor")
            totemSlot = -1
            return
        }

        if (!InventoryUtil.isTotem(itemData)) {
            OverlayLogger.w(TAG, "equipTotem: slot=$slot'daki item artık totem değil (tanınmadı), totemSlot sıfırlanıyor")
            totemSlot = -1
            return
        }

        val session = PacketEventBus.currentSession
        if (session == null) {
            OverlayLogger.e(TAG, "equipTotem: currentSession NULL — relay bağlı değil!")
            return
        }

        OverlayLogger.i(TAG, "MobEquipmentPacket gönderiliyor: slot=$slot selfRuntimeId=${EntityTracker.selfRuntimeId}")
        InventoryUtil.sendOffhandEquip(session, slot, itemData)
        offhandHasTotem = true
        OverlayLogger.i(TAG, "Totem takıldı: slot=$slot")
    }
}
