package com.oxclient.core.raknet;

import android.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight RakNet reliability framing decoder for one connection.
 *
 * FIX: decodeAll() eklendi.
 *
 * Önceki decode() metodu bir datagramdan yalnızca ilk geçerli frame'i döndürüp
 * duruyordu. RakNet datagramları birden fazla frame içerebilir (özellikle
 * handshake sırasında). Kalan frame'ler sessizce atılıyordu; bu yüzden
 * bağlantı handshake'i tamamlanamıyor ve sunucuya girilmiyordu.
 *
 * Çözüm: decodeAll() tüm frame'leri bir List<byte[]> olarak döndürür.
 * decode() geriye dönük uyumluluk için korundu (ilk frame'i döndürür).
 */
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
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, SplitBuf> e) {
            return size() > 64;
        }
    };

    public RakNetSession(InetSocketAddress addr, Direction dir) {
        this.addr = addr;
        this.dir  = dir;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * FIX: Bir RakNet datagramındaki TÜM frame'leri decode eder.
     *
     * Boş liste döner:
     *   - ACK / NAK paketi ise (sadece ilet, işleme)
     *   - FLAG_VALID set değilse
     *   - Hiç decode edilebilir frame yoksa
     *
     * MitmProxy InboundHandler ve OutboundHandler bu metodu kullanmalı.
     */
    public List<byte[]> decodeAll(ByteBuf raw) {
        List<byte[]> results = new ArrayList<>();
        if (raw == null || !raw.isReadable()) return results;

        raw.markReaderIndex();
        try {
            byte flags = raw.readByte();

            // ACK veya NAK — payload yoktur, olduğu gibi ilet
            if ((flags & FLAG_ACK) != 0 || (flags & FLAG_NAK) != 0) return results;

            // FLAG_VALID set değilse muhtemelen offline mesaj (unconnected ping vs.)
            if ((flags & FLAG_VALID) == 0) return results;

            // Sequence number
            int seq = readInt24LE(raw);
            if (seq > recvSeq) recvSeq = seq;

            // Datagram içindeki TÜM frame'leri oku
            while (raw.isReadable()) {
                byte[] payload = decodeFrame(raw);
                if (payload != null) {
                    results.add(payload);
                }
            }
        } catch (Exception e) {
            Log.v(TAG, "[" + dir + "] decodeAll error: " + e.getMessage());
            raw.resetReaderIndex();
        }
        return results;
    }

    /**
     * Geriye dönük uyumluluk: ilk frame'i döndürür.
     * Yeni kod decodeAll() kullanmalı.
     *
     * @deprecated decodeAll() kullan
     */
    @Deprecated
    public byte[] decode(ByteBuf raw) {
        List<byte[]> all = decodeAll(raw);
        return all.isEmpty() ? null : all.get(0);
    }

    // ── Frame decoder ─────────────────────────────────────────────────────

    private byte[] decodeFrame(ByteBuf b) {
        if (!b.isReadable(3)) return null; // en az flags + bitLen

        byte ff     = b.readByte();
        int  rel    = (ff & 0xE0) >> 5;
        boolean split = (ff & 0x10) != 0;

        int bitLen  = b.readShort() & 0xFFFF;
        int byteLen = (bitLen + 7) / 8;

        if (byteLen == 0) return null;

        // Reliability header alanlarını atla
        if (isReliable(rel))   readInt24LE(b);               // reliable index
        if (isSequenced(rel))  readInt24LE(b);               // sequencing index
        if (isOrdered(rel))    { readInt24LE(b); b.readByte(); } // order index + channel

        // Split header
        int splitCount = 0, splitId = 0, splitIndex = 0;
        if (split) {
            splitCount = b.readInt();
            splitId    = b.readShort() & 0xFFFF;
            splitIndex = b.readInt();
        }

        if (!b.isReadable(byteLen)) return null; // truncated datagram
        byte[] payload = new byte[byteLen];
        b.readBytes(payload);

        if (split) {
            return handleSplit(splitId, splitCount, splitIndex, payload);
        }
        return payload;
    }

    // ── Split packet reassembly ───────────────────────────────────────────

    private byte[] handleSplit(int id, int count, int idx, byte[] data) {
        if (count <= 0 || idx < 0 || idx >= count) return null;

        SplitBuf sb = splits.computeIfAbsent(id, k -> new SplitBuf(count));
        if (idx < sb.parts.length && sb.parts[idx] == null) {
            sb.parts[idx] = data;
            sb.received++;
        }

        if (sb.received < count) return null;

        // Tüm parçalar geldi — birleştir
        splits.remove(id);
        int total = 0;
        for (byte[] p : sb.parts) if (p != null) total += p.length;

        ByteBuf asm = Unpooled.buffer(total);
        for (byte[] p : sb.parts) if (p != null) asm.writeBytes(p);

        byte[] r = new byte[asm.readableBytes()];
        asm.readBytes(r);
        asm.release();
        return r;
    }

    // ── Reliability type helpers ──────────────────────────────────────────

    private boolean isReliable(int r)  { return r == 2 || r == 3 || r == 4 || r == 6 || r == 7; }
    private boolean isSequenced(int r) { return r == 1 || r == 4; }
    private boolean isOrdered(int r)   { return r == 3 || r == 7; }

    private static int readInt24LE(ByteBuf b) {
        return (b.readByte() & 0xFF)
             | ((b.readByte() & 0xFF) << 8)
             | ((b.readByte() & 0xFF) << 16);
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public InetSocketAddress getAddr() { return addr; }
    public Direction         getDir()  { return dir; }

    // ── Inner types ───────────────────────────────────────────────────────

    private static class SplitBuf {
        final byte[][] parts;
        int received = 0;
        SplitBuf(int n) { parts = new byte[n][]; }
    }
}
