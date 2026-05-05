package com.oxclient.core.proxy;

import android.util.Log;

import com.oxclient.events.PacketEvent;
import com.oxclient.events.PacketEventBus;
import com.oxclient.session.SessionManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * PacketProcessor
 *
 * Bedrock Batch (0xFE 0x01) paketlerini açar → sub-paketleri dispatch eder → re-pack eder.
 *
 * DÜZELTME 1 — Batch decompression:
 *   Önceki kod batch'i açmadan 0xFE olarak dispatch ediyordu.
 *   Hiçbir modül BATCH event'i dinlemediğinden StartGame/AddPlayer/MovePlayer
 *   hiç işlenmiyordu → EntityTracker boş kalıyordu → KillAura hedef bulamıyordu.
 *
 * DÜZELTME 2 — EntityTracker.onClientPacket():
 *   C2S paketleri dispatch edildikten sonra EntityTracker'a bildirilir.
 *   Bu sayede PLAYER_AUTH_INPUT paketi selfX/Y/Z günceller.
 *   Önceki kodda bu çağrı hiç yoktu → selfX=0,selfY=0,selfZ=0 → tüm entityler
 *   "range dışında" → KillAura hiç saldırmıyordu.
 */
public class PacketProcessor {
    private static final String TAG = "PacketProcessor";

    // Batch packet id VarInt = 0xFE → 2-byte encoding: 0xFE 0x01
    private static final int BATCH_VARINT_B0 = 0xFE;
    private static final int BATCH_VARINT_B1 = 0x01;

    private static final Map<Integer, String> NAMES = new HashMap<>();
    static {
        NAMES.put(PacketIds.LOGIN,                "Login");
        NAMES.put(PacketIds.PLAY_STATUS,          "PlayStatus");
        NAMES.put(PacketIds.START_GAME,           "StartGame");
        NAMES.put(PacketIds.ADD_PLAYER,           "AddPlayer");
        NAMES.put(PacketIds.ADD_ENTITY,           "AddEntity");
        NAMES.put(PacketIds.REMOVE_ENTITY,        "RemoveEntity");
        NAMES.put(PacketIds.MOVE_PLAYER,          "MovePlayer");
        NAMES.put(PacketIds.MOVE_ENTITY_ABSOLUTE, "MoveEntityAbs");
        NAMES.put(PacketIds.MOVE_ENTITY_DELTA,    "MoveEntityDelta");
        NAMES.put(PacketIds.PLAYER_AUTH_INPUT,    "PlayerAuthInput");
        NAMES.put(PacketIds.INVENTORY_TRANSACTION,"InventoryTransaction");
        NAMES.put(PacketIds.INVENTORY_CONTENT,    "InventoryContent");
        NAMES.put(PacketIds.INVENTORY_SLOT,       "InventorySlot");
        NAMES.put(PacketIds.SET_ENTITY_DATA,      "SetEntityData");
        NAMES.put(PacketIds.UPDATE_ATTRIBUTES,    "UpdateAttributes");
        NAMES.put(PacketIds.ANIMATE,              "Animate");
        NAMES.put(PacketIds.RESPAWN,              "Respawn");
        NAMES.put(PacketIds.TEXT,                 "Text");
        NAMES.put(PacketIds.BATCH,                "Batch");
        NAMES.put(PacketIds.DISCONNECT,           "Disconnect");
        NAMES.put(PacketIds.NETWORK_STACK_LATENCY,"NetworkLatency");
    }

    // ── Public API ────────────────────────────────────────────────────────

    public byte[] processC2S(byte[] payload, InetSocketAddress addr) {
        if (payload == null || payload.length == 0) return payload;
        if (isBatch(payload)) return processBatch(payload, addr, PacketEvent.DIRECTION_C2S);
        return dispatchAndTrack(payload, addr, PacketEvent.DIRECTION_C2S);
    }

    public byte[] processS2C(byte[] payload, InetSocketAddress addr) {
        if (payload == null || payload.length == 0) return payload;
        if (isBatch(payload)) return processBatch(payload, addr, PacketEvent.DIRECTION_S2C);
        return dispatchAndTrack(payload, addr, PacketEvent.DIRECTION_S2C);
    }

