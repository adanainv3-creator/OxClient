package com.oxclient.core.proxy;

import android.net.VpnService;
import android.util.Log;

import com.oxclient.BuildConfig;
import com.oxclient.core.raknet.RakNetSession;
import com.oxclient.events.PacketEvent;
import com.oxclient.events.PacketEventBus;

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
 * Packet flow:
 *   Game → TUN → handleIncomingPacket()
 *       → InboundHandler → RakNetSession.decodeAll()
 *       → PacketProcessor.processC2S()
 *       → clientChannel → 2b2tpe.org:19132
 *
 *   2b2tpe.org → clientChannel → OutboundHandler
 *       → RakNetSession.decodeAll()
 *       → PacketProcessor.processS2C()
 *       → tunWriter.write() → TUN → Game
 *
 * FIXES:
 *   1. protectFromVpn() artık reflection yerine doğrudan NioDatagramChannel cast kullanıyor.
 *      Önceki reflection kodu NoSuchMethodException atıp sessizce geçiyordu;
 *      clientChannel korunmadan kalınca routing loop oluşuyor ve bağlantı kesiliyordu.
 *
 *   2. OutboundHandler artık serverChannel'a yazmak yerine tunWriter callback'ini kullanıyor.
 *      Bu sayede sunucudan gelen paketler doğru IP/UDP başlıkları ile TUN'a iletilir.
 *
 *   3. decode() yerine decodeAll() kullanılarak bir RakNet datagramındaki tüm
 *      frame'ler işleniyor (handshake paket kaybı giderildi).
 */
public class MitmProxy {
    private static final String TAG = "MitmProxy";

    /** Proxy → Game TUN yazma callback interface */
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

    private final ConcurrentHashMap<Long, RakNetSession> sessions = new ConcurrentHashMap<>();
    private final PacketProcessor processor = new PacketProcessor();

    private volatile boolean running = false;

    public MitmProxy(int localPort, VpnService vpnService) {
        this.localPort  = localPort;
        this.vpnService = vpnService;
    }

    /** OxVpnService tarafından start() öncesinde set edilmeli. */
    public void setTunWriter(TunWriter writer) {
        this.tunWriter = writer;
    }

    // ── Start / Stop ──────────────────────────────────────────────────────

