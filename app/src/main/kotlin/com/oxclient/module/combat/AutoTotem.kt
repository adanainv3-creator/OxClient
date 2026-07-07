package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction
import org.cloudburstmc.protocol.bedrock.packet.*

class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Totemi sürekli sol ele takar"
) {
    // ✅ YENİ: Modern Bedrock (server-authoritative inventory açık sunucularda,
    // ki bu genelde 1.16.100+ için varsayılan) MobEquipmentPacket'i ARTIK GERÇEKTEN
    // uygulamıyor — paket sadece bilgilendirme amaçlı, sunucu kendi authoritative
    // envanter state'ini değiştirmiyor. Bu yüzden totem ANLIK olarak takılıymış gibi
    // görünüp (client-side echo) hemen ardından sunucunun gerçek (değişmemiş) state'i
    // geri geldiğinde offhand tekrar boşalıyordu (log: isTotem=true → 650ms sonra false).
    //
    // Asıl doğru yöntem ItemStackRequestPacket ile SwapAction göndermek — bu, gerçek
    // bir oyuncunun envanterde sürükle-bırak/sağ-tık swap yaptığında sunucuya giden
    // paketle birebir aynı ve sunucunun authoritative envanterini GERÇEKTEN değiştiriyor.
    enum class EquipMethod { ItemStackRequest, MobEquipment, Both }

    companion object {
        private const val TAG = "AutoTotem"
    }

    private val equipMethod = enum("Equip Method", EquipMethod.ItemStackRequest)

    @Volatile private var totemSlot       = -1
    @Volatile private var offhandHasTotem = false

    // Bedrock istemcisi stackRequestId'leri NEGATİF ve azalan üretir (sunucunun
    // kendi ürettiği pozitif id'lerle çakışmaması için).
    @Volatile private var stackRequestIdCounter = 0
    private fun nextStackRequestId(): Int {
        stackRequestIdCounter -= 1
        return stackRequestIdCounter
    }

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

        when (equipMethod.value) {
            EquipMethod.MobEquipment -> {
                sendViaMobEquipment(session, slot, itemData)
            }
            EquipMethod.ItemStackRequest -> {
                sendViaItemStackRequest(session, slot, itemData)
            }
            EquipMethod.Both -> {
                sendViaItemStackRequest(session, slot, itemData)
                sendViaMobEquipment(session, slot, itemData)
            }
        }

        offhandHasTotem = true
        OverlayLogger.i(TAG, "Totem takıldı: slot=$slot (yöntem=${equipMethod.value})")
    }

    private fun sendViaMobEquipment(session: OxRelaySession, slot: Int, itemData: ItemData) {
        OverlayLogger.i(TAG, "MobEquipmentPacket gönderiliyor: slot=$slot selfRuntimeId=${EntityTracker.selfRuntimeId}")
        InventoryUtil.sendOffhandEquip(session, slot, itemData)
    }

    /**
     * ✅ ASIL FİX: ItemStackRequestPacket ile ana envanterdeki totemi offhand ile
     * SWAP eder — gerçek bir oyuncunun elle yaptığı swap ile birebir aynı paket.
     *
     * ⚠️ DİKKAT: ItemStackRequestSlotData / FullContainerName / SwapAction sınıflarının
     * constructor imzası kullandığın CloudburstMC protocol kütüphanesi versiyonuna göre
     * FARKLILIK gösterebilir (alan sırası, isimlendirme, ekstra parametreler gibi).
     * Derleme hatası alırsan IDE'de ctrl+click ile gerçek sınıf tanımına bakıp parametre
     * sırasını/isimlerini buna göre düzelt — mantık (container tipi + slot + netId ile
     * swap action oluşturmak) aynı kalacak. Özellikle FullContainerName'in ikinci
     * parametresi (dynamicId) bazı versiyonlarda yok/farklı isimde olabilir — o durumda
     * sadece FullContainerName(ContainerSlotType.X) tek parametreli haliyle dene.
     */
    private fun sendViaItemStackRequest(session: OxRelaySession, slot: Int, itemData: ItemData) {
        try {
            val offhandSnapshot = EntityTracker.getInventoryItem(119)
            val offhandNetId = offhandSnapshot?.netId ?: 0

            // ✅ FIX (derleme hatası): ItemStackRequestSlotData artık ContainerSlotType'ı
            // DOĞRUDAN almıyor — bunun yerine "containerName: FullContainerName" bekliyor
            // (nested container'lar için — ör. çift sandık, bundle — dynamicId taşıyabilen
            // bir sarmalayıcı). Basit hotbar/inventory/offhand slotları için dynamicId null.
            val source = ItemStackRequestSlotData(
                FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null),
                slot,
                itemData.netId
            )
            val destination = ItemStackRequestSlotData(
                FullContainerName(ContainerSlotType.OFFHAND, null),
                0,
                offhandNetId
            )

            val request = ItemStackRequest(
                nextStackRequestId(),
                arrayOf(SwapAction(source, destination)),
                arrayOf()
            )

            session.serverBound(ItemStackRequestPacket().apply {
                requests.add(request)
            })

            OverlayLogger.i(TAG, "ItemStackRequestPacket (Swap) gönderildi: slot=$slot netId=${itemData.netId} -> offhand netId=$offhandNetId")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "ItemStackRequestPacket gönderilemedi: ${e.message}", e)
        }
    }
}
