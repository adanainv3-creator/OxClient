package com.oxclient.module.combat

import com.oxclient.events.PacketDirection
import com.oxclient.module.BaseModule
import com.oxclient.proxy.BedrockPacketIds
import com.oxclient.proxy.PacketProcessor
import io.netty.buffer.Unpooled

/**
 * Criticals
 *
 * Her saldırıdan önce hafif bir y-offset hareketi göndererek
 * kritik vuruş koşulunu sağlar (havadayken vurma = 1.5x hasar).
 *
 * Bedrock'ta kritik: oyuncu yere basmıyorken ve saldırırken → 1.5x çarpan.
 * Yöntem: PlayerAuthInput veya MovePlayer'ı intercept edip
 * kısa süreliğine onGround=false + küçük y-delta ile gönder.
 */
class Criticals : BaseModule(
    name        = "Criticals",
    description = "Saldırıları kritik vuruşa dönüştürür",
    category    = Category.COMBAT
) {
    // ── Ayarlar ───────────────────────────────────────────────────────────
    var mode: Mode = Mode.PACKET

    enum class Mode {
        PACKET,     // MovePlayer paketini manipüle et
        JUMP        // MicroJump: hafif y offset enjekte et
    }

    // ── İç durum ──────────────────────────────────────────────────────────
    @Volatile private var lastAttackTime = 0L
    @Volatile private var critPending    = false

    private var playerRuntimeId = 0L
    private var px = 0f; private var py = 0f; private var pz = 0f
    private var pyaw = 0f; private var ppitch = 0f

    override fun onEnable() {
        subscribePackets()
    }

    private fun subscribePackets() {
        // Kendi runtimeId ve konumumuzu izle
        subscribe(packetId = BedrockPacketIds.START_GAME, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                playerRuntimeId = readVarLong(buf)
            } catch (_: Exception) {}
            finally { buf.release() }
        }

        // MovePlayer (client-bound) → kendi pozisyonumuzu güncelle
        subscribe(packetId = BedrockPacketIds.MOVE_PLAYER, direction = PacketDirection.CLIENT_BOUND) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                val rid = readVarLong(buf)
                if (rid == playerRuntimeId) {
                    px = buf.readFloatLE(); py = buf.readFloatLE(); pz = buf.readFloatLE()
                    ppitch = buf.readFloatLE(); pyaw = buf.readFloatLE()
                }
            } catch (_: Exception) {}
            finally { buf.release() }
        }

        // InventoryTransaction (attack) yakalanınca kritik paketi enjekte et
        subscribe(packetId = BedrockPacketIds.INVENTORY_TRANSACTION, direction = PacketDirection.SERVER_BOUND, priority = 50) { event ->
            val buf = Unpooled.wrappedBuffer(event.data)
            try {
                buf.readByte()
                readVarInt(buf)  // legacyRequestId
                val txType = readVarInt(buf)
                if (txType == 2) {  // USE_ITEM_ON_ENTITY
                    injectCritical()
                }
            } catch (_: Exception) {}
            finally { buf.release() }
        }
    }

    private fun injectCritical() {
        if (playerRuntimeId == 0L) return
        val now = System.currentTimeMillis()
        if (now - lastAttackTime < 100) return
        lastAttackTime = now

        when (mode) {
            Mode.PACKET -> injectPacketCritical()
            Mode.JUMP   -> injectJumpCritical()
        }
    }

    /**
     * Packet modu: onGround=false ile MovePlayer gönder
     * Sonraki tick'te onGround=true ile geri dön.
     */
    private fun injectPacketCritical() {
        // Adım 1: Havada göster (onGround = false)
        val moveUp = PacketProcessor.buildMovePlayerPacket(
            entityRuntimeId = playerRuntimeId,
            x = px, y = py + 0.11f, z = pz,
            yaw = pyaw, pitch = ppitch, headYaw = pyaw,
            onGround = false, teleport = false
        )
        PacketProcessor.injectToServer(moveUp)

        // Adım 2: Yere dön (onGround = true)
        val moveDown = PacketProcessor.buildMovePlayerPacket(
            entityRuntimeId = playerRuntimeId,
            x = px, y = py, z = pz,
            yaw = pyaw, pitch = ppitch, headYaw = pyaw,
            onGround = true, teleport = false
        )
        PacketProcessor.injectToServer(moveDown)
    }

    /**
     * Jump modu: küçük y-delta + animate swing paketi
     */
    private fun injectJumpCritical() {
        val offsets = listOf(0.0625f, 0f, 0.0625f, 0f)
        offsets.forEach { dy ->
            val move = PacketProcessor.buildMovePlayerPacket(
                entityRuntimeId = playerRuntimeId,
                x = px, y = py + dy, z = pz,
                yaw = pyaw, pitch = ppitch, headYaw = pyaw,
                onGround = dy == 0f, teleport = false
            )
            PacketProcessor.injectToServer(move)
        }
    }

    // ── VarInt Yardımcıları ───────────────────────────────────────────────

    private fun readVarLong(buf: io.netty.buffer.ByteBuf): Long {
        var result = 0L; var shift = 0
        while (buf.isReadable) {
            val b = buf.readByte().toLong()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80L == 0L) break
            shift += 7
        }
        return result
    }

    private fun readVarInt(buf: io.netty.buffer.ByteBuf): Int {
        var result = 0; var shift = 0
        while (buf.isReadable) {
            val b = buf.readByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }
}
