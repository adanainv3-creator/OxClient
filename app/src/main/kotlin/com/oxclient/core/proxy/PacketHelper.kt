package com.oxclient.core.proxy

import java.io.ByteArrayOutputStream

/**
 * PacketHelper
 *
 * Tüm modüllerin ortak kullandığı Bedrock paket builder'ları.
 * PacketProcessor üzerinden modüllere enjeksiyon sağlar.
 *
 * Kullanım:
 *   PacketHelper.injectToServer(PacketHelper.buildMovePlayer(...))
 *   PacketHelper.injectToClient(PacketHelper.buildSetHealth(...))
 */
object PacketHelper {

    // ── Injection ─────────────────────────────────────────────────────────

    /**
     * Sunucuya sahte paket gönder (client → server yönü).
     * Paket MITMProxy'nin serverSocket'inden sunucuya iletilir.
     */
    fun injectToServer(data: ByteArray) {
        InjectionQueue.enqueueToServer(data)
    }

    /**
     * İstemciye sahte paket gönder (server → client yönü).
     * Paket MITMProxy'nin listenSocket'inden istemciye iletilir.
     */
    fun injectToClient(data: ByteArray) {
        InjectionQueue.enqueueToClient(data)
    }

    // ── MovePlayer ────────────────────────────────────────────────────────

