package com.oxclient.core.proxy;

import android.net.VpnService;
import android.util.Log;

import com.oxclient.BuildConfig;
import com.oxclient.core.raknet.RakNetSession;
import com.oxclient.events.PacketEventBus;
import com.oxclient.session.SessionManager;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MitmProxy — Netty NIO UDP MITM proxy.
 *
 * Paket akışı:
 *   Oyun → TUN → handleIncomingPacket()
 *       → InboundHandler → RakNetSession.decodeAll()
 *       → PacketProcessor.processC2S()
 *       → clientChannel → 2b2tpe.org
 *
 *   2b2tpe.org → clientChannel → OutboundHandler
 *       → RakNetSession.decodeAll()
 *       → PacketProcessor.processS2C()
 *       → tunWriter.write() → TUN → Oyun
 *
 * DÜZELTMELER:
 *   1. protectFromVpn(): reflection ile AbstractNioChannel.ch field'ına erişir.
 *      javaChannel() protected olduğundan doğrudan çağrılamaz.
 *      Korunmasız socket routing loop oluşturur → sunucuya paket ulaşmaz.
 *
 *   2. TunWriter callback: S2C paketler tunWriter üzerinden TUN'a yazılır.
 *      Önceki kod serverChannel'a yazıyordu → oyun paketi almıyordu.
 *
 *   3. decodeAll(): Bir RakNet datagramındaki tüm frame'ler işlenir.
 *      Önceki decode() sadece ilk frame'i alıyordu → handshake paket kaybı.
 */
public class MitmProxy {
    private static final String TAG = "MitmProxy";

    public interface TunWriter {
        void write(byte[] udpPayload, InetSocketAddress src, InetSocketAddress dst);
    }

    private final int        localPort;
    private final VpnService vpnService;

    private final InetSocketAddress realServer =
        new InetSocketAddress(BuildConfig.SERVER_HOST, BuildConfig.SERVER_PORT);

    private EventLoopGroup bossGroup;
    private Channel        serverChannel;
    private Channel        clientChannel;

    private volatile InetSocketAddress gameClientAddress;
    private volatile TunWriter         tunWriter;

    private final ConcurrentHashMap<Long, RakNetSession> sessions   = new ConcurrentHashMap<>();
    private final PacketProcessor                        processor  = new PacketProcessor();

    private volatile boolean running = false;

    public MitmProxy(int localPort, VpnService vpnService) {
        this.localPort  = localPort;
        this.vpnService = vpnService;
    }

    public void setTunWriter(TunWriter writer) { this.tunWriter = writer; }

    // ── Start / Stop ──────────────────────────────────────────────────────

