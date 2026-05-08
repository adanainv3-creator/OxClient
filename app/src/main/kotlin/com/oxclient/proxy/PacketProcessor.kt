package com.oxclient.proxy

import android.util.Log
import com.oxclient.events.PacketDirection
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import io.netty.buffer.Unpooled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PacketProcessor
 *
 * MITM'in kalbidir.
 * - Her yönden gelen decode edilmiş Bedrock paketini alır
 * - PacketEventBus üzerinden modüllere bildirir
 * - Modüllerin inject etmek istediği ek paketleri pipeline'a ekler
 * - Cancel edilen paketleri filtreler
 */
object PacketProcessor {

    private const val TAG = "PacketProcessor"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Istatistik ────────────────────────────────────────────────────────
    @Volatile var packetsIntercepted = 0L
        private set
    @Volatile var packetsInjected    = 0L
        private set
    @Volatile var packetsCancelled   = 0L
        private set

    // ── Enjeksiyona hazır ek paket kuyruğu ───────────────────────────────
    // Her relay tick'te tüketilir
    private val injectQueue = ArrayDeque<PendingPacket>()

    data class PendingPacket(
        val data     : ByteArray,
        val direction: PacketDirection   // CLIENT_BOUND = sunucudan gelip MC'ye gitsin
                                         // SERVER_BOUND = MC'den gelmiş gibi sunucuya gitsin
    )

