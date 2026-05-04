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
 * Changelog v3:
 * - Narrow route to target server IP only (prevents establish() null)
 * - Retry mechanism for establish() with exponential backoff
 * - Fallback DNS server (8.8.8.8)
 * - MitmProxy.isRunning() check after start()
 * - Graceful failure handling in startVpn()
 */
public class OxVpnService extends VpnService {
    private static final String TAG          = "OxVpnService";
    private static final String CHANNEL_ID   = "ox_vpn";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_START = "com.oxclient.START_VPN";
    public  static final String ACTION_STOP  = "com.oxclient.STOP_VPN";

    public static final int MC_PORT    = BuildConfig.SERVER_PORT;    // 19132
    public static final int PROXY_PORT = BuildConfig.LOCAL_PROXY_PORT; // 19133
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

    // ── VPN Lifecycle ────────────────────────────────────────────────────

    /**
     * Starts the VPN tunnel and MITM proxy.
     * Fails gracefully if TUN or proxy cannot be established.
     */
    private void startVpn() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "VPN zaten çalışıyor, tekrar başlatma isteği yoksayıldı");
            return;
        }

        try {
            Log.i(TAG, "═══════ VPN BAŞLATILIYOR ═══════");

            // Aşama 1: TUN interface oluştur
            tunFd = buildTun();
            Log.i(TAG, "✓ TUN başarıyla oluşturuldu, fd=" + tunFd.getFd());

            // Aşama 2: MITM proxy başlat
            proxy = new MitmProxy(PROXY_PORT, this);
            proxy.start();

            // Proxy'nin gerçekten çalıştığını doğrula
            if (!proxy.isRunning()) {
                throw new IllegalStateException("MitmProxy.start() çağrıldı fakat isRunning() false döndü");
            }
            Log.i(TAG, "✓ MitmProxy başlatıldı, port=" + PROXY_PORT);

            // Aşama 3: Session manager'a bildir
            SessionManager.INSTANCE.onSessionStart(proxy);
            Log.i(TAG, "✓ SessionManager bilgilendirildi");

            // Aşama 4: Tunnel loop'u başlat
            tunnelTask = io.submit(this::tunnelLoop);
            Log.i(TAG, "✓ Tunnel loop başlatıldı");
            Log.i(TAG, "═══════ VPN AKTİF ═══════");

        } catch (Exception e) {
            Log.e(TAG, "✗ startVpn BAŞARISIZ: " + e.getMessage(), e);
            
            // Başarısız olursa her şeyi temizle
            cleanupOnFailure();
        }
    }

    /**
     * Stops VPN tunnel and releases all resources.
     * Idempotent — safe to call multiple times.
     */
    private void stopVpn() {
        if (!running.getAndSet(false)) {
            Log.d(TAG, "VPN zaten durdurulmuş, stopVpn yoksayıldı");
            return;
        }

        Log.i(TAG, "═══════ VPN DURDURULUYOR ═══════");

        // Tunnel loop'u durdur
        if (tunnelTask != null) {
            tunnelTask.cancel(true);
            tunnelTask = null;
            Log.d(TAG, "✓ Tunnel task iptal edildi");
        }

        // Session manager'a bildir
        SessionManager.INSTANCE.onSessionStop();
        Log.d(TAG, "✓ SessionManager bilgilendirildi");

        // Proxy'yi durdur
        if (proxy != null) {
            proxy.stop();
            proxy = null;
            Log.d(TAG, "✓ MitmProxy durduruldu");
        }

        // TUN interface'i kapat
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException e) {
                Log.w(TAG, "TUN kapatma hatası: " + e.getMessage());
            }
            tunFd = null;
            Log.d(TAG, "✓ TUN kapatıldı");
        }

        Log.i(TAG, "═══════ VPN DURDURULDU ═══════");
    }

    /**
     * Cleans up all resources when VPN start fails.
     * Ensures no resources leak even on failure.
     */
    private void cleanupOnFailure() {
        Log.w(TAG, "Hata sonrası temizlik başlatılıyor...");

        // Proxy'yi durdurmayı dene (başlamış olabilir)
        if (proxy != null) {
            try {
                proxy.stop();
            } catch (Exception ex) {
                Log.w(TAG, "Proxy temizlik hatası: " + ex.getMessage());
            }
            proxy = null;
        }

        // TUN'u kapatmayı dene (oluşturulmuş olabilir)
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ex) {
                Log.w(TAG, "TUN temizlik hatası: " + ex.getMessage());
            }
            tunFd = null;
        }

        // Tunnel task varsa iptal et
        if (tunnelTask != null) {
            tunnelTask.cancel(true);
            tunnelTask = null;
        }

        running.set(false);
        stopSelf();
        Log.w(TAG, "Temizlik tamamlandı, servis durduruldu");
    }

    // ── TUN Builder ───────────────────────────────────────────────────────

    /**
     * Builds TUN interface with narrow route to target server.
     * Uses DNS resolution to get target IP, avoiding "0.0.0.0/0" route
     * which causes establish() to return null on many devices.
     *
     * @return ParcelFileDescriptor for the TUN interface
     * @throws Exception if TUN cannot be established after retries
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
            Log.w(TAG, "⚠ Fallback: 0.0.0.0/0 route kullanılıyor (çakışma riski var)");
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
        return establishWithRetry(b);
    }

    /**
     * Tries to establish TUN with retry and exponential backoff.
     * Some devices need multiple attempts, especially if another VPN
     * was recently disconnected.
     */
    private ParcelFileDescriptor establishWithRetry(Builder builder) throws Exception {
        final int MAX_RETRIES = 3;
        ParcelFileDescriptor pfd = null;
        int retryCount = 0;

        while (pfd == null && retryCount < MAX_RETRIES) {
            try {
                Log.d(TAG, "establish() deneniyor... (deneme " + (retryCount + 1) + "/" + MAX_RETRIES + ")");
                pfd = builder.establish();
                
                if (pfd == null) {
                    retryCount++;
                    if (retryCount < MAX_RETRIES) {
                        long waitMs = 300L * (retryCount + 1); // Exponential backoff
                        Log.w(TAG, "establish() null döndü, " + waitMs + "ms bekleniyor... (retry " + retryCount + ")");
                        Thread.sleep(waitMs);
                    }
                }
            } catch (Exception e) {
                retryCount++;
                Log.e(TAG, "establish() hata: " + e.getMessage() + " (retry " + retryCount + ")");
                if (retryCount >= MAX_RETRIES) {
                    throw e; // Son denemede de hata → fırlat
                }
                long waitMs = 500L * retryCount;
                Thread.sleep(waitMs);
            }
        }

        if (pfd == null) {
            throw new IllegalStateException(
                "TUN establish() " + MAX_RETRIES + " deneme sonunda başarısız oldu.\n" +
                "Olası sebepler:\n" +
                "  - Başka bir VPN uygulaması aktif\n" +
                "  - VPN izni reddedildi\n" +
                "  - Sistem kaynakları yetersiz\n" +
                "Çözüm: Diğer VPN'leri kapatın ve tekrar deneyin."
            );
        }

        return pfd;
    }

    // ── Tunnel Loop ───────────────────────────────────────────────────────

    /**
     * Main packet forwarding loop.
     * Reads raw IP packets from TUN, filters Minecraft UDP traffic,
     * sends matching packets to MITM proxy, forwards others directly.
     */
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
                    if (proxy != null && proxy.isRunning()) {
                        proxy.handleIncomingPacket(pkt.array(), len);
                    }
                } else {
                    // Diğer trafik → direkt TUN'a geri yaz (bypass)
                    out.write(pkt.array(), 0, len);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.e(TAG, "Tunnel I/O hatası: " + e.getMessage());
            }
        }

        Log.i(TAG, "Tunnel loop sona erdi");
    }

    // ── Packet Filter ─────────────────────────────────────────────────────

    /**
     * Checks if packet is IPv4 + UDP + destination port == MC_PORT (19132).
     * Only these packets are intercepted; all others pass through.
     *
     * @param d   Raw IP packet bytes
     * @param len Packet length
     * @return true if this is a Minecraft UDP packet
     */
    private boolean isMcUdp(byte[] d, int len) {
        // Minimum: 20 byte IP header + 8 byte UDP header = 28 byte
        if (len < 28) return false;

        // IPv4 kontrolü (version = 4)
        if (((d[0] >> 4) & 0xF) != 4) return false;

        // UDP protokol kontrolü (IP header'da protocol field = 17)
        if ((d[9] & 0xFF) != 17) return false;

        // UDP destination port kontrolü
        int ihl = (d[0] & 0xF) * 4; // IP header length (IHL)
        int dport = ((d[ihl + 2] & 0xFF) << 8) | (d[ihl + 3] & 0xFF);

        return dport == MC_PORT;
    }

    // ── Notification Channel ──────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "OxClient Proxy",
                NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("VPN proxy service notification for 2b2tpe.org");
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