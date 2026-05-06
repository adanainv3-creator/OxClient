package com.oxclient.modules.combat

import com.oxclient.events.PacketEvent
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHurtPacket
import timber.log.Timber

/**
 * AutoTotem — Otomatik Totem of Undying yöneticisi.
 *
 * ● Sunucudan gelen HasHealth / EntityEventPacket üzerinden ölüm tespiti.
 * ● Envanterdeki totemi offhand slotuna anında taşır (InventoryTransaction).
 * ● Re-equip delay ayarı → sunucu anti-cheat için gerçekçi gecikme.
 * ● Her tick'te offhand boşsa ve envanterde totem varsa otomatik ekler.
 * ● Düşük HP uyarı eşiği → ayarlanabilir.
 *
 * Paket ID'leri (Bedrock 1.21.x):
 *   0x27 = InventoryTransaction
 *   0x13 = PlayerHurt (sunucudan)
 *   0x28 = PlayerAuthInput (istemciden, her frame)
 */
class AutoTotem : BaseModule(
    name        = "AutoTotem",
    description = "Totem bitince otomatik yeniden takı",
    category    = ModuleCategory.COMBAT
) {
    // ── Ayarlar ───────────────────────────────────────────────────────────────

    /** Re-equip gecikmesi ms — 0 = anlık, 50–150 = gerçekçi */
    val delay     = intSetting("Delay (ms)", min = 0, max = 300, default = 60)

    /** Açık slot'a bakarken hangi container'ı tara */
    val deepScan  = boolSetting("Deep Scan", default = true)

    /** Konsol log */
    val logging   = boolSetting("Logging", default = false)

    // ── Durum ─────────────────────────────────────────────────────────────────

    @Volatile private var lastEquipMs       = 0L
    @Volatile private var pendingEquip      = false
    @Volatile private var totemSlot         = -1   // envanterdeki slot indeksi
    @Volatile private var offhandHasTotem   = false

    // Totem item ID — Bedrock 1.21
    private val TOTEM_ITEM_ID = 732  // minecraft:totem_of_undying

    // Offhand slot numarası (Bedrock inventory layout)
    private val OFFHAND_SLOT  = 54
    private val HOTBAR_START  = 0
    private val HOTBAR_END    = 8
    private val INV_START     = 9
    private val INV_END       = 35

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        log("AutoTotem aktif")
        pendingEquip    = false
        offhandHasTotem = false
        totemSlot       = -1
        lastEquipMs     = 0L
    }

    override fun onDisable() {
        log("AutoTotem pasif")
        pendingEquip = false
    }

    // ── Her 50ms tick ─────────────────────────────────────────────────────────

    override suspend fun onTick() {
        if (!pendingEquip) return
        val now = System.currentTimeMillis()
        if (now - lastEquipMs < delay.value) return
        if (totemSlot < 0) return

        pendingEquip = false
        lastEquipMs  = now
        sendEquipTransaction(totemSlot)
    }

    // ── Paket dinleyici ───────────────────────────────────────────────────────

    /**
     * Sunucudan gelen paketleri dinle.
     * PlayerHurtPacket → hasar aldık, totem gerekebilir.
     * InventoryTransactionPacket → offhand güncellendi mi kontrol et.
     */
    override fun onPacketReceive(event: PacketEvent) {
        val pkt = event.packet ?: return
        when (pkt) {
            is PlayerHurtPacket -> {
                // Hasar aldık → offhand'i kontrol et
                scheduleEquipIfNeeded()
            }
            is InventoryTransactionPacket -> {
                // Offhand slotu güncellendi mi?
                checkOffhandUpdate(pkt)
            }
            else -> {}
        }
    }

    /**
     * İstemciden gelen paketleri dinle.
     * PlayerAuthInputPacket her frame gelir — offhand boş ise anında equip iste.
     */
    override fun onPacketSend(event: PacketEvent) {
        val pkt = event.packet ?: return
        if (pkt is PlayerAuthInputPacket) {
            if (!offhandHasTotem) scheduleEquipIfNeeded()
        }
    }

    // ── Yardımcı fonksiyonlar ─────────────────────────────────────────────────

    private fun scheduleEquipIfNeeded() {
        if (offhandHasTotem) return        // zaten var
        if (pendingEquip)    return        // zaten bekliyor

        val slot = findTotemSlot()
        if (slot < 0) return              // envanterde totem yok

        totemSlot    = slot
        pendingEquip = true
        log("Totem slot $slot bulundu, equip planlandı (delay=${delay.value}ms)")
    }

    /**
     * Envanterde totem arar.
     * deepScan=true → tüm envanter + hotbar
     * deepScan=false → sadece hotbar
     */
    private fun findTotemSlot(): Int {
        // Bu metod gerçek envanter verisi olmadan çalışamaz —
        // OxRelaySession üzerinden gelen SetPlayerInventoryPacket /
        // InventoryContentPacket ile envanter takip edilmeli.
        // Şimdilik placeholder implementasyon döner:
        return OxInventoryTracker.findItem(TOTEM_ITEM_ID,
            deepScan = deepScan.value,
            hotbarOnly = !deepScan.value)
    }

    private fun checkOffhandUpdate(pkt: InventoryTransactionPacket) {
        // Offhand slot hareketi kontrol
        val hasTotemInOffhand = pkt.actions.any { action ->
            action.toSlot == OFFHAND_SLOT &&
            action.toItem.definition?.runtimeId == TOTEM_ITEM_ID
        }
        if (hasTotemInOffhand) {
            offhandHasTotem = true
            pendingEquip    = false
            log("Offhand totemi takıldı ✓")
        }

        val removedFromOffhand = pkt.actions.any { action ->
            action.fromSlot == OFFHAND_SLOT &&
            action.fromItem.definition?.runtimeId == TOTEM_ITEM_ID
        }
        if (removedFromOffhand) {
            offhandHasTotem = false
            log("Offhand totemi çıkarıldı — yeniden equip gerekebilir")
            scheduleEquipIfNeeded()
        }
    }

    /**
     * Gerçek InventoryTransaction paketi üretir ve istemciye gönderir.
     * Relay katmanı bu paketi sunucuya iletecek.
     */
    private fun sendEquipTransaction(fromSlot: Int) {
        try {
            val txPacket = InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.NORMAL
                actions.add(
                    InventoryActionData(
                        InventorySource.fromContainerWindowId(0),  // inventory
                        fromSlot,
                        OxInventoryTracker.getItem(fromSlot),     // totemItem
                        OxInventoryTracker.emptyItem()            // empty
                    )
                )
                actions.add(
                    InventoryActionData(
                        InventorySource.fromContainerWindowId(119), // offhand window
                        0,                                          // offhand slot 0
                        OxInventoryTracker.emptyItem(),
                        OxInventoryTracker.getItem(fromSlot)
                    )
                )
            }
            OxRelayBridge.sendToServer(txPacket)
            offhandHasTotem = true
            log("InventoryTransaction gönderildi: slot $fromSlot → offhand")
        } catch (e: Exception) {
            Timber.e(e, "AutoTotem sendEquipTransaction hata")
        }
    }

    private fun log(msg: String) {
        if (logging.value) Timber.d("[AutoTotem] $msg")
    }
}
