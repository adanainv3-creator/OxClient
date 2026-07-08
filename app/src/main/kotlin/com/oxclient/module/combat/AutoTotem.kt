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
    // ✅ ASIL FİX: EquipMethod seçeneği (ItemStackRequest/MobEquipment/Both) tamamen
    // kaldırıldı. Log kanıtı: "Both" modunda totem sunucu tarafından onaylanıyor
    // (offhand isTotem=true) ama 4 SANİYE SONRA, hiçbir tüketim/hasar event'i olmadan
    // kendiliğinden boşalıyordu. MobEquipmentPacket'in bu ek gönderimi sunucunun
    // birkaç saniye sonraki rutin senkronizasyonunu bozup state'i geri alıyordu.
    // Sadece ItemStackRequest kullanıldığında (MobEquipment hiç karışmadan) totem
    // önceki testte 9 dakika boyunca hiç düşmeden durmuştu — kanıtlanmış tek güvenilir
    // yol bu olduğu için artık TEK yöntem bu, ayar/karışıklık ihtimali de ortadan kalktı.

    // ✅ ASIL FİX (referans koddan): Bizim eski tasarım SADECE belirli paket
    // olaylarına (InventoryContent/Slot/EntityEvent) tepki veriyordu. Bu olaylardan
    // biri kaçarsa veya sunucu birkaç saniye sonra state'i geri alırsa (kanıtlandı:
    // "Both" modunda totem 4 saniye sonra sessizce düşüyordu), ASLA tekrar
    // denemiyorduk — offhand kalıcı boş kalıyordu. Referans implementasyon
    // (AutoTotemElement) her tick'te (event beklemeden) "offhand'da totem var mı?
    // Yoksa hemen düzelt" diye SÜREKLİ kontrol ediyor — bu, tek bir event'i doğru
    // yakalamaya bel bağlamak yerine kendi kendini onaran (self-healing) bir yapı.
    // Aynı deseni burada da kuruyoruz: event tabanlı anlık tepki KALIYOR (hızlı
    // tepki için) ama artık üstüne 200ms'lik bir tick loop da ekleniyor — sunucu
    // state'i her ne sebeple geri alırsa alsın, en geç 200ms içinde tekrar düzeltiliyor.
    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    private var lastEquipAttemptMs = 0L

    companion object {
        private const val TAG = "AutoTotem"
    }

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
        tickJob = launchTickLoop(200L) { tickCheck() }
    }

    override fun onDisable() {
        super.onDisable()
        tickJob?.cancel()
        tickJob = null
        OverlayLogger.i(TAG, "=== AutoTotem DISABLE === (son durum: totemSlot=$totemSlot offhandHasTotem=$offhandHasTotem)")
    }

    /**
     * ✅ ASIL FİX: Her 200ms'te bir, event beklemeden, offhand'ın GERÇEK anlık
     * durumunu (cache'ten değil EntityTracker'ın en taze snapshot'ından) okuyup
     * totem değilse hemen düzeltiyor. Sunucu state'i ne zaman/ne sebeple geri
     * alırsa alsın en geç 200ms içinde tekrar denenmiş oluyor — tek bir event'i
     * doğru yakalamaya bağımlı olmuyoruz.
     */
    private fun tickCheck() {
        val snapshot = EntityTracker.getInventorySnapshot()
        val offhandItem = snapshot[119]
        val hasTotemNow = InventoryUtil.isTotem(offhandItem)

        // cache'i taze veriyle senkronize tut
        offhandHasTotem = hasTotemNow
        offhandNetId = offhandItem?.netId ?: 0

        if (hasTotemNow) return

        val now = System.currentTimeMillis()
        if (now - lastEquipAttemptMs < 200L) return
        lastEquipAttemptMs = now

        if (totemSlot < 0 || !InventoryUtil.isTotem(snapshot[totemSlot])) {
            scanCachedInventory()
        }
        if (totemSlot >= 0) {
            OverlayLogger.d(TAG, "tickCheck: offhand totem değil, yeniden takılıyor (slot=$totemSlot)")
            equipTotem()
        }
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

        // ✅ FIX: Artık tek yöntem var — kanıtlanmış çalışan ItemStackRequest.
        sendViaItemStackRequest(session, slot, itemData)

        offhandHasTotem = true
        OverlayLogger.i(TAG, "Totem takıldı: slot=$slot")

        // ✅ FIX: forceClientEcho() KALDIRILDI (kalıcı olarak). Gerçek cihaza ham
        // InventorySlotPacket enjekte ediyordu — offhand (containerId=119) için
        // "slot bazlı" bir paket, halbuki offhand normalde SADECE tam-içerik
        // InventoryContentPacket ile güncelleniyor. Bu paket gönderildikten ~26ms
        // sonra session kapanıyordu (log kanıtı: "forceClientEcho: ... yansıtıldı"
        // hemen ardından "Session kapandı ... channel closed"). Ayrıca gereksizdi:
        // sunucu swap'ı kabul ettiğinde kendi InventoryContent/InventorySlot
        // paketlerini normal akışta gönderiyor, relay bunları zaten gerçek cihaza
        // forward ediyor.
    }

    private fun sendViaItemStackRequest(session: OxRelaySession, slot: Int, itemData: ItemData) {
        try {
            val destNetId = offhandNetId

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