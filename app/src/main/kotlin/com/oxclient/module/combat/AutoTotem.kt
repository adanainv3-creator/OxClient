package com.oxclient.module.combat

import android.util.Log
import com.oxclient.core.proxy.BedrockPacketIds
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.module.*
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

/**
 * AutoTotem
 *
 * Nasıl çalışır:
 *  1. UpdateAttributes paketini dinler → oyuncunun can değerini takip eder.
 *  2. InventoryContent paketini dinler → totem slotunu takip eder.
 *  3. Can healthThreshold'un altına düşünce:
 *       - MobEquipment paketi: offhand slota totem koy
 *       - İsteğe bağlı: InventoryTransaction ile slot değiştirme
 *
 * Bedrock offhand slot ID: 119 (CONTAINER_ID=0, slot=119)
 */
class AutoTotem : BaseModule(
    name        = "AutoTotem",
    category    = ModuleCategory.COMBAT,
    description = "Ölümsüzlük totemini otomatik takkar"
), PacketListener {

    override val priority = 20  // Erken çalışsın

    // ── Ayarlar ───────────────────────────────────────────────────────────
    private val healthThreshold = FloatSetting("HealthThreshold", 10f, 1f, 20f)
    private val delay           = IntSetting("Delay",              50,  0,  500)
    private val offhand         = BoolSetting("Offhand",           true)
    private val shortcut        = BoolSetting("Shortcut",          false)

    override fun registerSettings() = listOf(healthThreshold, delay, offhand, shortcut)

    // ── İç durum ──────────────────────────────────────────────────────────
    @Volatile private var currentHealth  = 20f
    @Volatile private var totemHotbarSlot= -1   // envanterde totem hangi slot'ta
    @Volatile private var totemItemId    = 0    // Bedrock totem item ID (702)
    @Volatile private var offhandHasTotem= false
    @Volatile private var lastEquipMs    = 0L

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    companion object {
        private const val TAG             = "AutoTotem"
        private const val TOTEM_ITEM_ID   = 702    // Totem of Undying — Bedrock numeric ID
        private const val OFFHAND_SLOT    = 119    // Bedrock offhand slot
        private const val CONTAINER_ID    = 0      // Player inventory container
    }

    // ── Yaşam döngüsü ─────────────────────────────────────────────────────

    override fun onEnable() {
        PacketEventBus.register(this)
        currentHealth   = 20f
        totemHotbarSlot = -1
        offhandHasTotem = false
        watchJob = scope.launch { watchLoop() }
        Log.d(TAG, "Etkinleştirildi (threshold=${healthThreshold.value})")
    }

    override fun onDisable() {
        watchJob?.cancel()
        PacketEventBus.unregister(this)
    }

    // ── Paket dinleyici ───────────────────────────────────────────────────

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (event.packetId) {
            BedrockPacketIds.UPDATE_ATTRIBUTES -> parseHealth(event.data)
            BedrockPacketIds.INVENTORY_CONTENT -> parseInventory(event.data)
            BedrockPacketIds.INVENTORY_SLOT    -> parseInventorySlot(event.data)
            // Totem kullanılınca (oyuncu öldü ve totem devreye girdi)
            BedrockPacketIds.ENTITY_EVENT      -> parseEntityEvent(event.data)
        }
    }

    // ── Can takibi ────────────────────────────────────────────────────────

    private fun parseHealth(data: ByteArray) {
        /*
         * UpdateAttributes paketi:
         * [packetId varint] [runtimeId varlong]
         * [count varint] → her attribute:
         *   [min float32LE] [max float32LE] [current float32LE]
         *   [default float32LE] [name string]
         *
         * "minecraft:health" attribute'u current değerini okuyoruz.
         */
        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(data, pos); pos = p1
            val (rid, p2) = PacketHelper.readVarLong(data, pos); pos = p2
            if (rid != EntityTracker.selfRuntimeId) return

            val (count, p3) = PacketHelper.readVarInt(data, pos); pos = p3
            repeat(count) {
                if (pos + 16 > data.size) return@repeat
                // min, max, current, default (her biri 4 byte float LE)
                val current = PacketHelper.readFloatLE(data, pos + 8)
                pos += 16
                val (attrName, p4) = PacketHelper.readString(data, pos); pos = p4
                if (attrName == "minecraft:health") {
                    currentHealth = current
                    Log.v(TAG, "Can: $currentHealth")
                }
            }
        } catch (_: Exception) {}
    }

    private fun parseInventory(data: ByteArray) {
        /*
         * InventoryContent: [packetId] [windowId varint] [count varint]
         * Her item: [networkId varint] [count byte] [damage varint] [...]
         * Totem'i (ID=702) hotbar'da buluyoruz.
         */
        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(data, pos); pos = p1
            val (winId, p2) = PacketHelper.readVarInt(data, pos); pos = p2
            if (winId != CONTAINER_ID) return

            val (count, p3) = PacketHelper.readVarInt(data, pos); pos = p3
            totemHotbarSlot = -1
            offhandHasTotem = false

            for (slot in 0 until count) {
                val (itemId, p4) = PacketHelper.readVarInt(data, pos); pos = p4
                if (itemId == 0) continue  // air
                pos += 1  // count byte
                val (_, p5) = PacketHelper.readVarInt(data, pos); pos = p5  // damage

                when {
                    itemId == TOTEM_ITEM_ID && slot == OFFHAND_SLOT -> {
                        offhandHasTotem = true
                    }
                    itemId == TOTEM_ITEM_ID && totemHotbarSlot == -1 -> {
                        totemHotbarSlot = slot
                    }
                }
                // Kalan item nbt'yi atla (basit: varint tag kontrolü)
                val (hasNbt, p6) = PacketHelper.readVarInt(data, pos); pos = p6
                if (hasNbt != 0) {
                    // NBT blob — basit atlatma, gerçek parse gerekebilir
                    // Şimdilik kırılmamak için try-catch içinde bırakıyoruz
                }
            }
        } catch (_: Exception) {}
    }

    private fun parseInventorySlot(data: ByteArray) {
        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(data, pos); pos = p1
            val (winId, p2) = PacketHelper.readVarInt(data, pos); pos = p2
            if (winId != CONTAINER_ID) return
            val (slot, p3) = PacketHelper.readVarInt(data, pos); pos = p3
            val (itemId, _) = PacketHelper.readVarInt(data, pos)

            when (slot) {
                OFFHAND_SLOT   -> offhandHasTotem = (itemId == TOTEM_ITEM_ID)
                in 0..35       -> if (itemId == TOTEM_ITEM_ID && totemHotbarSlot == -1)
                                      totemHotbarSlot = slot
            }
        } catch (_: Exception) {}
    }

    private fun parseEntityEvent(data: ByteArray) {
        /*
         * EntityEvent ID 57 = totem kullanıldı (animasyon)
         * Totem aktifleşince offhand boş olur → yeniden tak
         */
        try {
            var pos = 0
            val (_, p1) = PacketHelper.readVarInt(data, pos); pos = p1
            val (rid, p2) = PacketHelper.readVarLong(data, pos); pos = p2
            if (rid != EntityTracker.selfRuntimeId) return
            val (eventId, _) = PacketHelper.readVarInt(data, pos)
            if (eventId == 57) {
                offhandHasTotem = false
                Log.d(TAG, "Totem kullanıldı — yeniden takılacak")
            }
        } catch (_: Exception) {}
    }

    // ── İzleme döngüsü ────────────────────────────────────────────────────

    private suspend fun watchLoop() {
        while (coroutineContext.isActive) {
            if (isEnabled) {
                val needsTotem = currentHealth <= healthThreshold.value && !offhandHasTotem
                val hasTotem   = totemHotbarSlot >= 0

                if (needsTotem && hasTotem) {
                    val now = System.currentTimeMillis()
                    if (now - lastEquipMs >= delay.value) {
                        lastEquipMs = now
                        equipTotem()
                    }
                }
            }
            delay(50L)
        }
    }

    private fun equipTotem() {
        val slot = totemHotbarSlot
        if (slot < 0) return

        Log.d(TAG, "Totem takılıyor (slot=$slot, offhand=${offhand.value})")

        if (offhand.value) {
            // MobEquipment → offhand slota totem koy
            equipToOffhand(slot)
        } else {
            // Hotbar slot 0'a taşı
            swapToHotbar(slot, 0)
        }
    }

    /**
     * MobEquipment paketi ile offhand'e item koy.
     * Sunucuya: konteyner=119 (offhand), slot=slot, itemId=702
     */
    private fun equipToOffhand(fromSlot: Int) {
        PacketHelper.injectToServer(buildMobEquipment(
            runtimeId   = EntityTracker.selfRuntimeId,
            windowId    = CONTAINER_ID,
            slot        = fromSlot,
            hotbarSlot  = OFFHAND_SLOT,
            itemId      = TOTEM_ITEM_ID
        ))
    }

    private fun swapToHotbar(fromSlot: Int, toSlot: Int) {
        PacketHelper.injectToServer(buildMobEquipment(
            runtimeId  = EntityTracker.selfRuntimeId,
            windowId   = CONTAINER_ID,
            slot       = fromSlot,
            hotbarSlot = toSlot,
            itemId     = TOTEM_ITEM_ID
        ))
    }

    // ── Paket builder ─────────────────────────────────────────────────────

    private fun buildMobEquipment(
        runtimeId  : Long,
        windowId   : Int,
        slot       : Int,
        hotbarSlot : Int,
        itemId     : Int
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        PacketHelper.writeVarInt(out, BedrockPacketIds.MOB_EQUIPMENT)
        PacketHelper.writeVarLong(out, runtimeId)
        // Item descriptor
        PacketHelper.writeVarInt(out, itemId)   // networkId
        out.write(1)                             // count
        PacketHelper.writeVarInt(out, 0)         // damage/data
        out.write(0)                             // hasNetId = false
        // Slot bilgileri
        out.write(slot)
        out.write(hotbarSlot)
        out.write(windowId)
        return PacketHelper.wrapBatch(out.toByteArray())
    }
}