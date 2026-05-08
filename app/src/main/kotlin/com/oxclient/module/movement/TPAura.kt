package com.oxclient.module.movement

import android.util.Log
import com.oxclient.events.PacketDirection
import com.oxclient.module.BaseModule
import com.oxclient.proxy.BedrockPacketIds
import com.oxclient.proxy.PacketProcessor
import io.netty.buffer.Unpooled
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * TPAura
 *
 * Her tick'te menzil içindeki entity'nin yanına ışınlanır,
 * saldırır ve geri döner.
 *
 * Mekanizma:
 *  1. Entity listesini KillAura ile aynı şekilde oluşturur.
 *  2. Hedef bulunca: player → hedef konumu MovePlayer (teleport=true)
 *  3. AttackPacket + AnimatePacket enjekte eder.
 *  4. Eski konuma geri döner: MovePlayer (geri).
 *
 * ⚠️  Yüksek TPAura değerleri sunucu tarafında ban sebebi olabilir.
 *     Gerçekçi gecikmeler kullanın.
 */
class TPAura : BaseModule(
    name        = "TPAura",
    description = "Düşmana ışınlanarak saldırır ve geri döner",
    category    = Category.MOVEMENT
) {
    // ── Ayarlar ───────────────────────────────────────────────────────────
    var range      : Float = 10.0f    // ışınlanma menzili (blok)
    var attackDelay: Long  = 200L     // ms — saldırı gecikmesi
    var returnDelay: Long  = 50L      // ms — geri dönme gecikmesi
    var onlyPlayers: Boolean = false

    // ── İç durum ──────────────────────────────────────────────────────────
    private data class Entity(
        val runtimeId: Long,
        var x: Float, var y: Float, var z: Float,
        val isPlayer: Boolean
    )

    private val entities      = ConcurrentHashMap<Long, Entity>()
    private var playerRuntimeId = 0L
    private var px = 0f; private var py = 0f; private var pz = 0f
    private var pyaw = 0f; private var ppitch = 0f
    @Volatile private var lastAttack = 0L
    @Volatile private var tpInProgress = false

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob : Job? = null

    override fun onEnable() {
        entities.clear()
        subscribePackets()
        tickJob = scope.launch { tickLoop() }
        Log.d("TPAura", "Etkinleştirildi (range=$range, delay=${attackDelay}ms)")
    }

    override fun onDisable() {
        tickJob?.cancel()
        entities.clear()
        tpInProgress = false
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PAKET DİNLEME
    // ─────────────────────────────────────────────────────────────────────

    private fun subscribePackets() {
        subscribe(packetId = BedrockPacketIds.START_GAME, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try { buf.readByte(); playerRuntimeId = readVarLong(buf) }
            catch (_: Exception) {} finally { buf.release() }
        }

        subscribe(packetId = BedrockPacketIds.ADD_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseAdd(event.data, true)
        }

        subscribe(packetId = BedrockPacketIds.ADD_ENTITY, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseAdd(event.data, false)
        }

        subscribe(packetId = BedrockPacketIds.MOVE_ENTITY_ABSOLUTE, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte(); val rid = readVarLong(buf); buf.readByte()
                val x = buf.readFloatLE(); val y = buf.readFloatLE(); val z = buf.readFloatLE()
                entities[rid]?.let { it.x = x; it.y = y; it.z = z }
            } catch (_: Exception) {} finally { buf.release() }
        }

        subscribe(packetId = BedrockPacketIds.MOVE_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte(); val rid = readVarLong(buf)
                val x = buf.readFloatLE(); val y = buf.readFloatLE(); val z = buf.readFloatLE()
                val pitch = buf.readFloatLE(); val yaw = buf.readFloatLE()
                if (rid == playerRuntimeId) {
                    px = x; py = y; pz = z; ppitch = pitch; pyaw = yaw
                } else {
                    entities[rid]?.let { it.x = x; it.y = y; it.z = z }
                }
            } catch (_: Exception) {} finally { buf.release() }
        }

        subscribe(packetId = BedrockPacketIds.REMOVE_ENTITY, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try { buf.readByte(); entities.remove(readVarLong(buf)) }
            catch (_: Exception) {} finally { buf.release() }
        }

        subscribe(packetId = BedrockPacketIds.PLAYER_AUTH_INPUT, direction = PacketDirection.SERVER_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                px = buf.readFloatLE(); py = buf.readFloatLE(); pz = buf.readFloatLE()
            } catch (_: Exception) {} finally { buf.release() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TICK
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (isActive) {
            val now = System.currentTimeMillis()
            if (enabled && !tpInProgress && now - lastAttack >= attackDelay) {
                val target = selectTarget()
                if (target != null) {
                    tpAttack(target, now)
                }
            }
            delay(50L)
        }
    }

    private suspend fun tpAttack(target: Entity, now: Long) {
        tpInProgress = true
        lastAttack   = now

        // Önceki konumu kaydet
        val origX = px; val origY = py; val origZ = pz

        // 1. Hedefe ışınlan (1 blok yakın)
        val dx = target.x - px; val dz = target.z - pz
        val dist = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
        val ratio = if (dist > 1f) (dist - 0.8f) / dist else 0f

        val tpX = px + dx * ratio
        val tpY = target.y
        val tpZ = pz + dz * ratio

        val teleportTo = PacketProcessor.buildMovePlayerPacket(
            playerRuntimeId, tpX, tpY, tpZ,
            pyaw, ppitch, pyaw,
            onGround = true, teleport = true
        )
        PacketProcessor.injectToServer(teleportTo)

        delay(30L)

        // 2. Swing + Attack
        val anim = PacketProcessor.buildAnimatePacket(4, playerRuntimeId)
        PacketProcessor.injectToServer(anim)

        val attack = PacketProcessor.buildAttackPacket(target.runtimeId)
        PacketProcessor.injectToServer(attack)

        Log.v("TPAura", "TP saldırı: entity ${target.runtimeId} @ %.1f,%.1f,%.1f".format(tpX, tpY, tpZ))

        delay(returnDelay)

        // 3. Geri dön
        val teleportBack = PacketProcessor.buildMovePlayerPacket(
            playerRuntimeId, origX, origY, origZ,
            pyaw, ppitch, pyaw,
            onGround = true, teleport = true
        )
        PacketProcessor.injectToServer(teleportBack)

        tpInProgress = false
    }

    private fun selectTarget(): Entity? {
        return entities.values
            .filter { e ->
                if (onlyPlayers && !e.isPlayer) return@filter false
                e.runtimeId != playerRuntimeId && distance(e) <= range
            }
            .minByOrNull { distance(it) }
    }

    private fun distance(e: Entity): Float {
        val dx = e.x - px; val dy = e.y - py; val dz = e.z - pz
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun parseAdd(data: ByteArray, isPlayer: Boolean) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte()
            readVarLong(buf)             // uniqueId
            val rid = readVarLong(buf)
            if (!isPlayer) readString(buf)
            val x = buf.readFloatLE(); val y = buf.readFloatLE(); val z = buf.readFloatLE()
            entities[rid] = Entity(rid, x, y, z, isPlayer)
        } catch (_: Exception) {} finally { buf.release() }
    }

    private fun readVarLong(buf: io.netty.buffer.ByteBuf): Long {
        var r = 0L; var s = 0
        while (buf.isReadable) { val b = buf.readByte().toLong(); r = r or ((b and 0x7F) shl s); if (b and 0x80L == 0L) break; s += 7 }
        return r
    }

    private fun readString(buf: io.netty.buffer.ByteBuf): String {
        var len = 0; var s = 0
        while (buf.isReadable) { val b = buf.readByte().toInt(); len = len or ((b and 0x7F) shl s); if (b and 0x80 == 0) break; s += 7 }
        if (len <= 0 || len > buf.readableBytes()) return ""
        val bytes = ByteArray(len); buf.readBytes(bytes); return String(bytes, Charsets.UTF_8)
    }
}
