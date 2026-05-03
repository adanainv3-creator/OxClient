package com.oxclient.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** Low-level binary helpers for Bedrock protocol parsing/serialization. */
public final class BinaryUtils {
    private BinaryUtils() {}

    public static ByteBuffer allocate(int cap) {
        return ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN);
    }
    public static ByteBuffer wrap(byte[] d) {
        return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
    }

    // VarInt (unsigned)
    public static int readVarInt(ByteBuffer b) {
        int v = 0, s = 0; byte c;
        do { if (!b.hasRemaining()) break; c = b.get(); v |= (c & 0x7F) << s; s += 7; }
        while ((c & 0x80) != 0 && s < 35); return v;
    }
    public static void writeVarInt(ByteBuffer b, int v) {
        do { byte c = (byte)(v & 0x7F); v >>>= 7; if (v != 0) c |= 0x80; b.put(c); } while (v != 0);
    }
    public static void skipVarInt(ByteBuffer b) {
        byte c; do { if (!b.hasRemaining()) return; c = b.get(); } while ((c & 0x80) != 0);
    }
    public static byte[] varIntBytes(int v) {
        byte[] t = new byte[5]; int i = 0;
        do { byte c = (byte)(v & 0x7F); v >>>= 7; if (v != 0) c |= 0x80; t[i++] = c; } while (v != 0);
        byte[] o = new byte[i]; System.arraycopy(t,0,o,0,i); return o;
    }

    // Signed VarInt (ZigZag)
    public static int readSignedVarInt(ByteBuffer b) { int r = readVarInt(b); return (r>>>1)^-(r&1); }
    public static void writeSignedVarInt(ByteBuffer b, int v) { writeVarInt(b,(v<<1)^(v>>31)); }

    // VarLong (unsigned)
    public static long readVarLong(ByteBuffer b) {
        long v = 0; int s = 0; byte c;
        do { if (!b.hasRemaining()) break; c = b.get(); v |= (long)(c&0x7F)<<s; s+=7; }
        while ((c&0x80)!=0 && s<63); return v;
    }
    public static void writeVarLong(ByteBuffer b, long v) {
        do { byte c=(byte)(v&0x7F); v>>>=7; if(v!=0)c|=0x80; b.put(c); } while(v!=0);
    }
    public static void skipVarLong(ByteBuffer b) {
        byte c; do { if(!b.hasRemaining())return; c=b.get(); } while((c&0x80)!=0);
    }

    // LE primitives
    public static float  readLEFloat(ByteBuffer b)  { return Float.intBitsToFloat(Integer.reverseBytes(b.getInt())); }
    public static void   writeLEFloat(ByteBuffer b, float v) { b.putInt(Integer.reverseBytes(Float.floatToRawIntBits(v))); }
    public static double readLEDouble(ByteBuffer b) { return Double.longBitsToDouble(Long.reverseBytes(b.getLong())); }
    public static void   writeLEDouble(ByteBuffer b, double v) { b.putLong(Long.reverseBytes(Double.doubleToRawLongBits(v))); }
    public static int    readLEInt(ByteBuffer b)    { return Integer.reverseBytes(b.getInt()); }
    public static void   writeLEInt(ByteBuffer b, int v) { b.putInt(Integer.reverseBytes(v)); }
    public static long   readLELong(ByteBuffer b)   { return Long.reverseBytes(b.getLong()); }

    // String (VarInt length-prefixed UTF-8)
    public static String readString(ByteBuffer b) {
        int len = readVarInt(b); if (len<=0) return "";
        byte[] arr = new byte[Math.min(len, b.remaining())]; b.get(arr);
        return new String(arr, java.nio.charset.StandardCharsets.UTF_8);
    }
    public static void writeString(ByteBuffer b, String s) {
        byte[] arr = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(b, arr.length); b.put(arr);
    }

    // UUID (big-endian 16 bytes)
    public static UUID readUUID(ByteBuffer b)       { return new UUID(b.getLong(), b.getLong()); }
    public static void writeUUID(ByteBuffer b, UUID u) { b.putLong(u.getMostSignificantBits()); b.putLong(u.getLeastSignificantBits()); }

    // Vec3 (3× LE float)
    public static float[] readVec3(ByteBuffer b)    { return new float[]{ readLEFloat(b), readLEFloat(b), readLEFloat(b) }; }
    public static void writeVec3(ByteBuffer b, float x, float y, float z) { writeLEFloat(b,x); writeLEFloat(b,y); writeLEFloat(b,z); }

    // Buffer trim
    public static byte[] trim(ByteBuffer b) {
        int p = b.position(); byte[] o = new byte[p]; b.rewind(); b.get(o); return o;
    }

    // Math helpers
    public static double distSq(float ax, float ay, float az, float bx, float by, float bz) {
        double dx=ax-bx, dy=ay-by, dz=az-bz; return dx*dx+dy*dy+dz*dz;
    }
    public static double dist(float ax,float ay,float az,float bx,float by,float bz) {
        return Math.sqrt(distSq(ax,ay,az,bx,by,bz));
    }
}
