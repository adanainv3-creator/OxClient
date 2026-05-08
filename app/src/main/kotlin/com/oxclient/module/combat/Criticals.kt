package com.oxclient.module.combat

import android.util.Log
import com.oxclient.events.PacketDirection
import com.oxclient.module.BaseModule
import com.oxclient.proxy.BedrockPacketIds
import com.oxclient.proxy.PacketProcessor
import io.netty.buffer.Unpooled
import kotlinx.coroutines.*

/**
 * Criticals - Çoklu mod desteği
 *
 * Vanilla   : onGround=false ile kritik
 * Jump      : Küçük y-offset enjekte et
 * TPJump    : Hedefe ışınla + kritik
 * MovePacket: MovePlayer manipülasyonu
 */
class Criticals : BaseModule(
    name        = "Criticals",
    description = "Tüm saldırıları kritik vuruşa dönüştürür",
    category    = Category.COMBAT
) {
    // ── Modlar ────────────────────────────────────────────────────────────
    enum class CriticalMode { VANILLA, JUMP, TPJUMP, MOVEPACKET }
    var mode: CriticalMode = CriticalMode.VANILLA

    // ── İç durum ──────────────────────────────────────────────────────────
    private var playerRuntimeId = 0L
    private var px = 0f; private var py = 0f; private var pz = 0f
    private var pyaw = 0f; private var ppitch = 0f
    private var headYaw = 0f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onEnable() {
        subscribePackets()
        Log.d("Criticals", "Etkinleştirildi (mode=$mode)")
    }

    override fun onDisable() {
        // Listener'lar BaseModule tarafından temizlenir
    }

    private fun subscribePackets() {
        // START_GAME - runtimeId
        subscribe(packetId = BedrockPacketIds.START_GAME, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try { 
                buf.readByte()
                playerRuntimeId = readVarLong(buf)
            } catch (_: Exception) {} 
            finally { buf.release() }
        }

        // MOVE_PLAYER - konum güncelle
        subscribe(packetId = BedrockPacketIds.MOVE_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                val rid = readVarLong(buf)
                if (rid == playerRuntimeId) {
                    px = buf.readFloatLE()
                    py = buf.readFloatLE()
                    pz = buf.readFloatLE()
                    ppitch = buf.readFloatLE()
                    pyaw = buf.readFloatLE()
                    headYaw = buf.readFloatLE()
                }
            } catch (_: Exception) {}
            finally { buf.release() }
        }

        // PLAYER_AUTH_INPUT - güncel konum (server-bound)
        subscribe(packetId = BedrockPacketIds.PLAYER_AUTH_INPUT, direction = PacketDirection.SERVER_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                px = buf.readFloatLE()
                py = buf.readFloatLE()
                pz = buf.readFloatLE()
                ppitch = buf.readFloatLE()
                pyaw = buf.readFloatLE()
                headYaw = buf.readFloatLE()
            } catch (_: Exception) {}
            finally { buf.release() }
        }

        // INVENTORY_TRANSACTION - saldırı tespiti
        subscribe(packetId = BedrockPacketIds.INVENTORY_TRANSACTION, direction = PacketDirection.SERVER_BOUND, priority = 50) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                readVarInt(buf)  // legacyRequestId
                val txType = readVarInt(buf)
                if (txType == 2) {  // USE_ITEM_ON_ENTITY = attack
                    executeCritical()
                }
            } catch (_: Exception) {}
            finally { buf.release() }
        }
    }

    private fun executeCritical() {
        if (playerRuntimeId == 0L) return

        when (mode) {
            CriticalMode.VANILLA    -> vanillaCritical()
            CriticalMode.JUMP       -> jumpCritical()
            CriticalMode.TPJUMP     -> tpJumpCritical()
            CriticalMode.MOVEPACKET -> movePacketCritical()
        }
    }

    /**
     * Vanilla: onGround = false ile MovePlayer gönder
     */
    private fun vanillaCritical() {
        // Havada
        val up = PacketProcessor.buildMovePlayerPacket(
            entityRuntimeId = playerRuntimeId,
            x = px, y = py + 0.0625f, z = pz,
            yaw = pyaw, pitch = ppitch, headYaw = headYaw,
            onGround = false, teleport = false
        )
        PacketProcessor.injectToServer(up)

        // Yerde
        val down = PacketProcessor.buildMovePlayerPacket(
            entityRuntimeId = playerRuntimeId,
            x = px, y = py, z = pz,
            yaw = pyaw, pitch = ppitch, headYaw = headYaw,
            onGround = true, teleport = false
        )
        PacketProcessor.injectToServer(down)
    }

    /**
     * Jump: Küçük y-delta ofsetleri
     */
    private fun jumpCritical() {
        val offsets = listOf(0.42f, 0.33f, 0.24f, 0.08f, 0f)
        offsets.forEach { dy ->
            val move = PacketProcessor.buildMovePlayerPacket(
                entityRuntimeId = playerRuntimeId,
                x = px, y = py + dy, z = pz,
                yaw = pyaw, pitch = ppitch, headYaw = headYaw,
                onGround = dy == 0f, teleport = false
            )
            PacketProcessor.injectToServer(move)
        }
    }

    /**
     * TPJump: Yukarı ışınlan + kritik + geri dön
     */
    private fun tpJumpCritical() {
        val tpUp = PacketProcessor.buildMovePlayerPacket(
            entityRuntimeId = playerRuntimeId,
            x = px, y = py + 0.5f, z = pz,
            yaw = pyaw, pitch = ppitch, headYaw = headYaw,
            onGround = false, teleport = true
        )
        PacketProcessor.injectToServer(tpUp)

        // Kritik vuruş için onGround=false zaten yukarıdaki pakette
        // Geri dön
        scope.launch {
            delay(100)
            val tpDown = PacketProcessor.buildMovePlayerPacket(
                entityRuntimeId = playerRuntimeId,
                x = px, y = py, z = pz,
                yaw = pyaw, pitch = ppitch, headYaw = headYaw,
                onGround = true, teleport = true
            )
            PacketProcessor.injectToServer(tpDown)
        }
    }

    /**
     * MovePacket: onGround=false flag ile MovePlayer gönder
     */
    private fun movePacketCritical() {
        // Saldırı anında onGround=false
        val movePacket = PacketProcessor.buildMovePlayerPacket(
            entityRuntimeId = playerRuntimeId,
            x = px, y = py + 0.001f, z = pz,
            yaw = pyaw, pitch = ppitch, headYaw = headYaw,
            onGround = false, teleport = false
        )
        PacketProcessor.injectToServer(movePacket)
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────

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

    private fun readVarInt(buf: io.netty.buffer.ByteBuf): Int {
        var r = 0; var s = 0
        while (buf.isReadable) {
            val b = buf.readByte().toInt()
            r = r or ((b and 0x7F) shl s)
            if (b and 0x80 == 0) break
            s += 7
        }
        return r
    }
}