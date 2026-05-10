package com.oxclient.core.proxy

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.ui.overlay.OverlayLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * EntityTracker
 *
 * ✅ FIX: parseAuthInput'ta headYaw offset sorunu giderildi.
 *
 * Bedrock 1.21.60 PLAYER_AUTH_INPUT gerçek formatı (paket ID varint atlandıktan sonra):
 *   pitch   : float LE (4 byte)
 *   yaw     : float LE (4 byte)
 *   headYaw : float LE (4 byte)  ← BU ALAN ATLANIYOR
 *   x       : float LE (4 byte)
 *   y       : float LE (4 byte)
 *   z       : float LE (4 byte)
 *
 * Önceki kodda headYaw atlanmıyordu → x/y/z 4 byte geriden okunuyordu
 * → koordinatlar NaN/saçma → koordinat doğrulama yoktu → selfX/Y/Z hep 0.
 *
 * Bedrock protokol değişiklik kaynağı: 1.19.10+ ile headYaw pitch/yaw'dan
 * hemen sonra gelmeye başladı. Eski kodun yorumu ("headYaw araya girmiyor")
 * hatalıydı.
 */
object EntityTracker : PacketListener {

    private const val TAG = "EntityTracker"

    override val priority: Int = 10  // PacketEventBus'ta en önce çalışır

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

    fun register() {
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
        return kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
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
            val (_, p1) = PacketHelper.readZigzagVarLong(d, pos); pos = p1
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
            val (_, p7)        = PacketHelper.readVarInt(d, pos);          pos = p7  // gameType

            if (pos + 12 > d.size) {
                OverlayLogger.w(TAG, "AddPlayer rid=$rid — yetersiz veri (pos=$pos size=${d.size})")
                return
            }
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)

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
            val (uniqueId, p1) = PacketHelper.readZigzagVarLong(d, pos); pos = p1
            val (rid, p2)      = PacketHelper.readVarLong(d, pos);        pos = p2
            val (_, p3)        = PacketHelper.readString(d, pos);         pos = p3
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
            if (runtimeId != null) entities.remove(runtimeId)
        } catch (_: Exception) {}
    }

    /**
     * PLAYER_AUTH_INPUT — Bedrock 1.21.60 formatı:
     *
     * [packetId varint]  ← atlanır
     * [pitch    float]   ← pos
     * [yaw      float]   ← pos+4
     * [headYaw  float]   ← pos+8  ✅ ATLANMASI GEREKEN ALAN
     * [x        float]   ← pos+12
     * [y        float]   ← pos+16
     * [z        float]   ← pos+20
     *
     * Önceki kod headYaw'ı atlamamış → x/y/z 4 byte geriden okunuyordu
     * → koordinatlar saçma → selfX/Y/Z hep 0 görünüyordu.
     */
    private fun parseAuthInput(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0

            val pitch   = PacketHelper.readFloatLE(d, pos); pos += 4  // pitch
            val yaw     = PacketHelper.readFloatLE(d, pos); pos += 4  // yaw
            /* headYaw */ PacketHelper.readFloatLE(d, pos); pos += 4  // headYaw — ATLANIYOR
            val x       = PacketHelper.readFloatLE(d, pos); pos += 4  // x
            val y       = PacketHelper.readFloatLE(d, pos); pos += 4  // y
            val z       = PacketHelper.readFloatLE(d, pos)             // z

            // Koordinat doğrulama — NaN/Inf veya imkansız değerler kabul edilmez
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return

            selfX = x; selfY = y; selfZ = z
            selfYaw = yaw; selfPitch = pitch
        } catch (e: Exception) {
            // Sessiz yutma kaldırıldı — parse hatası panelde görünsün
            OverlayLogger.w(TAG, "AuthInput parse hatası: ${e.message}")
        }
    }
}