    // ── Batch ─────────────────────────────────────────────────────────────

    /** Bedrock Batch: ilk 2 byte = VarInt 0xFE (0xFE 0x01) */
    private boolean isBatch(byte[] p) {
        return p.length > 2
            && (p[0] & 0xFF) == BATCH_VARINT_B0
            && (p[1] & 0xFF) == BATCH_VARINT_B1;
    }

    private byte[] processBatch(byte[] payload, InetSocketAddress addr, int dir) {
        try {
            // Header atla (2 byte: 0xFE 0x01)
            byte[] compressed = new byte[payload.length - 2];
            System.arraycopy(payload, 2, compressed, 0, compressed.length);

            byte[]       raw        = inflate(compressed);
            List<byte[]> subPackets = splitSubPackets(raw);
            if (subPackets.isEmpty()) return payload;

            boolean      modified  = false;
            List<byte[]> processed = new ArrayList<>(subPackets.size());

            for (byte[] sub : subPackets) {
                byte[] result = dispatchAndTrack(sub, addr, dir);
                if (result == null) { modified = true; continue; } // dropped
                if (result != sub)    modified = true;
                processed.add(result);
            }

            if (!modified)          return payload;
            if (processed.isEmpty()) return null;   // tümü drop → paketi yutma
            return packBatch(processed);

        } catch (Exception e) {
            Log.e(TAG, "processBatch error: " + e.getMessage());
            return payload;
        }
    }

    private List<byte[]> splitSubPackets(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int len = readVarInt(b);
            if (len <= 0 || len > b.remaining()) break;
            byte[] sub = new byte[len];
            b.get(sub);
            list.add(sub);
        }
        return list;
    }

    private byte[] packBatch(List<byte[]> subs) throws Exception {
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        for (byte[] s : subs) { writeVarInt(rawOut, s.length); rawOut.write(s); }
        byte[] compressed = deflate(rawOut.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length + 2);
        out.write(BATCH_VARINT_B0);
        out.write(BATCH_VARINT_B1);
        out.write(compressed);
        return out.toByteArray();
    }

    // ── Single packet dispatch ────────────────────────────────────────────

    /**
     * Tek paketi dispatch eder ve EntityTracker'ı günceller.
     * null → paket drop edildi.
     */
    private byte[] dispatchAndTrack(byte[] payload, InetSocketAddress addr, int dir) {
        if (payload == null || payload.length == 0) return payload;

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int    id   = readVarInt(buf);
        String name = NAMES.getOrDefault(id, String.format("Unk(0x%02X)", id));

        PacketEvent ev = new PacketEvent(id, name, payload, dir, addr);
        PacketEventBus.publish(ev);

        if (ev.isCancelled()) {
            Log.d(TAG, "Dropped: " + name + " dir=" + (dir == 0 ? "C2S" : "S2C"));
            return null;
        }

        // FIX: C2S paketlerini EntityTracker'a ilet (selfX/Y/Z güncelleme)
        if (dir == PacketEvent.DIRECTION_C2S) {
            try {
                SessionManager.INSTANCE.getEntityTracker().onClientPacket(ev);
            } catch (Exception ignored) {}
        }

        return ev.getPayload();
    }

    // ── Zlib ─────────────────────────────────────────────────────────────

    private static byte[] inflate(byte[] data) throws Exception {
        try (InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = inf.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static byte[] deflate(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream def = new DeflaterOutputStream(out)) {
            def.write(data);
        }
        return out.toByteArray();
    }

    // ── VarInt ───────────────────────────────────────────────────────────

    public static int readVarInt(ByteBuffer b) {
        int v = 0, s = 0;
        byte c;
        do {
            if (!b.hasRemaining()) break;
            c = b.get();
            v |= (c & 0x7F) << s;
            s += 7;
        } while ((c & 0x80) != 0 && s < 35);
        return v;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int v) {
        do {
            byte c = (byte)(v & 0x7F);
            v >>>= 7;
            if (v != 0) c |= (byte) 0x80;
            out.write(c);
        } while (v != 0);
    }
}
