package com.oxclient.events;

import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * PacketEvent — one Bedrock packet traversing the MITM proxy.
 * Modules receive this via PacketEventBus and may cancel or mutate it.
 */
public final class PacketEvent {
    public static final int DIRECTION_C2S = 0; // Client → Server
    public static final int DIRECTION_S2C = 1; // Server → Client

    private final int packetId;
    private final String packetName;
    private final int direction;
    private final InetSocketAddress clientAddr;

    private byte[] payload;
    private boolean cancelled = false;

    public PacketEvent(int packetId, String packetName, byte[] payload,
                       int direction, InetSocketAddress clientAddr) {
        this.packetId   = packetId;
        this.packetName = packetName;
        this.payload    = Arrays.copyOf(payload, payload.length);
        this.direction  = direction;
        this.clientAddr = clientAddr;
    }

    public int               getPacketId()   { return packetId; }
    public String            getPacketName() { return packetName; }
    public byte[]            getPayload()    { return payload; }
    public int               getDirection()  { return direction; }
    public InetSocketAddress getClientAddr() { return clientAddr; }
    public boolean           isC2S()         { return direction == DIRECTION_C2S; }
    public boolean           isS2C()         { return direction == DIRECTION_S2C; }
    public boolean           isCancelled()   { return cancelled; }

    public void setPayload(byte[] data) {
        if (data == null) throw new IllegalArgumentException("payload null");
        this.payload = data;
    }
    public void cancel() { this.cancelled = true; }

    @Override public String toString() {
        return "PacketEvent{0x" + Integer.toHexString(packetId)
            + "(" + packetName + ") "
            + (direction == DIRECTION_C2S ? "C→S" : "S→C")
            + " len=" + payload.length + " cancelled=" + cancelled + "}";
    }
}
