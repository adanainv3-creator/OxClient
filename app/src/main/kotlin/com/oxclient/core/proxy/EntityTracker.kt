package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
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
 * Diğer tüm parse fonksiyonlarında da uniqueEntityId alanları
 * readZigzagVarLong ile okunmak zorundadır; readVarLong ile okumak
 * zigzag decode'u yapmaz ve byte sayısı yanlış olabilir.
 */
object EntityTracker : PacketListener {

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

    fun register()   { PacketEventBus.register(this) }
    fun unregister() {
        PacketEventBus.unregister(this)
        entities.clear()
        uniqueToRuntime.clear()
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
            Log.i("EntityTracker", "selfRuntimeId = $rid")
        } catch (e: Exception) {
            Log.w("EntityTracker", "StartGame parse hatası: ${e.message}")
        }
    }

    private fun parseAddPlayer(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0
            pos += 16  // UUID (16 byte sabit)
            val (_, p1)        = PacketHelper.readString(d, pos);          pos = p1  // username
            val (uniqueId, p2) = PacketHelper.readZigzagVarLong(d, pos);   pos = p2  // uniqueEntityId
            val (rid, p3)      = PacketHelper.readVarLong(d, pos);         pos = p3  // runtimeEntityId
            val (_, p4)        = PacketHelper.readString(d, pos);          pos = p4  // platformChatId → atla
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)
            if (rid != selfRuntimeId) {
                entities[rid] = TrackedEntity(rid, uniqueId, x, y, z, isPlayer = true)
                uniqueToRuntime[uniqueId] = rid
            }
        } catch (e: Exception) {
            Log.w("EntityTracker", "AddPlayer parse hatası: ${e.message}")
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
            // REMOVE_ENTITY → uniqueEntityId (zigzag signed varlong)
            // runtimeId GELMİYOR — uniqueToRuntime map'i şart
            val (uniqueId, _) = PacketHelper.readZigzagVarLong(d, p0)
            val runtimeId = uniqueToRuntime.remove(uniqueId)
            if (runtimeId != null) {
                entities.remove(runtimeId)
            }
        } catch (_: Exception) {}
    }

    private fun parseAuthInput(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0
            val pitch = PacketHelper.readFloatLE(d, pos); pos += 4
            val yaw   = PacketHelper.readFloatLE(d, pos); pos += 4
            val x     = PacketHelper.readFloatLE(d, pos); pos += 4
            val y     = PacketHelper.readFloatLE(d, pos); pos += 4
            val z     = PacketHelper.readFloatLE(d, pos)
            selfX = x; selfY = y; selfZ = z
            selfYaw = yaw; selfPitch = pitch
        } catch (_: Exception) {}
    }
}
