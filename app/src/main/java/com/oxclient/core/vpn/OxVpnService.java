package com.oxclient.core.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import com.oxclient.BuildConfig;
import com.oxclient.R;
import com.oxclient.core.proxy.MitmProxy;
import com.oxclient.session.SessionManager;
import com.oxclient.ui.dashboard.DashboardActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OxVpnService — intercepts Minecraft Bedrock UDP traffic via Android TUN.
 * Target server hardcoded: 2b2tpe.org:19132
 * No root required. Scoped to com.mojang.minecraftpe only.
 */
public class OxVpnService extends VpnService {
    private static final String TAG          = "OxVpnService";
    private static final String CHANNEL_ID   = "ox_vpn";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_START = "com.oxclient.START_VPN";
    public  static final String ACTION_STOP  = "com.oxclient.STOP_VPN";

    public static final int MC_PORT    = BuildConfig.SERVER_PORT;
    public static final int PROXY_PORT = BuildConfig.LOCAL_PROXY_PORT;
    private static final int TUN_MTU   = 1500;

    private ParcelFileDescriptor tunFd;
    private MitmProxy            proxy;
    private ExecutorService      io;
    private Future<?>            tunnelTask;
    private final AtomicBoolean  running = new AtomicBoolean(false);

    @Override public void onCreate() {
        super.onCreate();
        io = Executors.newFixedThreadPool(2, r -> { Thread t=new Thread(r,"OxClient-IO"); t.setDaemon(true); return t; });
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_START.equals(intent.getAction())) {
            startForeground(NOTIF_ID, buildNotif("Active — intercepting 2b2tpe.org traffic"));
            startVpn();
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopVpn(); stopSelf();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() { stopVpn(); io.shutdownNow(); super.onDestroy(); }

    private void startVpn() {
        if (running.getAndSet(true)) return;
        try {
            tunFd = buildTun();
            Log.i(TAG, "TUN fd=" + tunFd.getFd());
            proxy = new MitmProxy(PROXY_PORT, this);
            proxy.start();

            // SessionManager callback'ini proxy hazır olduktan sonra çağır
            // proxy.start() exception fırlatmadan tamamlandıysa hazır kabul edilir
            SessionManager.INSTANCE.onSessionStart(proxy);

            tunnelTask = io.submit(this::tunnelLoop);
        } catch (Exception e) {
            Log.e(TAG, "startVpn failed", e);
            running.set(false);
            stopSelf();
        }
    }

    private void stopVpn() {
        if (!running.getAndSet(false)) return;
        if (tunnelTask  != null) { tunnelTask.cancel(true); tunnelTask = null; }
        SessionManager.INSTANCE.onSessionStop();
        if (proxy != null) { proxy.stop(); proxy = null; }
        if (tunFd != null) { try { tunFd.close(); } catch (IOException ignored) {} tunFd = null; }
    }

    private ParcelFileDescriptor buildTun() throws Exception {
        Builder b = new Builder();
        b.setSession("OxClient → 2b2tpe.org");
        b.setMtu(TUN_MTU);
        b.addAddress("10.0.0.2", 32);

        // Tüm trafiği değil, sadece hedef sunucuya giden trafiği yakala
        // 2b2tpe.org IP'sini DNS çözümlemesiyle al
        try {
            java.net.InetAddress serverAddr = java.net.InetAddress.getByName(BuildConfig.SERVER_HOST);
            String serverIp = serverAddr.getHostAddress();
            b.addRoute(serverIp, 32);
        } catch (Exception e) {
            // Fallback: DNS çözümlenemezse geniş route ama sorun çıkabilir
            Log.w(TAG, "DNS resolution failed, using fallback route");
            b.addRoute("0.0.0.0", 0);
        }

        // DNS için gerekli route
        b.addRoute("1.1.1.1", 32);

        try {
            b.addAllowedApplication("com.mojang.minecraftpe");
        } catch (Exception e) {
            Log.w(TAG, "Minecraft not found, routing all");
        }

        b.addDnsServer("1.1.1.1");
        b.addDnsServer("8.8.8.8"); // Yedek DNS ekle
        b.allowFamily(OsConstants.AF_INET);

        // Retry mekanizması ekle
        ParcelFileDescriptor pfd = null;
        int retryCount = 0;
        while (pfd == null && retryCount < 3) {
            try {
                pfd = b.establish();
                if (pfd == null) {
                    retryCount++;
                    Log.w(TAG, "establish() returned null, retry " + retryCount);
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                if (retryCount >= 2) throw e;
                retryCount++;
                Thread.sleep(300);
            }
        }

        if (pfd == null) {
            throw new IllegalStateException("establish() returned null after retries");
        }
        return pfd;
    }

    private void tunnelLoop() {
        Log.i(TAG, "Tunnel loop started");
        ByteBuffer pkt = ByteBuffer.allocate(TUN_MTU * 2);
        try (FileInputStream in   = new FileInputStream(tunFd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor())) {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                pkt.clear();
                int len = in.read(pkt.array());
                if (len <= 0) continue;
                if (isMcUdp(pkt.array(), len)) {
                    if (proxy != null) proxy.handleIncomingPacket(pkt.array(), len);
                } else {
                    out.write(pkt.array(), 0, len);
                }
            }
        } catch (IOException e) { if (running.get()) Log.e(TAG, "Tunnel I/O error", e); }
        Log.i(TAG, "Tunnel loop ended");
    }

    /** IPv4 + UDP + dstPort == MC_PORT */
    private boolean isMcUdp(byte[] d, int len) {
        if (len < 28) return false;
        if (((d[0]>>4)&0xF) != 4) return false;
        if ((d[9]&0xFF) != 17) return false;
        int ihl = (d[0]&0xF)*4;
        int dport = ((d[ihl+2]&0xFF)<<8)|(d[ihl+3]&0xFF);
        return dport == MC_PORT;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,"OxClient Proxy",NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this,0,
            new Intent(this, DashboardActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ox_logo)
            .setContentTitle("OxClient")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
