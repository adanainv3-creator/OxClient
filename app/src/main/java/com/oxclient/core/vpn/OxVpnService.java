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
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * OxVpnService — Minecraft Bedrock UDP trafiğini yakalar ve MITM proxy'ye yönlendirir.
 */
public class OxVpnService extends VpnService {
    private static final String TAG          = "OxVpnService";
    private static final String CHANNEL_ID   = "ox_vpn";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_START = "com.oxclient.START_VPN";
    public  static final String ACTION_STOP  = "com.oxclient.STOP_VPN";

    private static final int TUN_MTU   = 1500;
    private static final int MC_PORT   = BuildConfig.SERVER_PORT;
    private static final int PROXY_PORT = BuildConfig.LOCAL_PROXY_PORT;

    private ParcelFileDescriptor tunFd;
    private MitmProxy            proxy;
    private Thread               tunnelThread;
    private volatile boolean     running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Log.i(TAG, "OxVpnService onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        Log.d(TAG, "action=" + action);

        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotif("OxClient aktif - 2b2tpe.org"));
            startVpn();
        } else if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
        }

        return START_STICKY;
    }

    private void startVpn() {
        if (running) {
            Log.w(TAG, "VPN zaten çalışıyor");
            return;
        }

        try {
            Log.i(TAG, "═══ VPN BAŞLATILIYOR ═══");

            // 1. TUN interface
            tunFd = buildTun();
            Log.i(TAG, "✅ TUN oluşturuldu");

            // 2. MITM Proxy - TRY-CATCH içinde
            try {
                proxy = new MitmProxy(PROXY_PORT, this);
                proxy.start();
                Log.i(TAG, "✅ Proxy başladı, port=" + PROXY_PORT);
                SessionManager.INSTANCE.onSessionStart(proxy);
            } catch (Exception e) {
                Log.e(TAG, "❌ Proxy başlayamadı: " + e.getMessage());
                // Proxy'siz devam et - tunnel yine de çalışır
                proxy = null;
            }

            // 3. Tunnel loop
            running = true;
            tunnelThread = new Thread(this::tunnelLoop, "OxTunnel");
            tunnelThread.setDaemon(true);
            tunnelThread.start();

            Log.i(TAG, "═══ VPN AKTİF ═══");

        } catch (Exception e) {
            Log.e(TAG, "❌ VPN başlatılamadı: " + e.getMessage(), e);
            cleanup();
        }
    }

    private void stopVpn() {
        running = false;

        if (tunnelThread != null) {
            tunnelThread.interrupt();
            try { tunnelThread.join(1000); } catch (InterruptedException e) {}
            tunnelThread = null;
        }

        SessionManager.INSTANCE.onSessionStop();

        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }

        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException e) {}
            tunFd = null;
        }

        Log.i(TAG, "VPN durduruldu");
    }

    private void cleanup() {
        if (proxy != null) {
            try { proxy.stop(); } catch (Exception e) {}
            proxy = null;
        }
        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException e) {}
            tunFd = null;
        }
        running = false;
        stopSelf();
    }

    // ── TUN ──────────────────────────────────────────────────────────────

    private ParcelFileDescriptor buildTun() throws Exception {
        Builder b = new Builder();
        b.setSession("OxClient");
        b.setMtu(TUN_MTU);
        b.addAddress("10.0.0.2", 32);

        try {
            InetAddress addr = InetAddress.getByName(BuildConfig.SERVER_HOST);
            b.addRoute(addr.getHostAddress(), 32);
        } catch (Exception e) {
            b.addRoute("0.0.0.0", 0);
        }

        b.addRoute("1.1.1.1", 32);
        b.addDnsServer("1.1.1.1");
        b.addDnsServer("8.8.8.8");
        b.allowFamily(OsConstants.AF_INET);

        try {
            b.addAllowedApplication("com.mojang.minecraftpe");
        } catch (Exception e) {}

        ParcelFileDescriptor pfd = b.establish();
        if (pfd == null) {
            Thread.sleep(300);
            pfd = b.establish();
        }
        if (pfd == null) throw new Exception("TUN establish null");
        return pfd;
    }

    // ── Tunnel Loop ──────────────────────────────────────────────────────

    private void tunnelLoop() {
        Log.i(TAG, "Tunnel başladı");
        ByteBuffer pkt = ByteBuffer.allocate(TUN_MTU * 2);

        try (FileInputStream in   = new FileInputStream(tunFd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor())) {

            while (running) {
                pkt.clear();
                int len = in.read(pkt.array());
                if (len <= 0) continue;

                if (isMcUdp(pkt.array(), len) && proxy != null) {
                    proxy.handleIncomingPacket(pkt.array(), len);
                } else {
                    out.write(pkt.array(), 0, len);
                }
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "Tunnel hata: " + e.getMessage());
        }
        Log.i(TAG, "Tunnel bitti");
    }

    private boolean isMcUdp(byte[] d, int len) {
        if (len < 28) return false;
        if (((d[0] >> 4) & 0xF) != 4) return false;
        if ((d[9] & 0xFF) != 17) return false;
        int ihl = (d[0] & 0xF) * 4;
        int dport = ((d[ihl + 2] & 0xFF) << 8) | (d[ihl + 3] & 0xFF);
        return dport == MC_PORT;
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    // ── Notification ─────────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "OxClient", NotificationManager.IMPORTANCE_LOW)
            );
        }
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ox_logo)
            .setContentTitle("OxClient")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, DashboardActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}