    public void start() throws Exception {
        if (running) {
            Log.w(TAG, "MitmProxy zaten çalışıyor");
            return;
        }

        Log.i(TAG, "MitmProxy başlatılıyor...");

        bossGroup = new NioEventLoopGroup(2, r -> {
            Thread t = new Thread(r, "OxNetty");
            t.setDaemon(true);
            return t;
        });

        // Inbound: game → proxy (localhost dinler, TUN'dan paket alır)
        serverChannel = new Bootstrap()
            .group(bossGroup)
            .channel(NioDatagramChannel.class)
            .option(ChannelOption.SO_RCVBUF, 2 << 20)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(new ChannelInitializer<NioDatagramChannel>() {
                @Override
                protected void initChannel(NioDatagramChannel ch) {
                    ch.pipeline().addLast(new InboundHandler());
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", localPort))
            .sync()
            .channel();

        Log.i(TAG, "Server channel bağlandı: 127.0.0.1:" + localPort);

        // Outbound: proxy → 2b2t.pe
        clientChannel = new Bootstrap()
            .group(bossGroup)
            .channel(NioDatagramChannel.class)
            .option(ChannelOption.SO_SNDBUF, 2 << 20)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(new ChannelInitializer<NioDatagramChannel>() {
                @Override
                protected void initChannel(NioDatagramChannel ch) {
                    ch.pipeline().addLast(new OutboundHandler());
                }
            })
            .bind(0)
            .sync()
            .channel();

        int clientPort = ((InetSocketAddress) clientChannel.localAddress()).getPort();
        Log.i(TAG, "Client channel bağlandı, port: " + clientPort);

        // FIX 1: VPN koruması — doğrudan cast, reflection yok
        protectFromVpn();

        running = true;
        Log.i(TAG, "MitmProxy hazır — local:127.0.0.1:" + localPort + " → " + realServer);
    }

    /**
     * FIX 1: VPN routing loop koruması.
     *
     * Önceki kod reflection ile javaChannel() çağırıyordu.
     * NioDatagramChannel.javaChannel() protected olduğundan NoSuchMethodException
     * fırlatıyordu; catch bloğu sessizce geçiyor, clientChannel korunmadan
     * kalıyordu. Korunmayan socket TUN'a düşünce döngü oluşuyor ve
     * sunucuya hiç paket ulaşmıyordu.
     *
     * Düzeltme: NioDatagramChannel'a doğrudan cast → javaChannel() çağrısı.
     */
    private void protectFromVpn() {
        if (vpnService == null || clientChannel == null) return;
        try {
            NioDatagramChannel ndc = (NioDatagramChannel) clientChannel;
            java.nio.channels.DatagramChannel javaCh = ndc.javaChannel();
            boolean ok = vpnService.protect(javaCh.socket());
            Log.i(TAG, "VPN protect: " + (ok ? "✅ başarılı" : "❌ başarısız — routing loop riski!"));
            if (!ok) {
                Log.e(TAG, "VPN protect başarısız! clientChannel korunmasız. Bağlantı çalışmayabilir.");
            }
        } catch (Exception e) {
            Log.e(TAG, "VPN protect exception: " + e.getMessage(), e);
        }
    }

    public void stop() {
        if (!running) return;

        Log.i(TAG, "MitmProxy durduruluyor");
        running = false;
        sessions.clear();

        closeChannel(serverChannel);
        serverChannel = null;

        closeChannel(clientChannel);
        clientChannel = null;

        if (bossGroup != null) {
            try {
                bossGroup.shutdownGracefully().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

    /**
     * OxVpnService tunnelLoop'tan çağrılır.
     * Ham IPv4 paketi parse edip UDP payload'ını serverChannel'a iletir.
     */
    public void handleIncomingPacket(byte[] rawIp, int len) {
        if (!running || serverChannel == null) return;

        try {
            int ihl        = (rawIp[0] & 0xF) * 4;
            int srcPort    = ((rawIp[ihl]     & 0xFF) << 8) | (rawIp[ihl + 1] & 0xFF);
            int dstPort    = ((rawIp[ihl + 2] & 0xFF) << 8) | (rawIp[ihl + 3] & 0xFF);
            int payloadOff = ihl + 8;
            int payloadLen = len - payloadOff;
            if (payloadLen <= 0) return;

            // Sadece MC portuna giden paketleri proxy'ye yönlendir
            if (dstPort != BuildConfig.SERVER_PORT) return;

            ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(payloadLen);
            buf.writeBytes(rawIp, payloadOff, payloadLen);

            byte[] srcIpB = { rawIp[12], rawIp[13], rawIp[14], rawIp[15] };
            InetSocketAddress src = new InetSocketAddress(
                java.net.InetAddress.getByAddress(srcIpB), srcPort);
            gameClientAddress = src;

            // serverChannel pipeline'ına enjekte et
            DatagramPacket dp = new DatagramPacket(buf,
                new InetSocketAddress("127.0.0.1", localPort), src);
            serverChannel.pipeline().fireChannelRead(dp);

        } catch (Exception e) {
            Log.e(TAG, "handleIncomingPacket error: " + e.getMessage());
        }
    }

    // ── Packet injection (used by modules) ────────────────────────────────

    public void injectC2S(byte[] payload) {
        if (clientChannel == null || payload == null || !running) return;
        ByteBuf buf = clientChannel.alloc().buffer(payload.length);
        buf.writeBytes(payload);
        clientChannel.writeAndFlush(new DatagramPacket(buf, realServer))
            .addListener(f -> { if (!f.isSuccess()) Log.w(TAG, "injectC2S fail: " + f.cause()); });
    }

    public void injectS2C(byte[] payload) {
        if (tunWriter == null || gameClientAddress == null || payload == null || !running) return;
        InetSocketAddress serverAddr = realServer;
        tunWriter.write(payload, serverAddr, gameClientAddress);
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    /** Game → 2b2t.pe */
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
                // FIX 3: decodeAll() — bir datagramdaki tüm frame'leri işle
                List<byte[]> payloads = session.decodeAll(dgram.content());

                if (payloads.isEmpty()) {
                    // ACK/NAK veya decode edilemeyen paket — olduğu gibi ilet
                    forwardRaw(dgram, clientChannel, realServer);
                    return;
                }

                for (byte[] payload : payloads) {
                    byte[] processed = processor.processC2S(payload, clientAddr);
                    if (processed != null) {
                        sendTo(clientChannel, processed, realServer);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "InboundHandler error: " + e.getMessage());
            } finally {
                dgram.content().release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.e(TAG, "InboundHandler exception: " + cause.getMessage());
        }
    }

    /** 2b2t.pe → Game (TUN aracılığıyla) */
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
                // FIX 3: decodeAll() — tüm frame'leri işle
                List<byte[]> payloads = session.decodeAll(dgram.content());

                if (payloads.isEmpty()) {
                    // ACK/NAK — raw olarak TUN'a yaz
                    writeRawToTun(dgram);
                    return;
                }

                for (byte[] payload : payloads) {
                    byte[] processed = processor.processS2C(payload, firstClient);
                    if (processed != null) {
                        // FIX 2: serverChannel yerine tunWriter kullan
                        // Önceki kod serverChannel'a yazıyordu; bu TUN'a doğru
                        // IP başlığı olmadan yazıyordu, oyun paketi tanımıyordu.
                        if (tunWriter != null) {
                            tunWriter.write(processed, realServer, gameClientAddress);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "OutboundHandler error: " + e.getMessage());
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
                Log.e(TAG, "writeRawToTun error: " + e.getMessage());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.e(TAG, "OutboundHandler exception: " + cause.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void sendTo(Channel ch, byte[] data, InetSocketAddress dest) {
        if (ch == null || data == null || dest == null) return;
        ByteBuf buf = ch.alloc().buffer(data.length);
        buf.writeBytes(data);
        ch.writeAndFlush(new DatagramPacket(buf, dest));
    }

    private void forwardRaw(DatagramPacket dgram, Channel targetCh, InetSocketAddress dest) {
        if (targetCh == null || dest == null) return;
        dgram.content().resetReaderIndex();
        ByteBuf fwd = targetCh.alloc().buffer(dgram.content().readableBytes());
        fwd.writeBytes(dgram.content());
        targetCh.writeAndFlush(new DatagramPacket(fwd, dest));
    }

    public InetSocketAddress getRealServer()      { return realServer; }
    public InetSocketAddress getGameClientAddr()  { return gameClientAddress; }
}
