package com.oxclient.core.entity;

import android.util.Log;
import com.oxclient.core.proxy.PacketIds;
import com.oxclient.events.PacketEvent;
import com.oxclient.utils.BinaryUtils;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EntityTracker — maintains a live mirror of all entities.
 * Updated from S→C packets (AddPlayer, AddEntity, MoveEntity, etc.).
 * Queried by KillAura, TPAura to find targets.
 */
public class EntityTracker {
    private static final String TAG = "EntityTracker";

    private final ConcurrentHashMap<Long, TrackedEntity> entities = new ConcurrentHashMap<>();

    public volatile long  selfId = -1L;
    public volatile float selfX = 0, selfY = 0, selfZ = 0;
    public volatile float selfYaw = 0, selfPitch = 0;

    // ── Entry points ──────────────────────────────────────────────────────

    public void onServerPacket(PacketEvent ev) {
        int id = ev.getPacketId();
        try {
            switch (id) {
                case PacketIds.START_GAME         -> parseStartGame(ev.getPayload());
                case PacketIds.ADD_PLAYER         -> parseAddPlayer(ev.getPayload());
                case PacketIds.ADD_ENTITY         -> parseAddEntity(ev.getPayload());
                case PacketIds.REMOVE_ENTITY      -> parseRemoveEntity(ev.getPayload());
                case PacketIds.MOVE_ENTITY_ABSOLUTE -> parseMoveAbsolute(ev.getPayload());
                case PacketIds.MOVE_ENTITY_DELTA  -> parseMoveDelta(ev.getPayload());
                case PacketIds.SET_ENTITY_DATA    -> parseEntityData(ev.getPayload());
                case PacketIds.UPDATE_ATTRIBUTES  -> parseAttributes(ev.getPayload());
                case PacketIds.RESPAWN            -> clear();
            }
        } catch (Exception e) {
            Log.v(TAG, "Parse error pkt=0x" + Integer.toHexString(id) + " " + e.getMessage());
        }
    }

    public void onClientPacket(PacketEvent ev) {
        if (ev.getPacketId() != PacketIds.PLAYER_AUTH_INPUT) return;
        try {
            ByteBuffer b = BinaryUtils.wrap(ev.getPayload());
            BinaryUtils.skipVarInt(b);
            selfPitch = BinaryUtils.readLEFloat(b);
            selfYaw   = BinaryUtils.readLEFloat(b);
            selfX     = BinaryUtils.readLEFloat(b);
            selfY     = BinaryUtils.readLEFloat(b);
            selfZ     = BinaryUtils.readLEFloat(b);
        } catch (Exception ignored) {}
    }

    // ── Parsers ───────────────────────────────────────────────────────────

    private void parseStartGame(byte[] d) {
        ByteBuffer b = BinaryUtils.wrap(d);
        BinaryUtils.skipVarInt(b);
        BinaryUtils.readVarLong(b); // entityUniqueId
        selfId = BinaryUtils.readVarLong(b);
        Log.i(TAG, "Self runtime ID: " + selfId);
    }

    private void parseAddPlayer(byte[] d) {
        ByteBuffer b = BinaryUtils.wrap(d);
        BinaryUtils.skipVarInt(b);
        UUID uuid   = BinaryUtils.readUUID(b);
        String name = BinaryUtils.readString(b);
        long uid    = BinaryUtils.readVarLong(b);
        long rid    = BinaryUtils.readVarLong(b);
        BinaryUtils.readString(b); // platformChatId
        float x = BinaryUtils.readLEFloat(b);
        float y = BinaryUtils.readLEFloat(b);
        float z = BinaryUtils.readLEFloat(b);

        TrackedEntity e = new TrackedEntity(rid, uid, TrackedEntity.Type.PLAYER);
        e.name = name; e.uuid = uuid; e.x = x; e.y = y; e.z = z;
        entities.put(rid, e);
        Log.d(TAG, "AddPlayer: " + name + " rid=" + rid);
    }

    private void parseAddEntity(byte[] d) {
        ByteBuffer b = BinaryUtils.wrap(d);
        BinaryUtils.skipVarInt(b);
        long uid    = BinaryUtils.readVarLong(b);
        long rid    = BinaryUtils.readVarLong(b);
        String type = BinaryUtils.readString(b);
        float x = BinaryUtils.readLEFloat(b);
        float y = BinaryUtils.readLEFloat(b);
        float z = BinaryUtils.readLEFloat(b);

        TrackedEntity e = new TrackedEntity(rid, uid, TrackedEntity.Type.MOB);
        e.entityType = type; e.x = x; e.y = y; e.z = z;
        entities.put(rid, e);
    }

    private void parseRemoveEntity(byte[] d) {
        ByteBuffer b = BinaryUtils.wrap(d);
        BinaryUtils.skipVarInt(b);
        long uid = BinaryUtils.readVarLong(b);
        entities.entrySet().removeIf(entry -> entry.getValue().uniqueId == uid);
    }

