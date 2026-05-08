package com.oxclient.module.combat

import android.util.Log
import com.oxclient.events.PacketDirection
import com.oxclient.module.BaseModule
import com.oxclient.proxy.BedrockPacketIds
import com.oxclient.proxy.PacketProcessor
import io.netty.buffer.Unpooled
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * KillAura
 *
 * Menzil içindeki entity'lere otomatik saldırı yapar.
 *
 * Mekanizma:
 *  1. AddPlayer / AddEntity paketlerini dinleyerek yakın entity listesi oluşturur.
 *  2. MovePlayer / MoveEntityAbsolute paketlerini dinleyerek entity konumlarını günceller.
 *  3. Her tick'te menzil içindeki ilk uygun hedefi seçer.
 *  4. AnimatePacket (swing) + InventoryTransaction (attack) enjekte eder.
 *  5. RemoveEntity paketinde entity'yi listeden siler.
 */
class KillAura : BaseModule(
    name        = "KillAura",
    description = "Menzil içindeki düşmanlara otomatik saldırır",
    category    = Category.COMBAT
) {
    // ── Ayarlar ───────────────────────────────────────────────────────────
    var range         : Float = 4.0f       // saldırı menzili
    var attackDelay   : Long  = 500L       // ms cinsinden CPS kontrolü
    var swingAnimation: Boolean = true     // el sallama animasyonu
    var onlyPlayers   : Boolean = false    // sadece oyunculara saldır
    var rotations     : Boolean = false    // rotasyon düzeltmesi (basit)

    // ── İç durum ──────────────────────────────────────────────────────────
    private data class TrackedEntity(
        val runtimeId  : Long,
        var x          : Float,
        var y          : Float,
        var z          : Float,
        val isPlayer   : Boolean,
        var lastAttack : Long = 0L
    )

    private val entities = ConcurrentHashMap<Long, TrackedEntity>()
    private var playerX  = 0f; private var playerY = 0f; private var playerZ = 0f
    private var playerRuntimeId = 0L

    private var tickJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ─────────────────────────────────────────────────────────────────────
    //  ETKİNLEŞTİRME
    // ─────────────────────────────────────────────────────────────────────

    override fun onEnable() {
        entities.clear()
        subscribePackets()
        tickJob = scope.launch { tickLoop() }
        Log.d("KillAura", "Etkinleştirildi (range=$range, delay=${attackDelay}ms)")
    }

    override fun onDisable() {
        tickJob?.cancel()
        entities.clear()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PAKET DİNLEME
    // ─────────────────────────────────────────────────────────────────────

    private fun subscribePackets() {
        // StartGame → kendi runtimeId'mizi öğren
        subscribe(packetId = BedrockPacketIds.START_GAME, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()                          // packet ID
                playerRuntimeId = readVarLong(buf)     // entityIdSelf
            } catch (_: Exception) {}
            finally { buf.release() }
        }

        // AddPlayer — diğer oyuncuları takip et
        subscribe(packetId = BedrockPacketIds.ADD_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseAddEntity(event.data, isPlayer = true)
        }

        // AddEntity — mob'ları takip et
        subscribe(packetId = BedrockPacketIds.ADD_ENTITY, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseAddEntity(event.data, isPlayer = false)
        }

        // MoveEntityAbsolute — konum güncelle
        subscribe(packetId = BedrockPacketIds.MOVE_ENTITY_ABSOLUTE, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseMoveEntityAbsolute(event.data)
        }

        // MovePlayer — hem kendi konumumuzu hem de oyuncuları takip et
        subscribe(packetId = BedrockPacketIds.MOVE_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseMovePlayer(event.data)
        }

        // RemoveEntity — listeden sil
        subscribe(packetId = BedrockPacketIds.REMOVE_ENTITY, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                val rid = readVarLong(buf)
                entities.remove(rid)
            } catch (_: Exception) {}
            finally { buf.release() }
        }

        // PlayerAuthInput — kendi konumumuzu güncelle (server-bound)
        subscribe(packetId = BedrockPacketIds.PLAYER_AUTH_INPUT, direction = PacketDirection.SERVER_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                playerX = buf.readFloatLE()
                playerY = buf.readFloatLE()
                playerZ = buf.readFloatLE()
            } catch (_: Exception) {}
            finally { buf.release() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TICK DÖNGÜSÜ
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (isActive) {
            if (enabled) tryAttack()
            delay(50L)   // 20 TPS
        }
    }

    private fun tryAttack() {
        val now    = System.currentTimeMillis()
        val target = selectTarget() ?: return
        if (now - target.lastAttack < attackDelay) return

        target.lastAttack = now

        // 1. Swing animasyonu
        if (swingAnimation) {
            val anim = PacketProcessor.buildAnimatePacket(
                action          = 4,
                entityRuntimeId = playerRuntimeId
            )
            PacketProcessor.injectToServer(anim)
        }

        // 2. Attack (InventoryTransaction USE_ITEM_ON_ENTITY)
        val attack = PacketProcessor.buildAttackPacket(target.runtimeId)
        PacketProcessor.injectToServer(attack)

        Log.v("KillAura", "Saldırı → entity ${target.runtimeId} (%.1f blok)".format(
            distance(target.x, target.y, target.z)
        ))
    }

    private fun selectTarget(): TrackedEntity? {
        return entities.values
            .filter { entity ->
                if (onlyPlayers && !entity.isPlayer) return@filter false
                entity.runtimeId != playerRuntimeId &&
                distance(entity.x, entity.y, entity.z) <= range
            }
            .minByOrNull { distance(it.x, it.y, it.z) }
    }

    private fun distance(x: Float, y: Float, z: Float): Float {
        val dx = x - playerX; val dy = y - playerY; val dz = z - playerZ
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PARSE YARDIMCILARI
    // ─────────────────────────────────────────────────────────────────────

    private fun parseAddEntity(data: ByteArray, isPlayer: Boolean) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte()                      // packet ID
            val uniqueId  = readVarLong(buf)    // uniqueEntityId (signed VarLong)
            val runtimeId = readVarLong(buf)
            if (!isPlayer) readString(buf)      // entity type string (only for AddEntity)
            val x = buf.readFloatLE()
            val y = buf.readFloatLE()
            val z = buf.readFloatLE()
            entities[runtimeId] = TrackedEntity(runtimeId, x, y, z, isPlayer)
        } catch (_: Exception) {}
        finally { buf.release() }
    }

    private fun parseMoveEntityAbsolute(data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte()
            val runtimeId = readVarLong(buf)
            buf.readByte()                      // flags
            val x = buf.readFloatLE()
            val y = buf.readFloatLE()
            val z = buf.readFloatLE()
            entities[runtimeId]?.let { it.x = x; it.y = y; it.z = z }
        } catch (_: Exception) {}
        finally { buf.release() }
    }

    private fun parseMovePlayer(data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte()
            val runtimeId = readVarLong(buf)
            val x = buf.readFloatLE()
            val y = buf.readFloatLE()
            val z = buf.readFloatLE()
            if (runtimeId == playerRuntimeId) {
                playerX = x; playerY = y; playerZ = z
            } else {
                entities[runtimeId]?.let { it.x = x; it.y = y; it.z = z }
            }
        } catch (_: Exception) {}
        finally { buf.release() }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  VarInt / String okuma
    // ─────────────────────────────────────────────────────────────────────

    private fun readVarLong(buf: io.netty.buffer.ByteBuf): Long {
        var result = 0L; var shift = 0
        while (buf.isReadable) {
            val b = buf.readByte().toLong()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result
    }

    private fun readString(buf: io.netty.buffer.ByteBuf): String {
        var len = 0; var shift = 0
        while (buf.isReadable) {
            val b = buf.readByte().toInt()
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (len <= 0 || len > buf.readableBytes()) return ""
        val bytes = ByteArray(len)
        buf.readBytes(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
