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
            // Paket ID'yi varint olarak atla (zaten parseStartGame çağrıldığında
            // d[0] paket ID baytı, ama paket ID varint olduğundan readVarInt ile atlanmalı)
            val (_, p1) = PacketHelper.readVarInt(d, 0); var pos = p1

            // uniqueEntityId (zigzag encoded varlong) → atla
            val (_, p2) = PacketHelper.readVarInt(d, pos); pos = p2

            // runtimeEntityId (varlong) → selfRuntimeId buradan okunur
            val (rid, _) = PacketHelper.readVarLong(d, pos)
            selfRuntimeId = rid
            Log.i("EntityTracker", "selfRuntimeId = $rid")
        } catch (e: Exception) {
            Log.w("EntityTracker", "StartGame parse hatası: ${e.message}")
        }
    }

    // ✅ FIX: Tüm aşağıdaki parse fonksiyonlarında "pos = 1" vardı.
    //         Paket ID'si varint formatında olduğu için 1 byte OLMAYABILIR.
    //         0x0B (START_GAME) varint olarak 1 byte, 0x90 (PLAYER_AUTH_INPUT)
    //         varint olarak 2 byte sürer. pos=1 ile sabit atlamak offset'i
    //         kaydırır → runtimeId yanlış okunur → entity tracker çalışmaz
    //         → KillAura, CrystalAura hedef bulamaz.
    //         Çözüm: her fonksiyon readVarInt(d, 0) ile gerçek varint boyutu
    //         kadar atlar.

    private fun parseAddPlayer(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0  // ✅ FIX
            pos += 16    // UUID (16 byte sabit)
            val (_, p2) = PacketHelper.readString(d, pos); pos = p2     // username
            val (rid, p3) = PacketHelper.readVarLong(d, pos); pos = p3  // runtimeId
            val (_, p4) = PacketHelper.readVarLong(d, pos); pos = p4    // uniqueId
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)
            if (rid != selfRuntimeId)
                entities[rid] = TrackedEntity(rid, x, y, z, isPlayer = true)
        } catch (_: Exception) {}
    }

    private fun parseAddEntity(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0   // ✅ FIX
            val (_, p1) = PacketHelper.readVarLong(d, pos); pos = p1    // uniqueId
            val (rid, p2) = PacketHelper.readVarLong(d, pos); pos = p2  // runtimeId
            val (_, p3) = PacketHelper.readString(d, pos); pos = p3     // entity type string
            val x = PacketHelper.readFloatLE(d, pos); pos += 4
            val y = PacketHelper.readFloatLE(d, pos); pos += 4
            val z = PacketHelper.readFloatLE(d, pos)
            entities[rid] = TrackedEntity(rid, x, y, z, isPlayer = false)
        } catch (_: Exception) {}
    }

    private fun parseMovePlayer(d: ByteArray) {
        try {
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0   // ✅ FIX
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
            val (_, p0) = PacketHelper.readVarInt(d, 0); var pos = p0   // ✅ FIX
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
            val (_, p0) = PacketHelper.readVarInt(d, 0)                 // ✅ FIX
            val (rid, _) = PacketHelper.readVarLong(d, p0)
            entities.remove(rid)
        } catch (_: Exception) {}
    }

    private fun parseAuthInput(d: ByteArray) {
        try {
            // ✅ FIX: PLAYER_AUTH_INPUT = 0x90 → varint olarak 2 byte sürer (0x90 0x01)
            //         pos=1 ile atlamak 1 byte eksik atlıyor, tüm offset'ler kayıyor.
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
