package com.oxclient.core.proxy;

import com.oxclient.utils.BinaryUtils;
import java.nio.ByteBuffer;

/**
 * PacketFactory — builds raw Bedrock packet byte arrays for injection.
 * All methods return the raw payload (no RakNet framing).
 */
public final class PacketFactory {
    private PacketFactory() {}

    // ── Attack entity (KillAura) ─────────────────────────────────────────
    public static byte[] buildAttack(
        long targetRuntimeId, int hotbarSlot,
        float px, float py, float pz,
        float tx, float ty, float tz
    ) {
        ByteBuffer b = BinaryUtils.allocate(128);
        BinaryUtils.writeVarInt(b, PacketIds.INVENTORY_TRANSACTION);
        BinaryUtils.writeVarInt(b, 0); // legacyRequestId
        BinaryUtils.writeVarInt(b, PacketIds.TX_USE_ITEM_ON_ENTITY);
        BinaryUtils.writeVarLong(b, targetRuntimeId);
        BinaryUtils.writeVarInt(b, 1); // actionType ATTACK
        BinaryUtils.writeVarInt(b, hotbarSlot);
        writeAir(b);                   // heldItem
        BinaryUtils.writeVec3(b, px, py, pz);
        BinaryUtils.writeVec3(b, tx, ty + 1.0f, tz);
        return BinaryUtils.trim(b);
    }

    // ── Animate / swing arm ──────────────────────────────────────────────
    public static byte[] buildSwingArm(long selfRuntimeId) {
        ByteBuffer b = BinaryUtils.allocate(16);
        BinaryUtils.writeVarInt(b, PacketIds.ANIMATE);
        BinaryUtils.writeVarInt(b, 1); // SWING_ARM
        BinaryUtils.writeVarLong(b, selfRuntimeId);
        return BinaryUtils.trim(b);
    }

    // ── MovePlayer ───────────────────────────────────────────────────────
    public static byte[] buildMovePlayer(
        long runtimeId,
        float x, float y, float z,
        float yaw, float pitch, float headYaw,
        boolean onGround
    ) {
        ByteBuffer b = BinaryUtils.allocate(64);
        BinaryUtils.writeVarInt(b, PacketIds.MOVE_PLAYER);
        BinaryUtils.writeVarLong(b, runtimeId);
        BinaryUtils.writeVec3(b, x, y, z);
        BinaryUtils.writeLEFloat(b, pitch);
        BinaryUtils.writeLEFloat(b, yaw);
        BinaryUtils.writeLEFloat(b, headYaw);
        b.put((byte) 1); // mode NORMAL
        b.put((byte)(onGround ? 1 : 0));
        BinaryUtils.writeVarLong(b, 0L);
        return BinaryUtils.trim(b);
    }

    // ── Off-hand totem equip ─────────────────────────────────────────────
    public static byte[] buildOffhandEquip(int fromSlot, int itemId, int count) {
        ByteBuffer b = BinaryUtils.allocate(256);
        BinaryUtils.writeVarInt(b, PacketIds.INVENTORY_TRANSACTION);
        BinaryUtils.writeVarInt(b, 0);
        BinaryUtils.writeVarInt(b, PacketIds.TX_NORMAL);
        BinaryUtils.writeVarInt(b, 2); // 2 actions
        // Action 1: take from inventory
        BinaryUtils.writeVarInt(b, 0); // source CONTAINER
        BinaryUtils.writeVarInt(b, PacketIds.CONTAINER_INVENTORY);
        BinaryUtils.writeVarInt(b, fromSlot);
        writeItem(b, itemId, count, 0); // fromItem
        writeAir(b);                    // toItem
        // Action 2: put to offhand
        BinaryUtils.writeVarInt(b, 0);
        BinaryUtils.writeVarInt(b, PacketIds.CONTAINER_OFFHAND);
        BinaryUtils.writeVarInt(b, 0); // slot
        writeAir(b);
        writeItem(b, itemId, count, 0);
        return BinaryUtils.trim(b);
    }

    // ── PlayerAction ─────────────────────────────────────────────────────
    public static byte[] buildPlayerAction(long runtimeId, int actionId) {
        ByteBuffer b = BinaryUtils.allocate(32);
        BinaryUtils.writeVarInt(b, PacketIds.PLAYER_ACTION);
        BinaryUtils.writeVarLong(b, runtimeId);
        BinaryUtils.writeVarInt(b, actionId);
        BinaryUtils.writeSignedVarInt(b, 0);
        BinaryUtils.writeSignedVarInt(b, 0);
        BinaryUtils.writeSignedVarInt(b, 0);
        BinaryUtils.writeVarInt(b, 0);
        return BinaryUtils.trim(b);
    }

    // ── Item stack helpers ───────────────────────────────────────────────
    public static void writeAir(ByteBuffer b) { BinaryUtils.writeVarInt(b, 0); }
    public static void writeItem(ByteBuffer b, int id, int count, int damage) {
        BinaryUtils.writeVarInt(b, id);
        if (id == 0) return;
        BinaryUtils.writeVarInt(b, count);
        BinaryUtils.writeVarInt(b, damage);
        b.put((byte) 0); // hasNbt
        BinaryUtils.writeVarInt(b, 0); // canPlaceOn
        BinaryUtils.writeVarInt(b, 0); // canDestroy
    }
}
