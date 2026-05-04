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
import com.oxclient.ui.dashboard.DashboardActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OxVpnService — BASİT TEST VERSİYONU
 * Sadece VPN tunnel kurar, proxy YOK.
 * Amaç: VPN'in crash olmadan başladığını doğrulamak.
 */
public class OxVpnService extends VpnService {
    private static final String TAG          = "OxVpnService";
    private static final String CHANNEL_ID   = "ox_vpn";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_START = "com.oxclient.START_VPN";
    public  static final String ACTION_STOP  = "com.oxclient.STOP_VPN";

    private static final int TUN_MTU = 1500;

    private static final String DNS_PRIMARY   = "1.1.1.1";
    private static final String DNS_SECONDARY = "8.8.8.8";

    private ParcelFileDescriptor tunFd;
    private ExecutorService      io;
    private Future<?>            tunnelTask;
    private final AtomicBoolean  running = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OxClient-IO");
            t.setDaemon(true);
            return t;
        });
        createChannel();
        Log.i(TAG, "OxVpnService onCreate (TEST MODU - Proxy YOK)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "onStartCommand: intent null");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: action=" + action);

        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotif("OxClient VPN (test modu)"));
            startVpn();
        } else if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        stopVpn();
        if (io != null) {
            io.shutdownNow();
        }
        super.onDestroy();
    }

    // ── BASİT VPN BAŞLATMA (PROXY YOK) ──────────────────────────────────

    private void startVpn() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "VPN zaten çalışıyor");
            return;
        }

        try {
            Log.i(TAG, "═══ VPN BAŞLATILIYOR (PROXY YOK) ═══");
            
            // Sadece TUN oluştur
            tunFd = buildTun();
            Log.i(TAG, "✅ TUN oluşturuldu, fd=" + tunFd.getFd());
            
            // Sadece tunnel loop başlat (paketleri olduğu gibi geri yaz)
            tunnelTask = io.submit(this::simpleTunnelLoop);
            Log.i(TAG, "✅ Tunnel loop başladı");
            Log.i(TAG, "═══ VPN AKTİF (PROXY YOK) ═══");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ VPN başlatılamadı: " + e.getMessage(), e);
            
            // Temizlik
            if (tunFd != null) {
                try { tunFd.close(); } catch (IOException ex) {}
                tunFd = null;
            }
            running.set(false);
            stopSelf();
        }
    }

    private void stopVpn() {
        if (!running.getAndSet(false)) {
            return;
        }

        Log.i(TAG, "VPN durduruluyor...");

        if (tunnelTask != null) {
            tunnelTask.cancel(true);
            tunnelTask = null;
        }

        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException e) {}
            tunFd = null;
        }

        Log.i(TAG, "VPN durduruldu");
    }

    // ── TUN OLUŞTURMA (BASİT) ──────────────────────────────────────────

    private ParcelFileDescriptor buildTun() throws Exception {
        Builder b = new Builder();
        b.setSession("OxClient Test");
        b.setMtu(TUN_MTU);
        b.addAddress("10.0.0.2", 32);

        // Hedef sunucu IP'sini çözümle
        try {
            InetAddress serverAddr = InetAddress.getByName(BuildConfig.SERVER_HOST);
            String serverIp = serverAddr.getHostAddress();
            Log.i(TAG, "Hedef IP: " + serverIp);
            b.addRoute(serverIp, 32);
        } catch (Exception e) {
            Log.w(TAG, "DNS başarısız, fallback route");
            b.addRoute("0.0.0.0", 0);
        }

        b.addRoute(DNS_PRIMARY, 32);
        b.addRoute(DNS_SECONDARY, 32);
        b.addDnsServer(DNS_PRIMARY);
        b.addDnsServer(DNS_SECONDARY);
        b.allowFamily(OsConstants.AF_INET);

        // Minecraft'a scople
        try {
            b.addAllowedApplication("com.mojang.minecraftpe");
        } catch (Exception e) {
            Log.w(TAG, "Minecraft bulunamadı");
        }

        // Tek denemede establish et
        ParcelFileDescriptor pfd = b.establish();
        if (pfd == null) {
            // Bir daha dene
            Thread.sleep(500);
            pfd = b.establish();
        }
        if (pfd == null) {
            throw new Exception("TUN establish() null döndü - başka VPN aktif olabilir");
        }
        return pfd;
    }

    // ── BASİT TUNNEL LOOP (PAKETLERİ GERİ YAZ) ─────────────────────────

    private void simpleTunnelLoop() {
        Log.i(TAG, "Simple tunnel loop başladı");
        ByteBuffer pkt = ByteBuffer.allocate(TUN_MTU * 2);

        try (FileInputStream in   = new FileInputStream(tunFd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor())) {

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                pkt.clear();
                int len = in.read(pkt.array());
                if (len > 0) {
                    // Geleni direkt geri yaz (bypass)
                    out.write(pkt.array(), 0, len);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.e(TAG, "Tunnel I/O hatası: " + e.getMessage());
            }
        }
        Log.i(TAG, "Simple tunnel loop bitti");
    }

    // ── NOTIFICATION ───────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "OxClient VPN",
                NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        PendingIntent pi = PendingIntent.getActivity(
            this, 0,
            new Intent(this, DashboardActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ox_logo)
            .setContentTitle("OxClient")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}