    private void parseMoveAbsolute(byte[] d) {
        ByteBuffer b = BinaryUtils.wrap(d);
        BinaryUtils.skipVarInt(b);
        long rid = BinaryUtils.readVarLong(b);
        b.get(); // flags
        float x = BinaryUtils.readLEFloat(b);
        float y = BinaryUtils.readLEFloat(b);
        float z = BinaryUtils.readLEFloat(b);
        TrackedEntity e = entities.get(rid);
        if (e != null) { e.x = x; e.y = y; e.z = z; }
    }

    private void parseMoveDelta(byte[] d) {
        ByteBuffer b = BinaryUtils.wrap(d);
        BinaryUtils.skipVarInt(b);
        long rid   = BinaryUtils.readVarLong(b);
        int  flags = BinaryUtils.readLEInt(b);
        TrackedEntity e = entities.get(rid);
        if (e == null) return;
        if ((flags & 0x01) != 0) e.x += BinaryUtils.readLEFloat(b);
        if ((flags & 0x02) != 0) e.y += BinaryUtils.readLEFloat(b);
        if ((flags & 0x04) != 0) e.z += BinaryUtils.readLEFloat(b);
    }

    private void parseEntityData(byte[] d) {
        ByteBuffer b = BinaryUtils.wrap(d);
        BinaryUtils.skipVarInt(b);
        long rid = BinaryUtils.readVarLong(b);
        TrackedEntity e = entities.get(rid);
        int count = BinaryUtils.readVarInt(b);
        for (int i = 0; i < count && b.hasRemaining(); i++) {
            int key  = BinaryUtils.readVarInt(b);
            int type = BinaryUtils.readVarInt(b);
            if (key == 0 && type == 7) {
                long flags = BinaryUtils.readVarLong(b);
                if (e != null) e.invisible = (flags & PacketIds.FLAG_INVISIBLE) != 0;
            } else {
                skipMeta(b, type);
            }
        }
    }

    private void parseAttributes(byte[] d) {
        ByteBuffer b = BinaryUtils.wrap(d);
        BinaryUtils.skipVarInt(b);
        long rid = BinaryUtils.readVarLong(b);
        int count = BinaryUtils.readVarInt(b);
        TrackedEntity e = entities.get(rid);
        for (int i = 0; i < count && b.hasRemaining(); i++) {
            float min  = BinaryUtils.readLEFloat(b);
            float max  = BinaryUtils.readLEFloat(b);
            float curr = BinaryUtils.readLEFloat(b);
            float def  = BinaryUtils.readLEFloat(b);
            String nm  = BinaryUtils.readString(b);
            int mc     = BinaryUtils.readVarInt(b);
            for (int m = 0; m < mc && b.hasRemaining(); m++) {
                if (b.remaining() < 25) break;
                b.position(b.position() + 16);
                BinaryUtils.readString(b);
                b.position(b.position() + 12);
            }
            if (e != null && "minecraft:health".equals(nm)) {
                e.health = curr; e.maxHealth = max;
                if (curr <= 0) e.dead = true;
            }
        }
    }

    private void skipMeta(ByteBuffer b, int type) {
        try {
            switch (type) {
                case 0 -> b.get();
                case 1 -> BinaryUtils.readSignedVarInt(b);
                case 2 -> BinaryUtils.readSignedVarInt(b);
                case 3 -> BinaryUtils.readLEFloat(b);
                case 4 -> BinaryUtils.readString(b);
                case 7 -> BinaryUtils.readVarLong(b);
                case 8 -> { BinaryUtils.readLEFloat(b); BinaryUtils.readLEFloat(b); BinaryUtils.readLEFloat(b); }
            }
        } catch (Exception ignored) {}
    }

    // ── Query API ─────────────────────────────────────────────────────────

    public List<TrackedEntity> getNearby(float x, float y, float z, float range) {
        float rSq = range * range;
        List<TrackedEntity> out = new ArrayList<>();
        for (TrackedEntity e : entities.values()) {
            if (e.runtimeId == selfId) continue;
            if (e.invisible || e.dead) continue;
            float dx = e.x-x, dy = e.y-y, dz = e.z-z;
            float dSq = dx*dx+dy*dy+dz*dz;
            if (dSq <= rSq) { e.cachedDistSq = dSq; out.add(e); }
        }
        return out;
    }

    public TrackedEntity getNearest(float x, float y, float z, float range) {
        List<TrackedEntity> list = getNearby(x, y, z, range);
        if (list.isEmpty()) return null;
        list.sort((a,b) -> Float.compare(a.cachedDistSq, b.cachedDistSq));
        return list.get(0);
    }

    public TrackedEntity getById(long rid) { return entities.get(rid); }
    public Collection<TrackedEntity> all() { return entities.values(); }
    public int count()                     { return entities.size(); }
    public long getSelfId()                { return selfId; }

    public void clear() {
        entities.clear();
        Log.i(TAG, "Entity list cleared");
    }
}
