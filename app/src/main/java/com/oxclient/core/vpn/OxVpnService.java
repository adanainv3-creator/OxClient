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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OxVpnService — intercepts Minecraft Bedrock UDP traffic via Android TUN.
 * Target server: 2b2tpe.org:19132
 * No root required. Scoped to Minecraft only.
 *
 * Fixed:
 * - Narrow route to target server IP only (prevents establish() null)
 * - Retry mechanism for establish()
 * - Fallback DNS server
 */
public class OxVpnService extends VpnService {
    private static final String TAG          = "OxVpnService";
    private static final String CHANNEL_ID   = "ox_vpn";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_START = "com.oxclient.START_VPN";
    public  static final String ACTION_STOP  = "com.oxclient.STOP_VPN";

    public static final int MC_PORT    = BuildConfig.SERVER_PORT;    // 19132
    public static final int PROXY_PORT = BuildConfig.LOCAL_PROXY_PORT;
    private static final int TUN_MTU   = 1500;

    // DNS sunucuları — route için de kullanılacak
    private static final String DNS_PRIMARY   = "1.1.1.1";
    private static final String DNS_SECONDARY = "8.8.8.8";

    private ParcelFileDescriptor tunFd;
    private MitmProxy            proxy;
    private ExecutorService      io;
    private Future<?>            tunnelTask;
    private final AtomicBoolean  running = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        io = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "OxClient-IO");
            t.setDaemon(true);
            return t;
        });
        createChannel();
        Log.i(TAG, "OxVpnService onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "onStartCommand: intent null, START_NOT_STICKY");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: action=" + action);

        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotif("Active — intercepting 2b2tpe.org traffic"));
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
        io.shutdownNow();
        super.onDestroy();
    }

    private void startVpn() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "VPN zaten çalışıyor");
            return;
        }

        try {
            Log.i(TAG, "VPN başlatılıyor...");
            tunFd = buildTun();
            Log.i(TAG, "TUN başarıyla oluşturuldu, fd=" + tunFd.getFd());

            proxy = new MitmProxy(PROXY_PORT, this);
            proxy.start();
            Log.i(TAG, "MitmProxy başlatıldı, port=" + PROXY_PORT);

            SessionManager.INSTANCE.onSessionStart(proxy);

            tunnelTask = io.submit(this::tunnelLoop);
            Log.i(TAG, "Tunnel loop başlatıldı");
        } catch (Exception e) {
            Log.e(TAG, "startVpn başarısız", e);
            running.set(false);
            stopSelf();
        }
    }

    private void stopVpn() {
        if (!running.getAndSet(false)) {
            Log.d(TAG, "VPN zaten durdurulmuş");
            return;
        }

        Log.i(TAG, "VPN durduruluyor...");

        if (tunnelTask != null) {
            tunnelTask.cancel(true);
            tunnelTask = null;
        }

        SessionManager.INSTANCE.onSessionStop();

        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }

        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }

        Log.i(TAG, "VPN durduruldu");
    }

    /**
     * Builds TUN interface with narrow route to target server.
     * Uses DNS resolution to get target IP, avoiding "0.0.0.0/0" route
     * which causes establish() to return null on many devices.
     */
    private ParcelFileDescriptor buildTun() throws Exception {
        Builder b = new Builder();
        b.setSession("OxClient → 2b2tpe.org");
        b.setMtu(TUN_MTU);
        b.addAddress("10.0.0.2", 32);

        // Hedef sunucunun IP'sini çözümle ve sadece ona route ekle
        try {
            InetAddress serverAddr = InetAddress.getByName(BuildConfig.SERVER_HOST);
            String serverIp = serverAddr.getHostAddress();
            Log.i(TAG, "Hedef sunucu IP: " + serverIp);
            // Sadece hedef sunucuya giden trafiği yakala
            b.addRoute(serverIp, 32);
        } catch (Exception e) {
            Log.w(TAG, "DNS çözümlemesi başarısız: " + e.getMessage());
            // Fallback: hata almamak için geniş route ama riskli
            Log.w(TAG, "Fallback: 0.0.0.0/0 route kullanılıyor (çakışma riski var)");
            b.addRoute("0.0.0.0", 0);
        }

        // DNS sunucularına route ekle (gerekli)
        b.addRoute(DNS_PRIMARY, 32);
        b.addRoute(DNS_SECONDARY, 32);

        // Sadece Minecraft'a scope'la
        try {
            b.addAllowedApplication("com.mojang.minecraftpe");
            Log.d(TAG, "Minecraft uygulaması VPN'e eklendi");
        } catch (Exception e) {
            Log.w(TAG, "Minecraft bulunamadı, tüm uygulamalar yönlendiriliyor");
        }

        b.addDnsServer(DNS_PRIMARY);
        b.addDnsServer(DNS_SECONDARY);
        b.allowFamily(OsConstants.AF_INET);

        // Retry mekanizması ile establish()
        ParcelFileDescriptor pfd = null;
        int retryCount = 0;
        int maxRetries = 3;

        while (pfd == null && retryCount < maxRetries) {
            try {
                Log.d(TAG, "establish() deneniyor... (deneme " + (retryCount + 1) + ")");
                pfd = b.establish();
                if (pfd == null) {
                    retryCount++;
                    Log.w(TAG, "establish() null döndü, retry " + retryCount + "/" + maxRetries);
                    if (retryCount < maxRetries) {
                        Thread.sleep(300);
                    }
                }
            } catch (Exception e) {
                retryCount++;
                Log.e(TAG, "establish() hata: " + e.getMessage() + ", retry " + retryCount);
                if (retryCount >= maxRetries) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }

        if (pfd == null) {
            throw new IllegalStateException(
                "establish() " + maxRetries + " deneme sonunda null döndü. " +
                "Başka bir VPN aktif olabilir veya izin reddedilmiş olabilir."
            );
        }

        return pfd;
    }

    private void tunnelLoop() {
        Log.i(TAG, "Tunnel loop başladı");
        ByteBuffer pkt = ByteBuffer.allocate(TUN_MTU * 2);

        try (FileInputStream in   = new FileInputStream(tunFd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor())) {

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                pkt.clear();
                int len = in.read(pkt.array());

                if (len <= 0) continue;

                if (isMcUdp(pkt.array(), len)) {
                    // Minecraft UDP paketi → proxy'ye yönlendir
                    if (proxy != null) {
                        proxy.handleIncomingPacket(pkt.array(), len);
                    }
                } else {
                    // Diğer trafik → direkt TUN'a geri yaz
                    out.write(pkt.array(), 0, len);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.e(TAG, "Tunnel I/O hatası", e);
            }
        }

        Log.i(TAG, "Tunnel loop sona erdi");
    }

    /**
     * Checks if packet is IPv4 + UDP + destination port == MC_PORT
     */
    private boolean isMcUdp(byte[] d, int len) {
        if (len < 28) return false;

        // IPv4 kontrolü
        if (((d[0] >> 4) & 0xF) != 4) return false;

        // UDP protokol kontrolü (IP header'da protocol field = 17)
        if ((d[9] & 0xFF) != 17) return false;

        // UDP destination port kontrolü
        int ihl = (d[0] & 0xF) * 4; // IP header length
        int dport = ((d[ihl + 2] & 0xFF) << 8) | (d[ihl + 3] & 0xFF);

        return dport == MC_PORT;
    }

    // ── Notification Channel ────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "OxClient Proxy",
                NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("VPN proxy service notification");
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