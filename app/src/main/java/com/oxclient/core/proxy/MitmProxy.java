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

    public MitmProxy(int localPort, VpnService vpnService) {
        this.localPort  = localPort;
        this.vpnService = vpnService;
    }

    // ── Start / Stop ──────────────────────────────────────────────────────

    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(2, r -> {
            Thread t = new Thread(r, "OxNetty-" + System.nanoTime());
            t.setDaemon(true); return t;
        });

        // Inbound: game → proxy
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

        // Outbound: proxy → 2b2t.pe
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

        // Protect upstream socket from VPN routing loop
        // javaChannel() is protected in NioDatagramChannel, so we use reflection to access it
        if (vpnService != null) {
            try {
                java.lang.reflect.Method m =
                    io.netty.channel.nio.AbstractNioChannel.class
                        .getDeclaredMethod("javaChannel");
                m.setAccessible(true);
                DatagramChannel dc = (DatagramChannel) m.invoke(clientChannel);
                boolean ok = vpnService.protect(dc.socket());
                if (!ok) Log.w(TAG, "protect() failed — routing loop risk");
            } catch (Exception e) {
                Log.e(TAG, "protect() reflection failed", e);
            }
        }

        Log.i(TAG, "MitmProxy ready — local:127.0.0.1:" + localPort + " → " + realServer);
    }

    public void stop() {
        Log.i(TAG, "MitmProxy stopping");
        sessions.clear();
        if (serverChannel != null) serverChannel.close().awaitUninterruptibly();
        if (clientChannel != null) clientChannel.close().awaitUninterruptibly();
        if (bossGroup     != null) bossGroup.shutdownGracefully().awaitUninterruptibly();
    }

    // ── Raw IP entry from TUN ─────────────────────────────────────────────

    public void handleIncomingPacket(byte[] rawIp, int len) {
        int ihl = (rawIp[0] & 0xF) * 4;
        int srcPort = ((rawIp[ihl] & 0xFF) << 8) | (rawIp[ihl + 1] & 0xFF);
        int payloadOff = ihl + 8;
        int payloadLen = len - payloadOff;
        if (payloadLen <= 0) return;

        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(payloadLen);
        buf.writeBytes(rawIp, payloadOff, payloadLen);

        try {
            byte[] srcIpB = { rawIp[12], rawIp[13], rawIp[14], rawIp[15] };
            InetSocketAddress src = new InetSocketAddress(
                java.net.InetAddress.getByAddress(srcIpB), srcPort);
            gameClientAddress = src;

            DatagramPacket dp = new DatagramPacket(buf,
                new InetSocketAddress("127.0.0.1", localPort), src);
            serverChannel.pipeline().fireChannelRead(dp);
        } catch (Exception e) {
            Log.e(TAG, "handleIncomingPacket error", e);
            buf.release();
        }
    }

    // ── Packet injection (used by modules) ────────────────────────────────

    /** Inject a fabricated packet Client→Server (to 2b2t.pe). */
    public void injectC2S(byte[] payload) {
        if (clientChannel == null || payload == null) return;
        ByteBuf buf = clientChannel.alloc().buffer(payload.length);
        buf.writeBytes(payload);
        clientChannel.writeAndFlush(new DatagramPacket(buf, realServer))
            .addListener(f -> { if (!f.isSuccess()) Log.w(TAG, "injectC2S fail: " + f.cause()); });
    }

    /** Inject a fabricated packet Server→Client (to game). */
    public void injectS2C(byte[] payload) {
        if (serverChannel == null || gameClientAddress == null || payload == null) return;
        ByteBuf buf = serverChannel.alloc().buffer(payload.length);
        buf.writeBytes(payload);
        serverChannel.writeAndFlush(new DatagramPacket(buf, gameClientAddress))
            .addListener(f -> { if (!f.isSuccess()) Log.w(TAG, "injectS2C fail: " + f.cause()); });
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private class InboundHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket dgram)) return;
            InetSocketAddress clientAddr = dgram.sender();
            long key = clientAddr.hashCode() & 0xFFFFFFFFL;
            RakNetSession session = sessions.computeIfAbsent(key,
                k -> new RakNetSession(clientAddr, RakNetSession.Direction.C2S));
            try {
                byte[] payload = session.decode(dgram.content());
                if (payload == null) {
                    // Forward control frames (ACK/NAK) as-is
                    forward(ctx, dgram, clientChannel, realServer);
                    return;
                }
                byte[] processed = processor.processC2S(payload, clientAddr);
                if (processed == null) return;
                sendTo(clientChannel, processed, realServer);
            } catch (Exception e) {
                Log.e(TAG, "InboundHandler error", e);
            } finally {
                dgram.content().release();
            }
        }
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.e(TAG, "InboundHandler exception", cause);
        }
    }

    private class OutboundHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket dgram)) return;
            if (gameClientAddress == null) { dgram.content().release(); return; }
            if (sessions.isEmpty()) { dgram.content().release(); return; }
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
                Log.e(TAG, "OutboundHandler error", e);
            } finally {
                dgram.content().release();
            }
        }
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.e(TAG, "OutboundHandler exception", cause);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void sendTo(Channel ch, byte[] data, InetSocketAddress dest) {
        ByteBuf buf = ch.alloc().buffer(data.length);
        buf.writeBytes(data);
        ch.writeAndFlush(new DatagramPacket(buf, dest));
    }

    private void forward(ChannelHandlerContext ctx, DatagramPacket dgram,
                         Channel targetCh, InetSocketAddress dest) {
        ByteBuf fwd = ctx.alloc().buffer(dgram.content().readableBytes());
        dgram.content().resetReaderIndex();
        fwd.writeBytes(dgram.content());
        targetCh.writeAndFlush(new DatagramPacket(fwd, dest));
    }

    public InetSocketAddress getRealServer()     { return realServer; }
    public InetSocketAddress getGameClientAddr() { return gameClientAddress; }
}
