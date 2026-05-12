package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap

object EntityTracker : PacketEventBus.PacketListener {

    private const val TAG = "EntityTracker"
    override val priority = 10

    data class TrackedEntity(
        val runtimeId : Long,
        val uniqueId  : Long,
        var x: Float, var y: Float, var z: Float,
        var health    : Float = 20f,
        val isPlayer  : Boolean = false
    )

    private val entities        = ConcurrentHashMap<Long, TrackedEntity>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()

    @Volatile var selfRuntimeId: Long = 0L
    @Volatile var selfUniqueId : Long = 0L
    @Volatile var selfX  : Float = 0f
    @Volatile var selfY  : Float = 0f
    @Volatile var selfZ  : Float = 0f
    @Volatile var selfYaw  : Float = 0f
    @Volatile var selfPitch: Float = 0f

    fun register() {
        PacketEventBus.register(this)
        Log.d(TAG, "EntityTracker kayıt oldu")
    }

    fun reset() {
        entities.clear(); uniqueToRuntime.clear()
        selfRuntimeId = 0L; selfUniqueId = 0L
        selfX = 0f; selfY = 0f; selfZ = 0f; selfYaw = 0f; selfPitch = 0f
    }

    override fun onPacket(event: PacketEvent) {
        when (val pkt = event.packet) {
            is StartGamePacket          -> onStartGame(pkt)
            is AddPlayerPacket          -> onAddPlayer(pkt)
            is AddEntityPacket          -> onAddEntity(pkt)
            is RemoveEntityPacket       -> onRemoveEntity(pkt)
            is MovePlayerPacket         -> onMovePlayer(pkt)
            is MoveEntityAbsolutePacket -> onMoveEntity(pkt)
            is PlayerAuthInputPacket    -> onAuthInput(pkt)
            is UpdateAttributesPacket   -> onUpdateAttributes(pkt)
            else -> {}
        }
    }

    private fun onStartGame(pkt: StartGamePacket) {
        selfRuntimeId = pkt.runtimeEntityId
        selfUniqueId  = pkt.uniqueEntityId
        selfX = pkt.playerPosition.x
        selfY = pkt.playerPosition.y
        selfZ = pkt.playerPosition.z
        entities.clear(); uniqueToRuntime.clear()
        Log.i(TAG, "StartGame → selfRuntimeId=$selfRuntimeId uid=$selfUniqueId")
    }

    private fun onAddPlayer(pkt: AddPlayerPacket) {
        if (pkt.runtimeEntityId == selfRuntimeId) return
        val x = pkt.position.x; val y = pkt.position.y; val z = pkt.position.z
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() ||
            Math.abs(x) > 3e7f || Math.abs(y) > 4096f || Math.abs(z) > 3e7f) {
            Log.w(TAG, "AddPlayer geçersiz koordinat rid=${pkt.runtimeEntityId}")
            return
        }
        entities[pkt.runtimeEntityId] = TrackedEntity(
            pkt.runtimeEntityId, pkt.uniqueEntityId, x, y, z, isPlayer = true
        )
        uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
        Log.d(TAG, "AddPlayer rid=${pkt.runtimeEntityId} x=%.1f y=%.1f z=%.1f".format(x, y, z))
    }

    private fun onAddEntity(pkt: AddEntityPacket) {
        if (pkt.runtimeEntityId == selfRuntimeId) return
        entities[pkt.runtimeEntityId] = TrackedEntity(
            pkt.runtimeEntityId, pkt.uniqueEntityId,
            pkt.position.x, pkt.position.y, pkt.position.z
        )
        uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
    }

    private fun onRemoveEntity(pkt: RemoveEntityPacket) {
        val rid = uniqueToRuntime.remove(pkt.uniquEntityId)
        if (rid != null) entities.remove(rid)
    }

    private fun onMovePlayer(pkt: MovePlayerPacket) {
        if (pkt.runtimeEntityId == selfRuntimeId) {
            selfX = pkt.position.x; selfY = pkt.position.y; selfZ = pkt.position.z
            selfYaw = pkt.rotation.y; selfPitch = pkt.rotation.x
        } else {
            entities[pkt.runtimeEntityId]?.apply {
                x = pkt.position.x; y = pkt.position.y; z = pkt.position.z
            }
        }
    }

    private fun onMoveEntity(pkt: MoveEntityAbsolutePacket) {
        entities[pkt.runtimeEntityId]?.apply {
            x = pkt.position.x; y = pkt.position.y; z = pkt.position.z
        }
    }

    private fun onAuthInput(pkt: PlayerAuthInputPacket) {
        selfX = pkt.position.x; selfY = pkt.position.y; selfZ = pkt.position.z
        selfYaw = pkt.rotation.y; selfPitch = pkt.rotation.x
    }

    private fun onUpdateAttributes(pkt: UpdateAttributesPacket) {
        if (pkt.runtimeEntityId != selfRuntimeId) return
        pkt.attributes.firstOrNull { it.name == "minecraft:health" }
            ?.let { entities[selfRuntimeId]?.health = it.value }
    }

    fun getEntities(): Map<Long, TrackedEntity> = entities

    fun getEntitiesInRange(range: Float): List<TrackedEntity> =
        entities.values.filter { distanceTo(it) <= range }

    fun distanceTo(e: TrackedEntity): Float {
        val dx = e.x - selfX; val dy = e.y - selfY; val dz = e.z - selfZ
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    fun angleToEntity(e: TrackedEntity): Float {
        val dx = e.x - selfX; val dz = e.z - selfZ
        val yaw = Math.toDegrees(Math.atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        var diff = ((yaw - selfYaw) % 360 + 360) % 360
        if (diff > 180) diff -= 360
        return Math.abs(diff)
    }
}
