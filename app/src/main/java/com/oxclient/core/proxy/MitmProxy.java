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
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MitmProxy — Netty NIO UDP MITM proxy.
 *
 * Hardcoded target: 2b2tpe.org:19132
 *
 * Packet flow:
 *   Game → TUN → handleIncomingPacket()
 *       → serverChannel → InboundHandler
 *       → RakNetSession.decode()
 *       → PacketProcessor.processC2S()
 *       → PacketEventBus (modules intercept)
 *       → clientChannel → 2b2tpe.org:19132
 *
 *   2b2tpe.org → clientChannel → OutboundHandler
 *       → PacketProcessor.processS2C()
 *       → PacketEventBus
 *       → serverChannel → Game
 */
public class MitmProxy {
    private static final String TAG = "MitmProxy";

    private final int        localPort;
    private final VpnService vpnService;

    // 2b2t.pe target — hardcoded
    private final InetSocketAddress realServer =
        new InetSocketAddress(BuildConfig.SERVER_HOST, BuildConfig.SERVER_PORT);

    private EventLoopGroup bossGroup;
    private Channel        serverChannel;
    private Channel        clientChannel;

    private volatile InetSocketAddress gameClientAddress;

    private final ConcurrentHashMap<Long, RakNetSession> sessions = new ConcurrentHashMap<>();
    private final PacketProcessor processor = new PacketProcessor();

    // VPN koruması ve çalışma durumu takibi
    private volatile boolean vpnProtected = false;
    private volatile boolean running = false;

    public MitmProxy(int localPort, VpnService vpnService) {
        this.localPort  = localPort;
        this.vpnService = vpnService;
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

        // Inbound: game → proxy
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

        // VPN koruması — safe reflection
        protectFromVpn();

        running = true;
        Log.i(TAG, "MitmProxy hazır — local:127.0.0.1:" + localPort + " → " + realServer);
    }

    /**
     * VPN routing loop'u engellemek için client socket'i koru.
     * Reflection yerine güvenli metod çağrısı kullan.
     */
    private void protectFromVpn() {
        if (vpnService == null || vpnProtected || clientChannel == null) return;

        try {
            // NioDatagramChannel → javaChannel() metoduna güvenli erişim
            java.nio.channels.DatagramChannel javaChannel = 
                (java.nio.channels.DatagramChannel) clientChannel
                    .getClass()
                    .getMethod("javaChannel")
                    .invoke(clientChannel);

            if (javaChannel != null) {
                boolean ok = vpnService.protect(javaChannel.socket());
                if (ok) {
                    vpnProtected = true;
                    Log.i(TAG, "VPN koruması başarılı");
                } else {
                    Log.w(TAG, "VPN koruması başarısız — routing loop riski var");
                }
            }
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "javaChannel() metodu bulunamadı, VPN koruması atlanıyor");
        } catch (Exception e) {
            Log.e(TAG, "VPN koruması sırasında hata: " + e.getMessage());
            // Crash yapma, devam et
        }
    }

    public void stop() {
        if (!running) return;

        Log.i(TAG, "MitmProxy durduruluyor");
        running = false;
        sessions.clear();

        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverChannel = null;
        }

        if (clientChannel != null) {
            try {
                clientChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            clientChannel = null;
        }

        if (bossGroup != null) {
            try {
                bossGroup.shutdownGracefully().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            bossGroup = null;
        }

        vpnProtected = false;
        Log.i(TAG, "MitmProxy durduruldu");
    }

    public boolean isRunning() {
        return running;
    }

    // ── Raw IP entry from TUN ─────────────────────────────────────────────

    public void handleIncomingPacket(byte[] rawIp, int len) {
        if (!running || serverChannel == null) return;

        try {
            int ihl = (rawIp[0] & 0xF) * 4;
            int srcPort = ((rawIp[ihl] & 0xFF) << 8) | (rawIp[ihl + 1] & 0xFF);
            int payloadOff = ihl + 8;
            int payloadLen = len - payloadOff;
            if (payloadLen <= 0) return;

            ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(payloadLen);
            buf.writeBytes(rawIp, payloadOff, payloadLen);

            byte[] srcIpB = { rawIp[12], rawIp[13], rawIp[14], rawIp[15] };
            InetSocketAddress src = new InetSocketAddress(
                java.net.InetAddress.getByAddress(srcIpB), srcPort);
            gameClientAddress = src;

            DatagramPacket dp = new DatagramPacket(buf,
                new InetSocketAddress("127.0.0.1", localPort), src);
            serverChannel.pipeline().fireChannelRead(dp);
        } catch (Exception e) {
            Log.e(TAG, "handleIncomingPacket error: " + e.getMessage());
        }
    }

    // ── Packet injection (used by modules) ────────────────────────────────

    /** Inject a fabricated packet Client→Server (to 2b2t.pe). */
    public void injectC2S(byte[] payload) {
        if (clientChannel == null || payload == null || !running) return;
        ByteBuf buf = clientChannel.alloc().buffer(payload.length);
        buf.writeBytes(payload);
        clientChannel.writeAndFlush(new DatagramPacket(buf, realServer))
            .addListener(f -> {
                if (!f.isSuccess()) Log.w(TAG, "injectC2S fail: " + f.cause());
            });
    }

    /** Inject a fabricated packet Server→Client (to game). */
    public void injectS2C(byte[] payload) {
        if (serverChannel == null || gameClientAddress == null || payload == null || !running) return;
        ByteBuf buf = serverChannel.alloc().buffer(payload.length);
        buf.writeBytes(payload);
        serverChannel.writeAndFlush(new DatagramPacket(buf, gameClientAddress))
            .addListener(f -> {
                if (!f.isSuccess()) Log.w(TAG, "injectS2C fail: " + f.cause());
            });
    }

    // ── Handlers ─────────────────────────────────────────────────────────

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
                byte[] payload = session.decode(dgram.content());
                if (payload == null) {
                    forward(ctx, dgram, clientChannel, realServer);
                    return;
                }
                byte[] processed = processor.processC2S(payload, clientAddr);
                if (processed == null) return;
                sendTo(clientChannel, processed, realServer);
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
            RakNetSession session = sessions.computeIfAbsent(
                (firstClient.hashCode() & 0xFFFFFFFFL) + 1L,
                k -> new RakNetSession(firstClient, RakNetSession.Direction.S2C));
            try {
                byte[] payload = session.decode(dgram.content());
                if (payload == null) {
                    forward(ctx, dgram, serverChannel, gameClientAddress);
                    return;
                }
                byte[] processed = processor.processS2C(payload, firstClient);
                if (processed == null) return;
                sendTo(serverChannel, processed, gameClientAddress);
            } catch (Exception e) {
                Log.e(TAG, "OutboundHandler error: " + e.getMessage());
            } finally {
                dgram.content().release();
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

    private void forward(ChannelHandlerContext ctx, DatagramPacket dgram,
                         Channel targetCh, InetSocketAddress dest) {
        if (targetCh == null || dest == null) return;
        ByteBuf fwd = ctx.alloc().buffer(dgram.content().readableBytes());
        dgram.content().resetReaderIndex();
        fwd.writeBytes(dgram.content());
        targetCh.writeAndFlush(new DatagramPacket(fwd, dest));
    }

    public InetSocketAddress getRealServer() {
        return realServer;
    }

    public InetSocketAddress getGameClientAddr() {
        return gameClientAddress;
    }
}