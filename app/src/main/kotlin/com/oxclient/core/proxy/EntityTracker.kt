package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import java.util.concurrent.ConcurrentHashMap

object EntityTracker : PacketListener {

    override val priority: Int = 10

    data class TrackedEntity(
        val runtimeId : Long,
        var x         : Float,
        var y         : Float,
        var z         : Float,
        val isPlayer  : Boolean,
        var health    : Float = 20f,
        var yaw       : Float = 0f
    )

    private val entities = ConcurrentHashMap<Long, TrackedEntity>()

    @Volatile var selfRuntimeId : Long  = 0L
    @Volatile var selfX         : Float = 0f
    @Volatile var selfY         : Float = 0f
    @Volatile var selfZ         : Float = 0f
    @Volatile var selfYaw       : Float = 0f
    @Volatile var selfPitch     : Float = 0f

    fun register()   { PacketEventBus.register(this) }
    fun unregister() { PacketEventBus.unregister(this); entities.clear() }

    fun getEntities(): Map<Long, TrackedEntity> = entities

    fun getEntitiesInRange(range: Float): List<TrackedEntity> {
        return entities.values.filter { e ->
            e.runtimeId != selfRuntimeId && distanceTo(e) <= range
        }
    }

    fun distanceTo(e: TrackedEntity): Float {
        val dx = e.x - selfX; val dy = e.y - selfY; val dz = e.z - selfZ
        return Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
    }

    fun angleToEntity(e: TrackedEntity): Float {
        val dx = e.x - selfX; val dz = e.z - selfZ
        val targetYaw = Math.toDegrees(Math.atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        var diff = Math.abs(targetYaw - selfYaw) % 360f
        if (diff > 180f) diff = 360f - diff
        return diff
    }

    override fun onPacket(event: PacketEvent) {
        try {
            when (event.packetId) {
                BedrockPacketIds.START_GAME            -> parseStartGame(event.data)
                BedrockPacketIds.ADD_PLAYER            -> parseAddPlayer(event.data)
                BedrockPacketIds.ADD_ENTITY            -> parseAddEntity(event.data)
                BedrockPacketIds.MOVE_PLAYER           -> parseMovePlayer(event.data)
                BedrockPacketIds.MOVE_ENTITY_ABSOLUTE  -> parseMoveEntity(event.data)
                BedrockPacketIds.REMOVE_ENTITY         -> parseRemoveEntity(event.data)
                BedrockPacketIds.PLAYER_AUTH_INPUT     -> parseAuthInput(event.data)
            }
        } catch (_: Exception) {}
    }

    private fun parseStartGame(d: ByteArray) {
        try {
            // Packet ID'yi varint olarak doğru şekilde atla
            val (_, posAfterPktId) = PacketHelper.readVarInt(d, 0)
            var pos = posAfterPktId

            // unique entity id (zigzag varint) — atla
            val (_, p1) = PacketHelper.readVarInt(d, pos); pos = p1

            // runtimeEntityId (varlong)
            val (rid, _) = PacketHelper.readVarLong(d, pos)
            selfRuntimeId = rid
            Log.i("EntityTracker", "selfRuntimeId = $rid")
        } catch (e: Exception) {
            Log.w("EntityTracker", "StartGame parse hatası: ${e.message}")
        }
    }

    private fun parseAddPlayer(d: ByteArray) {
        try {
            var pos = 1  // skip packetId
            pos += 16    // UUID
            val (_, p2) = PacketHelper.readString(d, pos); pos = p2
            val (rid, p3) = PacketHelper.readVarLong(d, pos); pos = p3
            val (_, p4) = PacketHelper.readVarLong(d, pos); pos = p4
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)
            if (rid != selfRuntimeId)
                entities[rid] = TrackedEntity(rid, x, y, z, isPlayer = true)
        } catch (_: Exception) {}
    }

    private fun parseAddEntity(d: ByteArray) {
        try {
            var pos = 1  // skip packetId
            val (_, p1) = PacketHelper.readVarLong(d, pos); pos = p1
            val (rid, p2) = PacketHelper.readVarLong(d, pos); pos = p2
            val (_, p3) = PacketHelper.readString(d, pos); pos = p3
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)
            entities[rid] = TrackedEntity(rid, x, y, z, isPlayer = false)
        } catch (_: Exception) {}
    }

    private fun parseMovePlayer(d: ByteArray) {
        try {
            var pos = 1
            val (rid, p1) = PacketHelper.readVarLong(d, pos); pos = p1
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos); pos += 4
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
            var pos = 1
            val (rid, p1) = PacketHelper.readVarLong(d, pos); pos = p1
            pos += 1  // flags
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)
            entities[rid]?.let { it.x = x; it.y = y; it.z = z }
        } catch (_: Exception) {}
    }

    private fun parseRemoveEntity(d: ByteArray) {
        try {
            val (rid, _) = PacketHelper.readVarLong(d, 1)
            entities.remove(rid)
        } catch (_: Exception) {}
    }

    private fun parseAuthInput(d: ByteArray) {
        try {
            var pos = 1
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