    /**
     * MovePlayer paketi (0x13)
     *
     * @param runtimeId  Oyuncunun runtime entity ID'si
     * @param x, y, z   Konum
     * @param yaw        Yatay bakış açısı (derece)
     * @param pitch      Dikey bakış açısı (derece)
     * @param headYaw    Kafa yaw'ı (genelde yaw ile aynı)
     * @param onGround   Yerde mi?
     * @param teleport   Anlık ışınlanma mı?
     */
    fun buildMovePlayer(
        runtimeId : Long,
        x         : Float,
        y         : Float,
        z         : Float,
        yaw       : Float   = 0f,
        pitch     : Float   = 0f,
        headYaw   : Float   = 0f,
        onGround  : Boolean = true,
        teleport  : Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.MOVE_PLAYER)
        writeVarLong(out, runtimeId)
        writeFloatLE(out, x)
        writeFloatLE(out, y)
        writeFloatLE(out, z)
        writeFloatLE(out, pitch)
        writeFloatLE(out, yaw)
        writeFloatLE(out, headYaw)
        out.write(if (teleport) 1 else 0)  // mode: 0=normal, 1=teleport
        out.write(if (onGround) 1 else 0)
        writeVarLong(out, 0L) // ridingRuntimeId
        return wrapBatch(out.toByteArray())
    }

    // ── Animate (Swing) ───────────────────────────────────────────────────

    /**
     * Animate paketi (0x2C) — el sallaması / swing
     *
     * @param action  4 = swing arm, 128 = wake up
     */
    fun buildAnimate(runtimeId: Long, action: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.ANIMATE)
        writeVarInt(out, action)
        writeVarLong(out, runtimeId)
        return wrapBatch(out.toByteArray())
    }

    // ── Attack (InventoryTransaction USE_ITEM_ON_ENTITY) ──────────────────

    /**
     * Saldırı paketi (0x1E — InventoryTransaction, txType=2)
     *
     * @param targetRuntimeId  Hedef entity runtime ID
     * @param attackerRuntimeId Saldıran oyuncu runtime ID
     */
    fun buildAttack(targetRuntimeId: Long, attackerRuntimeId: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.INVENTORY_TRANSACTION)
        writeVarInt(out, 0)   // legacyRequestId
        writeVarInt(out, 0)   // legacySlots count
        writeVarInt(out, 2)   // txType = USE_ITEM_ON_ENTITY
        writeVarLong(out, targetRuntimeId)
        writeVarInt(out, 1)   // actionType = 1 = attack
        writeVarInt(out, 0)   // hotbarSlot
        writeItem(out)        // heldItem (air)
        writeVec3(out, 0f, 0f, 0f) // playerPos
        writeVec3(out, 0f, 0f, 0f) // clickPos
        return wrapBatch(out.toByteArray())
    }

    // ── InteractPacket ────────────────────────────────────────────────────

    /**
     * Interact paketi (0x21)
     * actionId: 1=interact, 2=attack, 3=leaveVehicle, 4=mouseover, 7=openInventory
     */
    fun buildInteract(targetRuntimeId: Long, actionId: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.INTERACT)
        out.write(actionId)
        writeVarLong(out, targetRuntimeId)
        writeVec3(out, 0f, 0f, 0f)
        return wrapBatch(out.toByteArray())
    }

    // ── SetHealth (client injection) ──────────────────────────────────────

    fun buildSetHealth(health: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.SET_HEALTH)
        writeVarInt(out, health)
        return wrapBatch(out.toByteArray())
    }

    // ── ContainerOpen / ContainerClose ────────────────────────────────────

    fun buildContainerClose(windowId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.CONTAINER_CLOSE)
        out.write(windowId)
        return wrapBatch(out.toByteArray())
    }

    // ── MobEffect ─────────────────────────────────────────────────────────

    /**
     * MobEffect paketi (0x1C) — İstemciye efekt enjekte et
     * eventId: 1=add, 2=modify, 3=remove
     * effectId: 16=NightVision, 11=Speed, 1=Speed, 5=Strength...
     */
    fun buildMobEffect(
        runtimeId : Long,
        eventId   : Int,
        effectId  : Int,
        amplifier : Int   = 0,
        particles : Boolean = false,
        duration  : Int   = 1000000
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.MOB_EFFECT)
        writeVarLong(out, runtimeId)
        out.write(eventId)
        writeVarInt(out, effectId)
        writeVarInt(out, amplifier)
        out.write(if (particles) 1 else 0)
        writeVarInt(out, duration)
        return wrapBatch(out.toByteArray())
    }

    // ── BlockEntityData (Crystal Place helper) ────────────────────────────

    /**
     * UseItem paketi (0x1F) — Blok üzerine item kullanma (kristal yerleştirme)
     * actionType: 0=clickBlock, 1=clickAir, 2=breakBlock
     */
    fun buildUseItem(
        actionType : Int,
        blockX: Int, blockY: Int, blockZ: Int,
        face: Int = 1,
        runtimeId: Long = 0L,
        x: Float = 0f, y: Float = 0f, z: Float = 0f
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarInt(out, BedrockPacketIds.USE_ITEM)
        writeVarInt(out, actionType)
        writeBlockPos(out, blockX, blockY, blockZ)
        writeVarInt(out, face)
        writeVarInt(out, 0)   // hotbarSlot
        writeItem(out)        // heldItem
        writeVec3(out, x, y, z)   // playerPos
        writeVec3(out, 0.5f, 0.5f, 0.5f) // clickPos
        return wrapBatch(out.toByteArray())
    }

    // ── PlayerAuthInput ───────────────────────────────────────────────────

    /**
     * Mevcut bir PlayerAuthInput paketinin x,y,z değerlerini patch'ler.
     * Jetpack/TPAura konum değiştirmesi için.
     */
    fun patchPlayerAuthInputPosition(
        original: ByteArray,
        x: Float, y: Float, z: Float
    ): ByteArray {
        if (original.size < 13) return original
        val copy = original.copyOf()
        // PlayerAuthInput: [packetId varint] [pitch float32LE] [yaw float32LE] [x float32LE] [y float32LE] [z float32LE]
        // varint(0x90) = 1 byte, sonra pitch(4)+yaw(4)+x(4)+y(4)+z(4)
        val baseOffset = varIntSize(BedrockPacketIds.PLAYER_AUTH_INPUT)
        val xOff = baseOffset + 8   // pitch(4) + yaw(4)
        if (copy.size < xOff + 12) return original
        writeFloatLEInto(copy, xOff,     x)
        writeFloatLEInto(copy, xOff + 4, y)
        writeFloatLEInto(copy, xOff + 8, z)
        return copy
    }

    // ── Paket Wrap ────────────────────────────────────────────────────────

    /**
     * Ham paket verisini Bedrock batch formatına sarar: 0xFE + [varint(len) + data]
     */
    fun wrapBatch(packetData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFE)
        writeVarInt(out, packetData.size)
        out.write(packetData)
        return out.toByteArray()
    }

    // ── Düşük seviye yazıcılar ────────────────────────────────────────────

    fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }

    fun writeVarLong(out: ByteArrayOutputStream, value: Long) {
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
    }

    fun writeFloatLE(out: ByteArrayOutputStream, value: Float) {
        val bits = java.lang.Float.floatToIntBits(value)
        out.write(bits and 0xFF)
        out.write((bits shr 8) and 0xFF)
        out.write((bits shr 16) and 0xFF)
        out.write((bits shr 24) and 0xFF)
    }

    private fun writeFloatLEInto(arr: ByteArray, offset: Int, value: Float) {
        val bits = java.lang.Float.floatToIntBits(value)
        arr[offset]     = (bits and 0xFF).toByte()
        arr[offset + 1] = ((bits shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((bits shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((bits shr 24) and 0xFF).toByte()
    }

    private fun writeVec3(out: ByteArrayOutputStream, x: Float, y: Float, z: Float) {
        writeFloatLE(out, x); writeFloatLE(out, y); writeFloatLE(out, z)
    }

    private fun writeBlockPos(out: ByteArrayOutputStream, x: Int, y: Int, z: Int) {
        writeVarInt(out, x)
        writeVarInt(out, y)
        writeVarInt(out, z)
    }

    private fun writeItem(out: ByteArrayOutputStream) {
        writeVarInt(out, 0)  // itemId = 0 (air)
    }

    private fun varIntSize(value: Int): Int {
        var v = value; var size = 0
        do { v = v ushr 7; size++ } while (v != 0)
        return size
    }

    // ── Okuyucular ────────────────────────────────────────────────────────

    fun readVarInt(data: ByteArray, offset: Int = 0): Pair<Int, Int> {
        var result = 0; var shift = 0; var pos = offset
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to pos
    }

    fun readVarLong(data: ByteArray, offset: Int = 0): Pair<Long, Int> {
        var result = 0L; var shift = 0; var pos = offset
        while (pos < data.size) {
            val b = data[pos++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80L == 0L) break
            shift += 7
        }
        return result to pos
    }

    fun readFloatLE(data: ByteArray, offset: Int): Float {
        val bits = (data[offset].toInt() and 0xFF) or
                   ((data[offset+1].toInt() and 0xFF) shl 8) or
                   ((data[offset+2].toInt() and 0xFF) shl 16) or
                   ((data[offset+3].toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun readString(data: ByteArray, offset: Int): Pair<String, Int> {
        val (len, pos) = readVarInt(data, offset)
        if (len <= 0 || pos + len > data.size) return "" to pos
        return String(data, pos, len, Charsets.UTF_8) to pos + len
    }
}
