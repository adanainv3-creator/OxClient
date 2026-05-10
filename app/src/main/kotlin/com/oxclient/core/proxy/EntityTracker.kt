package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.ui.overlay.OverlayLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * EntityTracker
 *
 * Entity yönetimi için iki ayrı map kullanılır:
 *
 *   entities:        runtimeId  → TrackedEntity   (hareket/saldırı için)
 *   uniqueToRuntime: uniqueId   → runtimeId        (REMOVE_ENTITY için)
 *
 * REMOVE_ENTITY paketi uniqueEntityId (zigzag signed varlong) içerir,
 * runtimeId içermez. uniqueToRuntime olmadan entity'ler hiç silinmez →
 * KillAura ölmüş/gitmiş oyunculara saldırmaya devam eder, liste şişer.
 *
 * ✅ FIX: PLAYER_AUTH_INPUT (0x91) parse düzeltildi.
 * Gerçek Bedrock 1.21.x format (paket ID varinti atlandıktan sonra):
 *   pitch    : float LE (4 byte)
 *   yaw      : float LE (4 byte)
 *   x        : float LE (4 byte)   ← headYaw BURADA DEĞİL
 *   y        : float LE (4 byte)
 *   z        : float LE (4 byte)
 *   headYaw  : float LE (4 byte)   ← sonradan geliyor
 *   ...
 * Önceki kod headYaw'ı 3. float sanıp atlıyordu → x/y/z yanlış offset'ten
 * okunuyordu → selfX/Y/Z hep 0 → modüller selfRuntimeId=0 ile saldırı
 * yapıyor, Criticals yanlış konum kullanıyor, TPAura'nın origX/Y/Z = 0.
 */
object EntityTracker : PacketListener {

    private const val TAG = "EntityTracker"

    override val priority: Int = 10

    data class TrackedEntity(
        val runtimeId : Long,
        val uniqueId  : Long,
        var x         : Float,
        var y         : Float,
        var z         : Float,
        val isPlayer  : Boolean,
        var health    : Float = 20f,
        var yaw       : Float = 0f
    )

    private val entities        = ConcurrentHashMap<Long, TrackedEntity>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()

    @Volatile var selfRuntimeId : Long  = 0L
    @Volatile var selfX         : Float = 0f
    @Volatile var selfY         : Float = 0f
    @Volatile var selfZ         : Float = 0f
    @Volatile var selfYaw       : Float = 0f
    @Volatile var selfPitch     : Float = 0f

    fun register()   {
        PacketEventBus.register(this)
        OverlayLogger.d(TAG, "EntityTracker kayıt oldu")
    }
    fun unregister() {
        PacketEventBus.unregister(this)
        entities.clear()
        uniqueToRuntime.clear()
        selfRuntimeId = 0L
        selfX = 0f; selfY = 0f; selfZ = 0f
        OverlayLogger.d(TAG, "EntityTracker sıfırlandı")
    }

    fun getEntities(): Map<Long, TrackedEntity> = entities

    fun getEntitiesInRange(range: Float): List<TrackedEntity> =
        entities.values.filter { e ->
            e.runtimeId != selfRuntimeId && distanceTo(e) <= range
        }

    fun distanceTo(e: TrackedEntity): Float {
        val dx = e.x - selfX; val dy = e.y - selfY; val dz = e.z - selfZ
        return kotlin.math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
    }

