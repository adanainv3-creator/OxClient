package com.oxclient.core.proxy;

import android.util.Log;
import com.oxclient.events.PacketEvent;
import com.oxclient.events.PacketEventBus;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/** Reads packet ID from Bedrock payload and dispatches to PacketEventBus. */
public class PacketProcessor {
    private static final String TAG = "PacketProcessor";

    private static final Map<Integer, String> NAMES = new HashMap<>();
    static {
        NAMES.put(PacketIds.LOGIN,              "Login");
        NAMES.put(PacketIds.START_GAME,         "StartGame");
        NAMES.put(PacketIds.ADD_PLAYER,         "AddPlayer");
        NAMES.put(PacketIds.ADD_ENTITY,         "AddEntity");
        NAMES.put(PacketIds.REMOVE_ENTITY,      "RemoveEntity");
        NAMES.put(PacketIds.MOVE_PLAYER,        "MovePlayer");
        NAMES.put(PacketIds.MOVE_ENTITY_ABSOLUTE,"MoveEntityAbs");
        NAMES.put(PacketIds.MOVE_ENTITY_DELTA,  "MoveEntityDelta");
        NAMES.put(PacketIds.PLAYER_AUTH_INPUT,  "PlayerAuthInput");
        NAMES.put(PacketIds.INVENTORY_TRANSACTION,"InventoryTransaction");
        NAMES.put(PacketIds.INVENTORY_CONTENT,  "InventoryContent");
        NAMES.put(PacketIds.INVENTORY_SLOT,     "InventorySlot");
        NAMES.put(PacketIds.SET_ENTITY_DATA,    "SetEntityData");
        NAMES.put(PacketIds.UPDATE_ATTRIBUTES,  "UpdateAttributes");
        NAMES.put(PacketIds.ANIMATE,            "Animate");
        NAMES.put(PacketIds.RESPAWN,            "Respawn");
        NAMES.put(PacketIds.TEXT,               "Text");
        NAMES.put(PacketIds.BATCH,              "Batch");
        NAMES.put(PacketIds.DISCONNECT,         "Disconnect");
    }

    public byte[] processC2S(byte[] payload, InetSocketAddress addr) {
        return dispatch(payload, addr, PacketEvent.DIRECTION_C2S);
    }
    public byte[] processS2C(byte[] payload, InetSocketAddress addr) {
        return dispatch(payload, addr, PacketEvent.DIRECTION_S2C);
    }

    private byte[] dispatch(byte[] payload, InetSocketAddress addr, int dir) {
        if (payload == null || payload.length == 0) return payload;
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int id = readVarInt(buf);
        String name = NAMES.getOrDefault(id, String.format("Unknown(0x%02X)", id));
        PacketEvent ev = new PacketEvent(id, name, payload, dir, addr);
        PacketEventBus.publish(ev);
        if (ev.isCancelled()) { Log.d(TAG, "Dropped: " + name); return null; }
        return ev.getPayload();
    }

    public static int readVarInt(ByteBuffer b) {
        int v = 0, s = 0; byte c;
        do { if (!b.hasRemaining()) break; c = b.get(); v |= (c & 0x7F) << s; s += 7; }
        while ((c & 0x80) != 0 && s < 35); return v;
    }
}
