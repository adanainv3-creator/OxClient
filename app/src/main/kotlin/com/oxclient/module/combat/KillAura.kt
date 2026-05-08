package com.oxclient.module.combat

import android.util.Log
import com.oxclient.events.PacketDirection
import com.oxclient.module.BaseModule
import com.oxclient.proxy.BedrockPacketIds
import com.oxclient.proxy.PacketProcessor
import io.netty.buffer.Unpooled
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.random.Random

/**
 * KillAura - Tam ayarlı versiyon
 *
 * CPS: Min-Max arası rastgele saldırı hızı
 * Range: Saldırı menzili
 * FOV: Görüş açısı filtresi
 * AttackMode: Single / Multi / Switch
 * RotationMode: Lock / Approximate / None
 * Swing: Client / Server / Both / None
 * Priority: Distance / Health / Direction
 */
class KillAura : BaseModule(
    name        = "KillAura",
    description = "Otomatik saldırı sistemi",
    category    = Category.COMBAT
) {
    // ── CPS Ayarları ─────────────────────────────────────────────────────
    var cpsMin       : Float = 19.0f       // Minimum CPS
    var cpsMax       : Float = 20.0f       // Maximum CPS
    var range        : Float = 3.70f       // Saldırı menzili
    var fov          : Float = 180.0f      // Görüş açısı (derece)
    var switchDelayMS: Long  = 50L         // Hedef değiştirme gecikmesi (ms)

    // ── Saldırı Modu ─────────────────────────────────────────────────────
    enum class AttackMode { SINGLE, MULTI, SWITCH }
    var attackMode: AttackMode = AttackMode.SINGLE

    // ── Rotasyon Modu ────────────────────────────────────────────────────
    enum class RotationMode { LOCK, APPROXIMATE, NONE }
    var rotationMode: RotationMode = RotationMode.LOCK

    // ── Swing Modu ───────────────────────────────────────────────────────
    enum class SwingMode { CLIENT, SERVER, BOTH, NONE }
    var swingMode: SwingMode = SwingMode.BOTH

    // ── Öncelik Modu ─────────────────────────────────────────────────────
    enum class PriorityMode { DISTANCE, HEALTH, DIRECTION }
    var priorityMode: PriorityMode = PriorityMode.DISTANCE
    var reversePriority: Boolean = false    // Ters öncelik

    // ── Ek Ayarlar ───────────────────────────────────────────────────────
    var mouseOver     : Boolean = true      // Sadece imleç üstündeyse saldır
    var swingSound    : Boolean = false     // Swing sesi
    var failRate      : Float   = 0.0f      // Başarısızlık oranı (%)
    var shortcut      : String  = ""        // Kısayol tuşu

    // ── İç Durum ─────────────────────────────────────────────────────────
    private data class TrackedEntity(
        val runtimeId  : Long,
        var x          : Float, var y: Float, var z: Float,
        var health     : Float = 20.0f,
        val isPlayer   : Boolean,
        var lastAttack : Long = 0L
    )

    private val entities = ConcurrentHashMap<Long, TrackedEntity>()
    private var playerX = 0f; private var playerY = 0f; private var playerZ = 0f
    private var playerYaw = 0f; private var playerPitch = 0f
    private var playerRuntimeId = 0L

    private var currentTarget: Long? = null
    private var lastSwitchTime = 0L

    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob  : Job? = null
    private var attackJob: Job? = null

    override fun onEnable() {
        entities.clear()
        currentTarget = null
        subscribePackets()
        tickJob   = scope.launch { tickLoop() }
        attackJob = scope.launch { attackLoop() }
        Log.d("KillAura", "Etkinleştirildi (CPS: $cpsMin-$cpsMax, Range: $range, Mode: $attackMode)")
    }

    override fun onDisable() {
        tickJob?.cancel()
        attackJob?.cancel()
        entities.clear()
        currentTarget = null
    }

    // ── PAKET DİNLEME ────────────────────────────────────────────────────

    private fun subscribePackets() {
        subscribe(packetId = BedrockPacketIds.START_GAME, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try { buf.readByte(); playerRuntimeId = readVarLong(buf) }
            catch (_: Exception) {} finally { buf.release() }
        }

        subscribe(packetId = BedrockPacketIds.ADD_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseAddEntity(event.data, isPlayer = true)
        }

        subscribe(packetId = BedrockPacketIds.ADD_ENTITY, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseAddEntity(event.data, isPlayer = false)
        }

        subscribe(packetId = BedrockPacketIds.MOVE_ENTITY_ABSOLUTE, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseMoveEntity(event.data)
        }

        subscribe(packetId = BedrockPacketIds.MOVE_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            parseMovePlayer(event.data)
        }

        subscribe(packetId = BedrockPacketIds.REMOVE_ENTITY, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try { buf.readByte(); entities.remove(readVarLong(buf)); currentTarget = null }
            catch (_: Exception) {} finally { buf.release() }
        }

        subscribe(packetId = BedrockPacketIds.PLAYER_AUTH_INPUT, direction = PacketDirection.SERVER_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                playerX    = buf.readFloatLE(); playerY = buf.readFloatLE(); playerZ = buf.readFloatLE()
                playerPitch= buf.readFloatLE(); playerYaw = buf.readFloatLE()
            } catch (_: Exception) {} finally { buf.release() }
        }

        // Entity Update Attributes (can bilgisi için)
        subscribe(packetId = BedrockPacketIds.UPDATE_ATTRIBUTES, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                val rid = readVarLong(buf)
                val attrCount = buf.readUnsignedByte().toInt()
                for (i in 0 until attrCount) {
                    readString(buf)      // attribute name
                    val current = buf.readFloatLE()
                    buf.readFloatLE()    // max
                    buf.readFloatLE()    // min
                    buf.readFloatLE()    // default
                    entities[rid]?.health = current
                }
            } catch (_: Exception) {} finally { buf.release() }
        }
    }

    // ── ANA DÖNGÜLER ─────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (scope.isActive) {
            if (enabled && attackMode == AttackMode.SWITCH) {
                val now = System.currentTimeMillis()
                if (now - lastSwitchTime >= switchDelayMS) {
                    currentTarget = selectTarget()?.runtimeId
                    lastSwitchTime = now
                }
            }
            delay(50L)
        }
    }

    private suspend fun attackLoop() {
        while (scope.isActive) {
            if (enabled) {
                val delay = getAttackDelay()
                delay(delay)
                if (enabled) executeAttack()
            } else {
                delay(50L)
            }
        }
    }

    private fun getAttackDelay(): Long {
        val cps = Random.nextDouble(cpsMin.toDouble(), cpsMax.toDouble())
        return (1000.0 / cps).toLong()
    }

    private fun executeAttack() {
        val target = when (attackMode) {
            AttackMode.SINGLE -> {
                if (currentTarget == null || entities[currentTarget] == null) {
                    currentTarget = selectTarget()?.runtimeId
                }
                entities[currentTarget]
            }
            AttackMode.MULTI -> selectTarget()
            AttackMode.SWITCH -> {
                if (currentTarget == null || entities[currentTarget] == null) {
                    currentTarget = selectTarget()?.runtimeId
                }
                entities[currentTarget]
            }
        } ?: return

        // Başarısızlık kontrolü
        if (failRate > 0f && Random.nextFloat() < failRate / 100f) return

        // Rotasyon düzeltmesi
        if (rotationMode != RotationMode.NONE) {
            applyRotation(target)
        }

        // Swing animasyonu
        when (swingMode) {
            SwingMode.CLIENT, SwingMode.BOTH -> {
                val anim = PacketProcessor.buildAnimatePacket(4, playerRuntimeId)
                PacketProcessor.injectToServer(anim)
            }
            SwingMode.SERVER -> { /* Server-side swing yok, sadece attack */ }
            SwingMode.NONE -> { /* Hiçbir şey yapma */ }
        }

        // Saldırı
        val attack = PacketProcessor.buildAttackPacket(target.runtimeId)
        PacketProcessor.injectToServer(attack)
        target.lastAttack = System.currentTimeMillis()
    }

    private fun applyRotation(target: TrackedEntity) {
        val dx = target.x - playerX
        val dz = target.z - playerZ
        val yaw = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat() - 90f

        when (rotationMode) {
            RotationMode.LOCK -> {
                playerYaw = yaw
                playerPitch = 0f
            }
            RotationMode.APPROXIMATE -> {
                playerYaw += (yaw - playerYaw) * 0.3f
            }
            RotationMode.NONE -> {}
        }
    }

    private fun selectTarget(): TrackedEntity? {
        val candidates = entities.values.filter { e ->
            e.runtimeId != playerRuntimeId &&
            distance(e) <= range &&
            isInFOV(e)
        }

        if (candidates.isEmpty()) return null

        val sorted = when (priorityMode) {
            PriorityMode.DISTANCE  -> candidates.sortedBy { distance(it) }
            PriorityMode.HEALTH    -> candidates.sortedBy { it.health }
            PriorityMode.DIRECTION -> candidates.sortedBy { 
                val angle = calculateAngle(it)
                if (angle > 180f) 360f - angle else angle
            }
        }

        return if (reversePriority) sorted.lastOrNull() else sorted.firstOrNull()
    }

    private fun distance(e: TrackedEntity): Float {
        val dx = e.x - playerX; val dy = e.y - playerY; val dz = e.z - playerZ
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun isInFOV(e: TrackedEntity): Boolean {
        if (fov >= 360f) return true
        val angle = calculateAngle(e)
        return angle <= fov / 2f
    }

    private fun calculateAngle(e: TrackedEntity): Float {
        val dx = e.x - playerX
        val dz = e.z - playerZ
        val targetYaw = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat() - 90f
        var diff = Math.abs(targetYaw - playerYaw) % 360f
        if (diff > 180f) diff = 360f - diff
        return diff
    }

    // ── PARSE YARDIMCILARI ───────────────────────────────────────────────

    private fun parseAddEntity(data: ByteArray, isPlayer: Boolean) {
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

    private fun parseMoveEntity(data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte(); val rid = readVarLong(buf); buf.readByte()
            val x = buf.readFloatLE(); val y = buf.readFloatLE(); val z = buf.readFloatLE()
            entities[rid]?.let { it.x = x; it.y = y; it.z = z }
        } catch (_: Exception) {} finally { buf.release() }
    }

    private fun parseMovePlayer(data: ByteArray) {
        val buf = Unpooled.wrappedBuffer(data)
        try {
            buf.readByte(); val rid = readVarLong(buf)
            val x = buf.readFloatLE(); val y = buf.readFloatLE(); val z = buf.readFloatLE()
            if (rid == playerRuntimeId) {
                playerX = x; playerY = y; playerZ = z
            } else {
                entities[rid]?.let { it.x = x; it.y = y; it.z = z }
            }
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