    // ─────────────────────────────────────────────────────────────────────
    //  ANA İŞLEM FONKSİYONU
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Gelen tek bir ham Bedrock paketini işler.
     *
     * @param rawPacket  VarInt-length-prefix'i GEÇİLMİŞ, ID'den başlayan ham baytlar
     * @param direction  Paketin hangi yönde gittiği
     * @return  Yönlendirme için kullanılacak bayt dizisi veya null (cancel)
     */
    fun process(rawPacket: ByteArray, direction: PacketDirection): ByteArray? {
        packetsIntercepted++

        // İlk byte → Paket ID (VarInt olabilir; basit ID'ler tek byte'tır)
        val packetId = readPacketId(rawPacket)

        // Sadece combat ile ilgili paketleri modüllere gönder (performans)
        if (packetId in BedrockPacketIds.COMBAT_RELEVANT) {
            val event = PacketEvent(
                id        = packetId,
                direction = direction,
                data      = rawPacket.copyOf(),
                mutable   = true
            )

            // Senkron yayınla (modüller aynı thread'de işler)
            PacketEventBus.publish(event)

            if (event.cancelled) {
                packetsCancelled++
                return null
            }

            return event.data
        }

        return rawPacket
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ENJEKSİYON API'Sİ  (modüller tarafından çağrılır)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Sunucuya göndermek üzere sahte bir paket kuyruğa ekler.
     * Örnek: KillAura → AttackEntityPacket enjekte eder.
     */
    fun injectToServer(data: ByteArray) {
        synchronized(injectQueue) {
            injectQueue.addLast(PendingPacket(data, PacketDirection.SERVER_BOUND))
            packetsInjected++
        }
    }

    /**
     * İstemciye (Minecraft) göndermek üzere sahte bir paket kuyruğa ekler.
     * Örnek: Criticals → animate entity.
     */
    fun injectToClient(data: ByteArray) {
        synchronized(injectQueue) {
            injectQueue.addLast(PendingPacket(data, PacketDirection.CLIENT_BOUND))
            packetsInjected++
        }
    }

    /**
     * Kuyruktaki tüm enjekte edilecek paketleri al ve temizle.
     * BedrockRelay her relay döngüsünde bunu çağırır.
     */
    fun drainInjected(): List<PendingPacket> {
        synchronized(injectQueue) {
            val list = injectQueue.toList()
            injectQueue.clear()
            return list
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PAKET BUILDER YARDIMCıLARI  (modüller kullanır)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * AnimatePacket oluşturur (swing arm = 0x04 aksiyon).
     * Criticals modülü bunu kritik vuruş animasyonu için kullanır.
     */
    fun buildAnimatePacket(action: Int = 4, entityRuntimeId: Long): ByteArray {
        val buf = Unpooled.buffer()
        writeVarInt(buf, BedrockPacketIds.ANIMATE)
        writeVarInt(buf, action)
        writeVarLong(buf, entityRuntimeId)
        return buf.array().copyOf(buf.writerIndex()).also { buf.release() }
    }

    /**
     * MovePlayerPacket oluşturur.
     * TPAura modülü anlık konum ışınlaması için kullanır.
     */
    fun buildMovePlayerPacket(
        entityRuntimeId : Long,
        x: Float, y: Float, z: Float,
        yaw: Float, pitch: Float, headYaw: Float,
        onGround: Boolean = true,
        teleport: Boolean = true
    ): ByteArray {
        val buf = Unpooled.buffer()
        writeVarInt(buf, BedrockPacketIds.MOVE_PLAYER)
        writeVarLong(buf, entityRuntimeId)
        buf.writeFloatLE(x); buf.writeFloatLE(y); buf.writeFloatLE(z)
        buf.writeFloatLE(pitch); buf.writeFloatLE(yaw); buf.writeFloatLE(headYaw)
        buf.writeByte(0)         // mode: NORMAL=0, RESET=1, TELEPORT=2
        buf.writeBoolean(onGround)
        writeVarLong(buf, 0)     // ridingEid
        if (teleport) {
            buf.writeByte(0)     // teleport cause
            buf.writeByte(0)     // entity type
        }
        buf.writeBoolean(false)  // tick (unused)
        return buf.array().copyOf(buf.writerIndex()).also { buf.release() }
    }

    /**
     * InventoryTransactionPacket (Attack entity) oluşturur.
     * KillAura bu paketi hedef entity'ye saldırı için kullanır.
     */
    fun buildAttackPacket(entityRuntimeId: Long): ByteArray {
        val buf = Unpooled.buffer()
        writeVarInt(buf, BedrockPacketIds.INVENTORY_TRANSACTION)
        writeVarInt(buf, 0)      // legacy request ID
        // TransactionType = USE_ITEM_ON_ENTITY (2)
        writeVarInt(buf, 2)
        writeVarLong(buf, entityRuntimeId)
        writeVarInt(buf, 1)      // action = attack (1)
        writeVarInt(buf, 0)      // hotbar slot
        // Held item (air)
        buf.writeByte(0)         // item id = 0 (air)
        // From position (0,0,0)
        buf.writeFloatLE(0f); buf.writeFloatLE(0f); buf.writeFloatLE(0f)
        // Click position
        buf.writeFloatLE(0f); buf.writeFloatLE(0f); buf.writeFloatLE(0f)
        return buf.array().copyOf(buf.writerIndex()).also { buf.release() }
    }

    /**
     * ItemStackRequest paketi oluşturur — offhand/totem swap için.
     * AutoTotem bu paketi kullanır.
     */
    fun buildSwapTotemPacket(totemSlot: Int, offhandSlot: Int = 119): ByteArray {
        val buf = Unpooled.buffer()
        writeVarInt(buf, BedrockPacketIds.ITEM_STACK_REQUEST)
        writeVarInt(buf, 1)      // request count
        writeVarInt(buf, System.currentTimeMillis().toInt()) // request ID
        writeVarInt(buf, 1)      // action count
        buf.writeByte(7)         // action type: SWAP = 7
        // Source slot
        buf.writeByte(12)        // container: INVENTORY = 12
        buf.writeByte(totemSlot.toByte().toInt())
        writeVarInt(buf, 0)      // source stackId
        // Dest slot (offhand = 119)
        buf.writeByte(33)        // container: OFFHAND = 33
        buf.writeByte(0)
        writeVarInt(buf, 0)
        return buf.array().copyOf(buf.writerIndex()).also { buf.release() }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  YARDIMCILAR
    // ─────────────────────────────────────────────────────────────────────

    private fun readPacketId(data: ByteArray): Int {
        if (data.isEmpty()) return -1
        // VarInt decode (maksimum 3 byte yeterli protokol 748 için)
        var result = 0; var shift = 0
        for (b in data) {
            result = result or ((b.toInt() and 0x7F) shl shift)
            if (b.toInt() and 0x80 == 0) break
            shift += 7
            if (shift > 21) break
        }
        return result
    }

    private fun writeVarInt(buf: io.netty.buffer.ByteBuf, value: Int) {
        var v = value
        while (v and -0x80 != 0) { buf.writeByte((v and 0x7F) or 0x80); v = v ushr 7 }
        buf.writeByte(v)
    }

    private fun writeVarLong(buf: io.netty.buffer.ByteBuf, value: Long) {
        var v = value
        while (v and -0x80L != 0L) { buf.writeByte((v and 0x7F).toInt() or 0x80); v = v ushr 7 }
        buf.writeByte(v.toInt())
    }

    fun resetStats() {
        packetsIntercepted = 0L; packetsInjected = 0L; packetsCancelled = 0L
    }
}
