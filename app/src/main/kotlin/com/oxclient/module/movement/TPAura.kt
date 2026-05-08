package com.oxclient.module.movement

import android.util.Log
import com.oxclient.events.PacketDirection
import com.oxclient.module.BaseModule
import com.oxclient.proxy.BedrockPacketIds
import com.oxclient.proxy.PacketProcessor
import io.netty.buffer.Unpooled
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlin.random.Random

/**
 * TPAura - Modlu versiyon
 *
 * Random : Hedefin etrafında rastgele konuma ışınlan
 * Strafe : Hedefin etrafında dairesel hareket
 * Behind : Hedefin arkasına ışınlan
 */
class TPAura : BaseModule(
    name        = "TPAura",
    description = "Düşmana ışınlanarak saldırır",
    category    = Category.MOVEMENT
) {
    // ── Mod ───────────────────────────────────────────────────────────────
    enum class TPMode { RANDOM, STRAFE, BEHIND }
    var mode: TPMode = TPMode.RANDOM

    // ── Ayarlar ───────────────────────────────────────────────────────────
    var range            : Float = 1.50f   // Işınlanma mesafesi
    var yOffset          : Float = 0.00f   // Y ekseni ofseti
    var horizontalSpeed  : Float = 6.11f   // Yatay hız çarpanı
    var verticalSpeed    : Float = 4.00f   // Dikey hız çarpanı
    var strafeSpeed      : Float = 2.0f    // Strafe dönüş hızı
    var passive          : Boolean = false // Pasif mod (saldırma)
    var shortcut         : String = ""     // Kısayol

    // ── İç durum ──────────────────────────────────────────────────────────
    private data class TrackedEntity(
        val runtimeId: Long,
        var x: Float, var y: Float, var z: Float,
        var yaw: Float = 0f,
        val isPlayer: Boolean
    )

    private val entities      = ConcurrentHashMap<Long, TrackedEntity>()
    private var playerRuntimeId = 0L
    private var px = 0f; private var py = 0f; private var pz = 0f
    private var pyaw = 0f; private var ppitch = 0f
    private var headYaw = 0f
    @Volatile private var tpInProgress = false
    private var strafeAngle = 0f

    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob  : Job? = null
    private var attackJob: Job? = null

    override fun onEnable() {
        entities.clear()
        tpInProgress = false
        strafeAngle = 0f
        subscribePackets()
        tickJob   = scope.launch { tickLoop() }
        attackJob = scope.launch { attackLoop() }
        Log.d("TPAura", "Etkinleştirildi (mode=$mode, range=$range)")
    }

    override fun onDisable() {
        tickJob?.cancel()
        attackJob?.cancel()
        entities.clear()
        tpInProgress = false
    }

    // ── PAKET DİNLEME ────────────────────────────────────────────────────

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
                buf.readByte(); val rid = readVarLong(buf)
                buf.readByte() // flags
                val x = buf.readFloatLE(); val y = buf.readFloatLE(); val z = buf.readFloatLE()
                entities[rid]?.let { it.x = x; it.y = y; it.z = z }
            } catch (_: Exception) {} finally { buf.release() }
        }

        subscribe(packetId = BedrockPacketIds.MOVE_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte(); val rid = readVarLong(buf)
                val x = buf.readFloatLE(); val y = buf.readFloatLE(); val z = buf.readFloatLE()
                val pitch = buf.readFloatLE(); val yaw = buf.readFloatLE(); val hYaw = buf.readFloatLE()
                if (rid == playerRuntimeId) {
                    px = x; py = y; pz = z; ppitch = pitch; pyaw = yaw; headYaw = hYaw
                } else {
                    entities[rid]?.let { it.x = x; it.y = y; it.z = z; it.yaw = yaw }
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
                ppitch = buf.readFloatLE(); pyaw = buf.readFloatLE(); headYaw = buf.readFloatLE()
            } catch (_: Exception) {} finally { buf.release() }
        }
    }

    // ── ANA DÖNGÜLER ─────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (scope.isActive) {
            if (enabled && !tpInProgress && mode == TPMode.STRAFE) {
                strafeAngle += strafeSpeed * 0.05f
                if (strafeAngle > 360f) strafeAngle -= 360f
            }
            delay(50L)
        }
    }

    private suspend fun attackLoop() {
        while (scope.isActive) {
            delay(200L) // Sabit saldırı hızı
            if (enabled && !tpInProgress) {
                val target = selectTarget() ?: continue
                executeTPAttack(target)
            }
        }
    }

    private suspend fun executeTPAttack(target: TrackedEntity) {
        tpInProgress = true
        val origX = px; val origY = py; val origZ = pz

        // Hedef konumu hesapla
        val (tpX, tpY, tpZ) = calculateTPPosition(target)

        // 1. Hedefe ışınlan
        val tp = PacketProcessor.buildMovePlayerPacket(
            entityRuntimeId = playerRuntimeId,
            x = tpX, y = tpY + yOffset, z = tpZ,
            yaw = pyaw, pitch = ppitch, headYaw = headYaw,
            onGround = true, teleport = true
        )
        PacketProcessor.injectToServer(tp)

        delay(30L)

        // 2. Saldırı
        if (!passive) {
            val anim = PacketProcessor.buildAnimatePacket(4, playerRuntimeId)
            PacketProcessor.injectToServer(anim)

            val attack = PacketProcessor.buildAttackPacket(target.runtimeId)
            PacketProcessor.injectToServer(attack)
        }

        delay(50L)

        // 3. Geri dön
        val back = PacketProcessor.buildMovePlayerPacket(
            entityRuntimeId = playerRuntimeId,
            x = origX, y = origY, z = origZ,
            yaw = pyaw, pitch = ppitch, headYaw = headYaw,
            onGround = true, teleport = true
        )
        PacketProcessor.injectToServer(back)

        tpInProgress = false
    }

    private fun calculateTPPosition(target: TrackedEntity): Triple<Float, Float, Float> {
        return when (mode) {
            TPMode.RANDOM -> {
                val angle = Random.nextFloat() * 360f
                val rad = Math.toRadians(angle.toDouble())
                val x = target.x + (cos(rad) * range).toFloat()
                val z = target.z + (sin(rad) * range).toFloat()
                Triple(x, target.y, z)
            }
            TPMode.STRAFE -> {
                val rad = Math.toRadians(strafeAngle.toDouble())
                val x = target.x + (cos(rad) * range * horizontalSpeed).toFloat()
                val z = target.z + (sin(rad) * range * horizontalSpeed).toFloat()
                Triple(x, target.y + verticalSpeed * 0.1f, z)
            }
            TPMode.BEHIND -> {
                // Hedefin arkası = hedefin baktığı yönün tersi
                val targetRad = Math.toRadians(target.yaw.toDouble())
                val x = target.x - (cos(targetRad) * range).toFloat()
                val z = target.z - (sin(targetRad) * range).toFloat()
                Triple(x, target.y, z)
            }
        }
    }

    private fun selectTarget(): TrackedEntity? {
        return entities.values
            .filter { e ->
                e.runtimeId != playerRuntimeId && distance(e.x, e.y, e.z) <= range * 3f
            }
            .minByOrNull { distance(it.x, it.y, it.z) }
    }

    private fun distance(x: Float, y: Float, z: Float): Float {
        val dx = x - px; val dy = y - py; val dz = z - pz
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    // ── PARSE ────────────────────────────────────────────────────────────

    private fun parseAdd(data: ByteArray, isPlayer: Boolean) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte()
            readVarLong(buf)
            val rid = readVarLong(buf)
            if (!isPlayer) readString(buf)
            val x = buf.readFloatLE(); val y = buf.readFloatLE(); val z = buf.readFloatLE()
            entities[rid] = TrackedEntity(rid, x, y, z, isPlayer = isPlayer)
        } catch (_: Exception) {} finally { buf.release() }
    }

    private fun readVarLong(buf: io.netty.buffer.ByteBuf): Long {
        var r = 0L; var s = 0
        while (buf.isReadable) {
            val b = buf.readByte().toLong()
            r = r or ((b and 0x7F) shl s)
            if (b and 0x80L == 0L) break
            s += 7
        }
        return r
    }

    private fun readString(buf: io.netty.buffer.ByteBuf): String {
        var len = 0; var s = 0
        while (buf.isReadable) {
            val b = buf.readByte().toInt()
            len = len or ((b and 0x7F) shl s)
            if (b and 0x80 == 0) break
            s += 7
        }
        if (len <= 0 || len > buf.readableBytes()) return ""
        val bytes = ByteArray(len); buf.readBytes(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}