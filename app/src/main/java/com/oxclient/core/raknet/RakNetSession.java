package com.oxclient.core.raknet;

import android.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight RakNet reliability framing decoder for one connection. */
public class RakNetSession {
    private static final String TAG = "RakNetSession";

    public static final byte FLAG_VALID = (byte) 0x80;
    public static final byte FLAG_ACK   = (byte) 0x40;
    public static final byte FLAG_NAK   = (byte) 0x20;

    public enum Direction { C2S, S2C }

    private final InetSocketAddress addr;
    private final Direction         dir;
    private int                     recvSeq = -1;

    private final Map<Integer, SplitBuf> splits = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, SplitBuf> e) { return size() > 64; }
    };

    public RakNetSession(InetSocketAddress addr, Direction dir) {
        this.addr = addr; this.dir = dir;
    }

    public byte[] decode(ByteBuf raw) {
        if (raw == null || !raw.isReadable()) return null;
        raw.markReaderIndex();
        try {
            byte flags = raw.readByte();
            if ((flags & FLAG_ACK) != 0) { return null; } // ACK
            if ((flags & FLAG_NAK) != 0) { return null; } // NAK
            if ((flags & FLAG_VALID) == 0) return null;
            int seq = readInt24LE(raw);
            if (seq > recvSeq) recvSeq = seq;
            while (raw.isReadable()) {
                byte[] p = decodeFrame(raw);
                if (p != null) return p;
            }
        } catch (Exception e) {
            Log.v(TAG, "[" + dir + "] decode error: " + e.getMessage());
            raw.resetReaderIndex();
        }
        return null;
    }

    private byte[] decodeFrame(ByteBuf b) {
        if (!b.isReadable()) return null;
        byte ff = b.readByte();
        int rel = (ff & 0xE0) >> 5;
        boolean split = (ff & 0x10) != 0;
        int bitLen  = b.readShort() & 0xFFFF;
        int byteLen = (bitLen + 7) / 8;
        if (isReliable(rel)) readInt24LE(b);
        if (isSequenced(rel)) readInt24LE(b);
        if (isOrdered(rel)) { readInt24LE(b); b.readByte(); }
        int sc = 0, si = 0, sidx = 0;
        if (split) { sc = b.readInt(); si = b.readShort() & 0xFFFF; sidx = b.readInt(); }
        byte[] payload = new byte[byteLen]; b.readBytes(payload);
        if (split) return handleSplit(si, sc, sidx, payload);
        return payload;
    }

    private byte[] handleSplit(int id, int count, int idx, byte[] data) {
        SplitBuf sb = splits.computeIfAbsent(id, k -> new SplitBuf(count));
        sb.parts[idx] = data; sb.received++;
        if (sb.received < count) return null;
        splits.remove(id);
        int total = 0; for (byte[] p : sb.parts) if (p != null) total += p.length;
        ByteBuf asm = Unpooled.buffer(total);
        for (byte[] p : sb.parts) if (p != null) asm.writeBytes(p);
        byte[] r = new byte[asm.readableBytes()]; asm.readBytes(r); asm.release();
        return r;
    }

    private boolean isReliable(int r)  { return r==2||r==3||r==4||r==6||r==7; }
    private boolean isSequenced(int r) { return r==1||r==4; }
    private boolean isOrdered(int r)   { return r==3||r==7; }

    private static int readInt24LE(ByteBuf b) {
        return (b.readByte()&0xFF)|((b.readByte()&0xFF)<<8)|((b.readByte()&0xFF)<<16);
    }

    public InetSocketAddress getAddr() { return addr; }
    public Direction getDir()          { return dir; }

    private static class SplitBuf {
        final byte[][] parts; int received = 0;
        SplitBuf(int n) { parts = new byte[n][]; }
    }
}