    fun angleToEntity(e: TrackedEntity): Float {
        val dx = e.x - selfX; val dz = e.z - selfZ
        val targetYaw = Math.toDegrees(kotlin.math.atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        var diff = kotlin.math.abs(targetYaw - selfYaw) % 360f
        if (diff > 180f) diff = 360f - diff
        return diff
    }

    override fun onPacket(event: PacketEvent) {
        try {
            when (event.packetId) {
                BedrockPacketIds.START_GAME           -> parseStartGame(event.data)
                BedrockPacketIds.ADD_PLAYER           -> parseAddPlayer(event.data)
                BedrockPacketIds.ADD_ENTITY           -> parseAddEntity(event.data)
                BedrockPacketIds.MOVE_PLAYER          -> parseMovePlayer(event.data)
                BedrockPacketIds.MOVE_ENTITY_ABSOLUTE -> parseMoveEntity(event.data)
                BedrockPacketIds.REMOVE_ENTITY        -> parseRemoveEntity(event.data)
                BedrockPacketIds.PLAYER_AUTH_INPUT    -> parseAuthInput(event.data)
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────

    private fun parseStartGame(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0
            // uniqueEntityId → zigzag signed varlong, sadece atla
            val (_, p1) = PacketHelper.readZigzagVarLong(d, pos); pos = p1
            // runtimeEntityId → selfRuntimeId
            val (rid, _) = PacketHelper.readVarLong(d, pos)
            selfRuntimeId = rid
            OverlayLogger.i(TAG, "StartGame → selfRuntimeId=$rid")
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "StartGame parse hatası: ${e.message}")
        }
    }

    private fun parseAddPlayer(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0
            pos += 16  // UUID (16 byte sabit)
            val (_, p1)        = PacketHelper.readString(d, pos);          pos = p1  // username
            val (uniqueId, p2) = PacketHelper.readZigzagVarLong(d, pos);   pos = p2  // uniqueEntityId
            val (rid, p3)      = PacketHelper.readVarLong(d, pos);         pos = p3  // runtimeEntityId
            val (_, p4)        = PacketHelper.readString(d, pos);          pos = p4  // platformChatId
            val (_, p5)        = PacketHelper.readString(d, pos);          pos = p5  // deviceId
            val (_, p6)        = PacketHelper.readVarInt(d, pos);          pos = p6  // buildPlatform
            // ✅ FIX: Bedrock 1.21.60 — gameType (varint) eksikti
            val (_, p7)        = PacketHelper.readVarInt(d, pos);          pos = p7  // gameType

            // Bounds kontrolü: x,y,z için 12 byte gerekli
            if (pos + 12 > d.size) {
                OverlayLogger.w(TAG, "AddPlayer rid=$rid — yetersiz veri (pos=$pos size=${d.size})")
                return
            }
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)

            // Koordinat doğrulama: NaN/Inf veya absürd değerleri kaydetme
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() ||
                Math.abs(x) > 3e7f || Math.abs(y) > 4096f || Math.abs(z) > 3e7f) {
                OverlayLogger.w(TAG, "AddPlayer rid=$rid — geçersiz koordinat x=$x y=$y z=$z")
                return
            }

            if (rid != selfRuntimeId) {
                entities[rid] = TrackedEntity(rid, uniqueId, x, y, z, isPlayer = true)
                uniqueToRuntime[uniqueId] = rid
                OverlayLogger.d(TAG, "AddPlayer rid=$rid x=%.1f y=%.1f z=%.1f".format(x, y, z))
            }
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "AddPlayer parse hatası: ${e.message}")
        }
    }

    private fun parseAddEntity(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0
            val (uniqueId, p1) = PacketHelper.readZigzagVarLong(d, pos);   pos = p1  // uniqueEntityId
            val (rid, p2)      = PacketHelper.readVarLong(d, pos);         pos = p2  // runtimeEntityId
            val (_, p3)        = PacketHelper.readString(d, pos);          pos = p3  // entity type → atla
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)
            entities[rid] = TrackedEntity(rid, uniqueId, x, y, z, isPlayer = false)
            uniqueToRuntime[uniqueId] = rid
        } catch (_: Exception) {}
    }

    private fun parseMovePlayer(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0
            val (rid, p1) = PacketHelper.readVarLong(d, pos); pos = p1
            val x     = PacketHelper.readFloatLE(d, pos); pos += 4
            val y     = PacketHelper.readFloatLE(d, pos); pos += 4
            val z     = PacketHelper.readFloatLE(d, pos); pos += 4
            val pitch = PacketHelper.readFloatLE(d, pos); pos += 4
            val yaw   = PacketHelper.readFloatLE(d, pos)
            if (rid == selfRuntimeId) {
                selfX = x; selfY = y; selfZ = z
                selfPitch = pitch; selfYaw = yaw
            } else {
                entities[rid]?.let { it.x = x; it.y = y; it.z = z; it.yaw = yaw }
            }
        } catch (_: Exception) {}
    }

    private fun parseMoveEntity(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0
            val (rid, p1) = PacketHelper.readVarLong(d, pos); pos = p1
            pos += 1  // flags byte
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)
            entities[rid]?.let { it.x = x; it.y = y; it.z = z }
        } catch (_: Exception) {}
    }

    private fun parseRemoveEntity(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0)
            val (uniqueId, _) = PacketHelper.readZigzagVarLong(d, p0)
            val runtimeId = uniqueToRuntime.remove(uniqueId)
            if (runtimeId != null) {
                entities.remove(runtimeId)
            }
        } catch (_: Exception) {}
    }

    /**
     * PLAYER_AUTH_INPUT — Bedrock 1.21.x gerçek formatı:
     *
     * [packetId varint]
     * [pitch    float LE]   ← pos
     * [yaw      float LE]   ← pos+4
     * [x        float LE]   ← pos+8   ✅ headYaw BURAYA GELMİYOR
     * [y        float LE]   ← pos+12
     * [z        float LE]   ← pos+16
     * [headYaw  float LE]   ← pos+20  (sonradan geliyor)
     * ...
     *
     * ÖNCEKİ HATA: pos+8'e headYaw sanılarak pos+=4 ekleniyor,
     * x pos+12'den okunuyordu → her şey 4 byte kaymış → 0,0,0 görünüyordu.
     */
    private fun parseAuthInput(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0

            // ✅ FIX: Doğru sıra — pitch, yaw, SONRA x/y/z
            // headYaw araya girmiyor
            val pitch = PacketHelper.readFloatLE(d, pos); pos += 4  // pitch
            val yaw   = PacketHelper.readFloatLE(d, pos); pos += 4  // yaw
            val x     = PacketHelper.readFloatLE(d, pos); pos += 4  // x ← headYaw atlanmıyor
            val y     = PacketHelper.readFloatLE(d, pos); pos += 4  // y
            val z     = PacketHelper.readFloatLE(d, pos)            // z

            selfX = x; selfY = y; selfZ = z
            selfYaw = yaw; selfPitch = pitch
        } catch (_: Exception) {}
    }
}