    public void start() throws Exception {
        if (running) { Log.w(TAG, "Zaten çalışıyor"); return; }

        bossGroup = new NioEventLoopGroup(2, r -> {
            Thread t = new Thread(r, "OxNetty");
            t.setDaemon(true);
            return t;
        });

        // Inbound: oyun → proxy
        serverChannel = new Bootstrap()
            .group(bossGroup)
            .channel(NioDatagramChannel.class)
            .option(ChannelOption.SO_RCVBUF, 2 << 20)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(new ChannelInitializer<NioDatagramChannel>() {
                @Override protected void initChannel(NioDatagramChannel ch) {
                    ch.pipeline().addLast(new InboundHandler());
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", localPort))
            .sync().channel();

        Log.i(TAG, "Server channel: 127.0.0.1:" + localPort);

        // Outbound: proxy → sunucu
        clientChannel = new Bootstrap()
            .group(bossGroup)
            .channel(NioDatagramChannel.class)
            .option(ChannelOption.SO_SNDBUF, 2 << 20)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(new ChannelInitializer<NioDatagramChannel>() {
                @Override protected void initChannel(NioDatagramChannel ch) {
                    ch.pipeline().addLast(new OutboundHandler());
                }
            })
            .bind(0).sync().channel();

        Log.i(TAG, "Client channel port: " +
            ((InetSocketAddress) clientChannel.localAddress()).getPort());

        // FIX 1: VPN protect — reflection ile AbstractNioChannel.ch field'ına eriş
        protectFromVpn();

        running = true;
        Log.i(TAG, "MitmProxy hazır → " + realServer);
    }

    /**
     * FIX 1: VPN routing loop koruması.
     *
     * javaChannel() NioDatagramChannel'da protected — doğrudan çağrılamaz.
     * AbstractNioChannel'ın private "ch" field'ına reflection ile erişiyoruz.
     * Bu field tüm Netty 4.x versiyonlarında stabil.
     *
     * Bu olmadan clientChannel soketi VPN tünelinden geçer →
     * 2b2tpe.org'a giden paket tekrar proxy'ye döner → sonsuz döngü →
     * sunucuya hiç paket ulaşmaz.
     */
    private void protectFromVpn() {
        if (vpnService == null || clientChannel == null) return;
        try {
            java.lang.reflect.Field chField =
                io.netty.channel.nio.AbstractNioChannel.class.getDeclaredField("ch");
            chField.setAccessible(true);
            java.nio.channels.DatagramChannel javaCh =
                (java.nio.channels.DatagramChannel) chField.get(clientChannel);
            boolean ok = vpnService.protect(javaCh.socket());
            Log.i(TAG, "VPN protect: " + (ok ? "✅" : "❌ — routing loop riski!"));
        } catch (NoSuchFieldException e) {
            Log.e(TAG, "VPN protect: 'ch' field yok — " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "VPN protect hata: " + e.getMessage(), e);
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        sessions.clear();
        closeChannel(serverChannel); serverChannel = null;
        closeChannel(clientChannel); clientChannel = null;
        if (bossGroup != null) {
            try { bossGroup.shutdownGracefully().sync(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            bossGroup = null;
        }
        Log.i(TAG, "MitmProxy durduruldu");
    }

    private void closeChannel(Channel ch) {
        if (ch == null) return;
        try { ch.close().sync(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public boolean isRunning() { return running; }

    // ── Raw IP entry from TUN ─────────────────────────────────────────────

    public void handleIncomingPacket(byte[] rawIp, int len) {
        if (!running || serverChannel == null) return;
        try {
            int ihl     = (rawIp[0] & 0xF) * 4;
            int srcPort = ((rawIp[ihl]     & 0xFF) << 8) | (rawIp[ihl + 1] & 0xFF);
            int dstPort = ((rawIp[ihl + 2] & 0xFF) << 8) | (rawIp[ihl + 3] & 0xFF);
            int payOff  = ihl + 8;
            int payLen  = len - payOff;
            if (payLen <= 0) return;
            if (dstPort != BuildConfig.SERVER_PORT) return;

            ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(payLen);
            buf.writeBytes(rawIp, payOff, payLen);

            byte[] srcIpB = { rawIp[12], rawIp[13], rawIp[14], rawIp[15] };
            InetSocketAddress src = new InetSocketAddress(
                java.net.InetAddress.getByAddress(srcIpB), srcPort);
            gameClientAddress = src;

            DatagramPacket dp = new DatagramPacket(buf,
                new InetSocketAddress("127.0.0.1", localPort), src);
            serverChannel.pipeline().fireChannelRead(dp);

        } catch (Exception e) {
            Log.e(TAG, "handleIncomingPacket: " + e.getMessage());
        }
    }

    // ── Module injection ──────────────────────────────────────────────────

    public void injectC2S(byte[] payload) {
        if (clientChannel == null || payload == null || !running) return;
        ByteBuf buf = clientChannel.alloc().buffer(payload.length);
        buf.writeBytes(payload);
        clientChannel.writeAndFlush(new DatagramPacket(buf, realServer))
            .addListener(f -> { if (!f.isSuccess()) Log.w(TAG, "injectC2S fail: " + f.cause()); });
    }

    public void injectS2C(byte[] payload) {
        if (tunWriter == null || gameClientAddress == null || payload == null || !running) return;
        tunWriter.write(payload, realServer, gameClientAddress);
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    /** Oyun → 2b2tpe.org */
    private class InboundHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket)) return;
            DatagramPacket dgram = (DatagramPacket) msg;

            InetSocketAddress clientAddr = dgram.sender();
            long key = clientAddr.hashCode() & 0xFFFFFFFFL;
            RakNetSession session = sessions.computeIfAbsent(key,
                k -> new RakNetSession(clientAddr, RakNetSession.Direction.C2S));

            try {
                // FIX 3: decodeAll — tüm frame'leri işle
                List<byte[]> payloads = session.decodeAll(dgram.content());

                if (payloads.isEmpty()) {
                    // ACK/NAK → olduğu gibi ilet
                    forwardRaw(dgram, clientChannel, realServer);
                    return;
                }

                for (byte[] payload : payloads) {
                    byte[] out = processor.processC2S(payload, clientAddr);
                    if (out != null) sendTo(clientChannel, out, realServer);
                }
            } catch (Exception e) {
                Log.e(TAG, "InboundHandler: " + e.getMessage());
            } finally {
                dgram.content().release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.e(TAG, "InboundHandler exc: " + cause.getMessage());
        }
    }

    /** 2b2tpe.org → Oyun (TUN aracılığıyla) */
    private class OutboundHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket)) return;
            DatagramPacket dgram = (DatagramPacket) msg;

            if (gameClientAddress == null || sessions.isEmpty()) {
                dgram.content().release();
                return;
            }

            InetSocketAddress firstClient = sessions.values().iterator().next().getAddr();
            long s2cKey = (firstClient.hashCode() & 0xFFFFFFFFL) + 1L;
            RakNetSession session = sessions.computeIfAbsent(s2cKey,
                k -> new RakNetSession(firstClient, RakNetSession.Direction.S2C));

            try {
                // FIX 3: decodeAll
                List<byte[]> payloads = session.decodeAll(dgram.content());

                if (payloads.isEmpty()) {
                    // ACK/NAK → TUN'a raw yaz
                    writeRawToTun(dgram);
                    return;
                }

                for (byte[] payload : payloads) {
                    byte[] out = processor.processS2C(payload, firstClient);
                    if (out != null && tunWriter != null) {
                        // FIX 2: tunWriter kullan — serverChannel değil
                        tunWriter.write(out, realServer, gameClientAddress);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "OutboundHandler: " + e.getMessage());
            } finally {
                dgram.content().release();
            }
        }

        private void writeRawToTun(DatagramPacket dgram) {
            if (tunWriter == null || gameClientAddress == null) return;
            try {
                dgram.content().resetReaderIndex();
                byte[] raw = new byte[dgram.content().readableBytes()];
                dgram.content().readBytes(raw);
                tunWriter.write(raw, realServer, gameClientAddress);
            } catch (Exception e) {
                Log.e(TAG, "writeRawToTun: " + e.getMessage());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.e(TAG, "OutboundHandler exc: " + cause.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void sendTo(Channel ch, byte[] data, InetSocketAddress dest) {
        if (ch == null || data == null) return;
        ByteBuf buf = ch.alloc().buffer(data.length);
        buf.writeBytes(data);
        ch.writeAndFlush(new DatagramPacket(buf, dest));
    }

    private void forwardRaw(DatagramPacket dgram, Channel ch, InetSocketAddress dest) {
        if (ch == null) return;
        dgram.content().resetReaderIndex();
        ByteBuf fwd = ch.alloc().buffer(dgram.content().readableBytes());
        fwd.writeBytes(dgram.content());
        ch.writeAndFlush(new DatagramPacket(fwd, dest));
    }

    public InetSocketAddress getRealServer()     { return realServer; }
    public InetSocketAddress getGameClientAddr() { return gameClientAddress; }
}
