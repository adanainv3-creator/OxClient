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
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction
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

    // ✅ FIX: offhand'ın GERÇEK anlık stackNetworkId'sini takip ediyoruz. Eskiden
    // ItemStackRequest'in "destination" netId'i için EntityTracker.getInventoryItem(119)
    // taze okunuyordu — ama TOTEM TÜKETİLDİĞİ ANDA (CONSUME_TOTEM event) EntityTracker'ın
    // cache'i henüz güncellenmemiş oluyordu (server'ın "offhand artık boş" paketi birkaç
    // ms sonra geliyor). Bu yüzden swap isteğinde stale (eski totem'in) netId'i
    // "destination" olarak gönderiliyordu — sunucu "bu ID orada değil" deyip isteği
    // sessizce reddediyordu (bkz. ItemStackRequestSlotData dokümantasyonu). Artık
    // consumption anında bunu elle 0'a (boş) çekiyoruz, çünkü tüketim = kesin boşalma.
    @Volatile private var offhandNetId = 0

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
                        offhandNetId = item?.netId ?: 0
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
                    offhandNetId = pkt.item?.netId ?: 0
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
                        // ✅ ASIL FİX: EntityTracker'ın cache'i henüz güncellenmemiş olsa bile
                        // tüketim kesin olarak offhand'ı boşaltır — bunu ELLE 0'a çekiyoruz.
                        // Bu satır olmadan equipTotem() bir sonraki swap isteğinde stale
                        // (tüketilen totem'in) netId'ini "destination" olarak gönderiyordu,
                        // sunucu ID uyuşmazlığı yüzünden swap'ı sessizce reddediyordu.
                        offhandNetId = 0
                        totemSlot = -1
                        scanMainInventoryForTotemSlot()
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
        offhandNetId = offhandItem?.netId ?: 0
        OverlayLogger.d(TAG, "Offhand (slot 119): item=${offhandItem != null} isTotem=$offhandHasTotem netId=${offhandItem?.netId} count=${offhandItem?.count} defId=${runCatching { offhandItem?.definition?.identifier }.getOrElse { "ERR" }}")

        var scanned = 0
        snapshot.forEach { (slot, item) ->
            if (slot in 0..35) {
                scanned++
                val isT = InventoryUtil.isTotem(item)
                val identifier = runCatching { item.definition?.identifier }.getOrElse { "ERR" }
                val runtimeId = runCatching { item.definition?.runtimeId }.getOrElse { -1 }
                if (isT) {
                    OverlayLogger.d(TAG, "  [scan] slot=$slot TOTEM netId=${item.netId} count=${item.count} rid=$runtimeId defId=$identifier")
                    if (totemSlot == -1) totemSlot = slot
                } else {
                    OverlayLogger.v(TAG, "  [scan] slot=$slot netId=${item.netId} count=${item.count} rid=$runtimeId defId=$identifier")
                }
            }
        }
        OverlayLogger.d(TAG, "scanCachedInventory tamamlandı: ${scanned} slot tarandı, totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem")
    }

    // ✅ FIX: consumption anında SADECE ana envanterden yeni totem slotu arar,
    // offhand'a dokunmaz. offhandHasTotem/offhandNetId zaten consumption event'inde
    // elle (kesin) sıfırlanmış oluyor — burada snapshot[119]'u tekrar okumak, henüz
    // sunucudan "offhand boşaldı" paketi gelmemişse ESKİ (stale) totem verisini geri
    // yazıp offhandHasTotem'i yanlışlıkla true yapıyordu, bu da equipTotem() hiç
    // tetiklenmemesine ya da yanlış destNetId ile swap'ın reddedilmesine yol açıyordu.
    private fun scanMainInventoryForTotemSlot() {
        val snapshot = EntityTracker.getInventorySnapshot()
        totemSlot = -1
        var scanned = 0
        snapshot.forEach { (slot, item) ->
            if (slot in 0..35) {
                scanned++
                if (InventoryUtil.isTotem(item)) {
                    if (totemSlot == -1) totemSlot = slot
                }
            }
        }
        OverlayLogger.d(TAG, "scanMainInventoryForTotemSlot: ${scanned} slot tarandı, totemSlot=$totemSlot (offhand dokunulmadı)")
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

        // ✅ FIX: forceClientEcho() KALDIRILDI. Gerçek cihaza ham InventorySlotPacket
        // enjekte ediyordu (özellikle offhand/119 için "slot bazlı" paket — halbuki
        // offhand normalde SADECE tam-içerik InventoryContentPacket ile güncelleniyor,
        // logların hepsinde bunu doğruladık). Bu paket gönderildikten ~26ms sonra
        // session kapanıyordu (log kanıtı: "forceClientEcho: ... yansıtıldı" hemen
        // ardından "Session kapandı ... channel closed") — cihaz beklenmedik/geçersiz
        // paketi görüp bağlantıyı kesiyordu. Ayrıca bu fonksiyon zaten GEREKSİZDİ:
        // sunucu swap'ı kabul ettiğinde kendi InventoryContent/InventorySlot paketlerini
        // normal akışta gönderiyor, relay bunları zaten gerçek cihaza forward ediyor —
        // önceki (forceClientEcho'suz) oturumda totem 9 dakika boyunca sorunsuz
        // görünüyordu. Elle ekstra paket enjekte etmeye hiç gerek yoktu.
    }

    private fun sendViaMobEquipment(session: OxRelaySession, slot: Int, itemData: ItemData) {
        OverlayLogger.i(TAG, "MobEquipmentPacket gönderiliyor: slot=$slot selfRuntimeId=${EntityTracker.selfRuntimeId}")
        InventoryUtil.sendOffhandEquip(session, slot, itemData)
    }

    /**
     * ✅ ASIL FİX: ItemStackRequestPacket ile ana envanterdeki totemi offhand ile
     * SWAP eder — gerçek bir oyuncunun elle yaptığı swap ile birebir aynı paket.
     *
     * Sınıf imzaları (ItemStackRequestSlotData, FullContainerName, SwapAction,
     * ItemStackRequest) CloudburstMC protocol kaynak dosyalarından doğrulandı,
     * tahmin değil.
     */
    private fun sendViaItemStackRequest(session: OxRelaySession, slot: Int, itemData: ItemData) {
        try {
            // ✅ ASIL FİX: Artık EntityTracker'dan TAZE (ve tüketim anında stale olabilen)
            // bir okuma yapmıyoruz — bunun yerine sınıfın kendi takip ettiği offhandNetId
            // alanını kullanıyoruz. Bu alan sadece gerçek InventoryContent/InventorySlot
            // paketleriyle VE tüketim event'inde (kesin boşalma anında) elle güncelleniyor
            // — böylece "destination" ID'si sunucunun gerçek anlık state'iyle eşleşiyor.
            val destNetId = offhandNetId

            // ✅ GERÇEK İMZA DOĞRULANDI (kaynak dosyalardan): ItemStackRequestSlotData
            // Lombok @Value ile 4 alanlı: (container: ContainerSlotType [deprecated ama
            // hâlâ zorunlu], slot: Int, stackNetworkId: Int, containerName: FullContainerName).
            // FullContainerName de @Value: (container: ContainerSlotType, dynamicId: Integer?).
            val source = ItemStackRequestSlotData(
                ContainerSlotType.HOTBAR_AND_INVENTORY,
                slot,
                itemData.netId,
                FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null)
            )
            val destination = ItemStackRequestSlotData(
                ContainerSlotType.OFFHAND,
                0,
                destNetId,
                FullContainerName(ContainerSlotType.OFFHAND, null)
            )

            val request = ItemStackRequest(
                nextStackRequestId(),
                arrayOf<ItemStackRequestAction>(SwapAction(source, destination)),
                arrayOf<String>()
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