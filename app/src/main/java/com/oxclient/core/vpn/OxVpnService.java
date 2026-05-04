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
 *
 * ── Trafik yönetimi ───────────────────────────────────────────────────────
 *
 *  SADECE com.mojang.minecraftpe VPN'den geçer (addAllowedApplication).
 *  OxClient'in kendi trafiği (Microsoft auth HTTPS) VPN'den GEÇMEZ.
 *
 *  Minecraft içindeki trafik:
 *    UDP → dst port 19132 → MitmProxy'ye ilet (RakNet/Bedrock)
 *    UDP → diğer port    → TUN'a geri yaz (pass-through)
 *    TCP                 → TUN'a geri yaz (pass-through) ← Minecraft login HTTPS buradan geçer
 *    Diğer protokol      → TUN'a geri yaz (pass-through)
 *
 *  SORUN (önceki): TCP paketleri hiçbir yere yazılmıyordu → Minecraft login
 *  akışı (WebView HTTPS) cevap alamıyordu → sonsuz yükleniyor.
 *
 *  DÜZELTME: tunnelLoop() içinde UDP+port19132 dışındaki her paket tunOut'a
 *  geri yazılıyor. Bu sayede Minecraft kendi Microsoft oturumunu açabiliyor,
 *  sonra Login paketi gönderdiğinde LoginInjector devreye giriyor.
 *
 *  ── Route stratejisi ──────────────────────────────────────────────────────
 *
 *  Sadece sunucu IP'sine /32 route ekliyoruz.
 *  DNS resolve başarısız olursa VPN BAŞLATILMIYOR (0.0.0.0/0 fallback YOK).
 *  Böylece diğer tüm trafik (Microsoft auth, CDN vb.) normal internet üzerinden
 *  gider ve VPN'den etkilenmez.
 */
public class OxVpnService extends VpnService {
    private static final String TAG          = "OxVpnService";
    private static final String CHANNEL_ID   = "ox_vpn";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_START = "com.oxclient.START_VPN";
    public  static final String ACTION_STOP  = "com.oxclient.STOP_VPN";

    private static final int TUN_MTU    = 1500;
    private static final int MC_PORT    = BuildConfig.SERVER_PORT;   // 19132
    private static final int PROXY_PORT = BuildConfig.LOCAL_PROXY_PORT; // 19133

    private ParcelFileDescriptor tunFd;
    private MitmProxy            proxy;
    private Thread               tunnelThread;
    private volatile boolean     running = false;

    // TUN yazma — hem tunnelLoop pass-through hem de MitmProxy S2C için
    private volatile FileOutputStream tunOut;

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

    // ── Start / Stop ──────────────────────────────────────────────────────

    private void startVpn() {
        if (running) { Log.w(TAG, "VPN zaten çalışıyor"); return; }

        try {
            Log.i(TAG, "═══ VPN BAŞLATILIYOR ═══");

            tunFd = buildTun();
            if (tunFd == null) {
                Log.e(TAG, "❌ TUN kurulamadı — VPN başlatılmıyor");
                stopSelf();
                return;
            }
            Log.i(TAG, "✅ TUN oluşturuldu");

            tunOut = new FileOutputStream(tunFd.getFileDescriptor());

            try {
                proxy = new MitmProxy(PROXY_PORT, this);
                proxy.setTunWriter(this::writeToTun);
                proxy.start();
                Log.i(TAG, "✅ Proxy başladı, port=" + PROXY_PORT);
                SessionManager.INSTANCE.onSessionStart(proxy);
            } catch (Exception e) {
                Log.e(TAG, "❌ Proxy başlayamadı: " + e.getMessage(), e);
                proxy = null;
            }

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
            try { tunnelThread.join(1500); } catch (InterruptedException ignored) {}
            tunnelThread = null;
        }

        SessionManager.INSTANCE.onSessionStop();

        if (proxy != null) { proxy.stop(); proxy = null; }

        if (tunOut != null) {
            try { tunOut.close(); } catch (IOException ignored) {}
            tunOut = null;
        }
        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException ignored) {}
            tunFd = null;
        }
        Log.i(TAG, "VPN durduruldu");
    }

    private void cleanup() {
        if (proxy != null) { try { proxy.stop(); } catch (Exception ignored) {} proxy = null; }
        if (tunOut != null) { try { tunOut.close(); } catch (IOException ignored) {} tunOut = null; }
        if (tunFd != null) { try { tunFd.close(); } catch (IOException ignored) {} tunFd = null; }
        running = false;
        stopSelf();
    }

    // ── TUN Interface ─────────────────────────────────────────────────────

    /**
     * TUN arayüzünü kurar.
     *
     * ROUTE STRATEJİSİ:
     *   Sadece sunucu IP'sine /32 route eklenir.
     *   Böylece Minecraft'ın Microsoft login HTTPS trafiği (login.live.com vb.)
     *   VPN'den geçmez — normal internet bağlantısını kullanır.
     *
     *   DNS resolve başarısız olursa null döner (0.0.0.0/0 fallback YOK).
     *   Çağıran startVpn() null kontrolü yapar ve VPN'i başlatmaz.
     */
    private ParcelFileDescriptor buildTun() throws Exception {
        // Önce sunucu IP'sini çöz
        InetAddress serverAddr;
        try {
            serverAddr = InetAddress.getByName(BuildConfig.SERVER_HOST);
            Log.i(TAG, "Sunucu IP: " + serverAddr.getHostAddress());
        } catch (Exception e) {
            Log.e(TAG, "Sunucu DNS çözümlenemedi: " + e.getMessage());
            // 0.0.0.0/0 fallback KULLANMA — tüm trafiği ele geçirir, login bozulur
            return null;
        }

        Builder b = new Builder();
        b.setSession("OxClient");
        b.setMtu(TUN_MTU);

        // TUN'un kendi adresi
        b.addAddress("10.8.0.1", 32);

        // SADECE sunucu IP'si route'a giriyor
        // Minecraft'ın geri kalan trafiği (HTTPS auth, CDN) normal internet'ten gider
        b.addRoute(serverAddr.getHostAddress(), 32);

        // DNS — sadece Minecraft içi DNS sorguları için (sunucu ping vb.)
        b.addDnsServer("1.1.1.1");
        b.addDnsServer("8.8.8.8");

        b.allowFamily(OsConstants.AF_INET);

        // SADECE Minecraft uygulaması VPN'den geçsin
        // OxClient'in kendisi (Microsoft auth HTTP istekleri) bypass eder
        try {
            b.addAllowedApplication("com.mojang.minecraftpe");
            Log.i(TAG, "AllowedApp: com.mojang.minecraftpe");
        } catch (Exception e) {
            Log.e(TAG, "addAllowedApplication başarısız: " + e.getMessage());
            // Bu olmadan tüm uygulamalar VPN'den geçer — çok tehlikeli, iptal et
            return null;
        }

        ParcelFileDescriptor pfd = b.establish();
        if (pfd == null) {
            Log.w(TAG, "establish() null döndü, tekrar deneniyor...");
            Thread.sleep(400);
            pfd = b.establish();
        }
        return pfd; // null olabilir, çağıran kontrol eder
    }

    // ── Tunnel Loop ───────────────────────────────────────────────────────

    /**
     * TUN'dan gelen paketleri işler:
     *
     *   UDP + dst port 19132 → MitmProxy (RakNet intercept)
     *   Diğer her şey       → TUN'a geri yaz (pass-through)
     *
     * "Diğer her şey" şunları kapsar:
     *   - Minecraft içi TCP bağlantıları (Marketplace, Realms, Microsoft login WebView)
     *   - UDP ama farklı port (sunucu discovery ping vb.)
     *   - ICMP
     *
     * Bu pass-through olmadan Minecraft içindeki "Microsoft hesabınızla oturum açılıyor"
     * adımı internet bağlantısı bulamıyor ve sonsuz yükleniyor.
     */
    private void tunnelLoop() {
        Log.i(TAG, "Tunnel başladı");
        byte[] buf = new byte[TUN_MTU * 2];

        try (FileInputStream  in  = new FileInputStream(tunFd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor())) {

            while (running) {
                int len = in.read(buf);
                if (len <= 0) continue;

                if (isMcUdp(buf, len)) {
                    // Minecraft sunucu UDP trafiği → proxy'ye ilet
                    if (proxy != null) {
                        byte[] copy = new byte[len];
                        System.arraycopy(buf, 0, copy, 0, len);
                        proxy.handleIncomingPacket(copy, len);
                    }
                } else {
                    // TCP, ICMP, farklı port UDP vb. → pass-through
                    // Minecraft'ın kendi internet bağlantısı (login, marketplace vb.) buradan geçer
                    try {
                        synchronized (this) {
                            out.write(buf, 0, len);
                        }
                    } catch (IOException e) {
                        if (running) Log.v(TAG, "Pass-through yaz hatası: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "Tunnel hata: " + e.getMessage());
        }
        Log.i(TAG, "Tunnel bitti");
    }

    /**
     * IPv4 UDP paketi mi VE hedef port MC_PORT (19132) mi?
     *
     * Sadece sunucu'ya giden C2S paketleri proxy'ye gitmeli.
     * Sunucudan gelen S2C paketler proxy tarafından doğrudan TUN'a yazılır
     * (writeToTun callback).
     */
    private boolean isMcUdp(byte[] d, int len) {
        if (len < 28) return false;                          // min IP(20) + UDP(8)
        if (((d[0] >> 4) & 0xF) != 4) return false;        // IPv4
        if ((d[9] & 0xFF) != 17) return false;              // UDP protokolü
        int ihl   = (d[0] & 0xF) * 4;
        if (len < ihl + 8) return false;
        int dport = ((d[ihl + 2] & 0xFF) << 8) | (d[ihl + 3] & 0xFF);
        return dport == MC_PORT;                             // dst port 19132
    }

    // ── TUN write callback (MitmProxy → Game) ────────────────────────────

    /**
     * MitmProxy S2C paketleri işledikten sonra bu callback'i çağırır.
     * UDP payload'ı IP+UDP başlıkla sarıp TUN'a yazar → oyun alır.
     */
    public void writeToTun(byte[] udpPayload, java.net.InetSocketAddress src, java.net.InetSocketAddress dst) {
        if (!running || tunOut == null || udpPayload == null) return;
        try {
            byte[] pkt = buildIpUdpPacket(udpPayload, src, dst);
            synchronized (this) {
                tunOut.write(pkt);
            }
        } catch (Exception e) {
            Log.e(TAG, "writeToTun hata: " + e.getMessage());
        }
    }

    private byte[] buildIpUdpPacket(byte[] udpPayload,
                                     java.net.InetSocketAddress src,
                                     java.net.InetSocketAddress dst) throws Exception {
        int udpLen   = 8 + udpPayload.length;
        int totalLen = 20 + udpLen;
        ByteBuffer pkt = ByteBuffer.allocate(totalLen);

        byte[] srcIp  = src.getAddress().getAddress();
        byte[] dstIp  = dst.getAddress().getAddress();

        // IPv4 header
        pkt.put((byte) 0x45);
        pkt.put((byte) 0x00);
        pkt.putShort((short) totalLen);
        pkt.putShort((short) 0);
        pkt.putShort((short) 0x4000);      // DF flag
        pkt.put((byte) 64);                // TTL
        pkt.put((byte) 17);               // UDP
        pkt.putShort((short) 0);          // checksum placeholder
        pkt.put(srcIp);
        pkt.put(dstIp);
        pkt.putShort(18, ipChecksum(pkt.array(), 0, 20));

        // UDP header
        pkt.putShort((short) src.getPort());
        pkt.putShort((short) dst.getPort());
        pkt.putShort((short) udpLen);
        pkt.putShort((short) 0);          // checksum disabled

        pkt.put(udpPayload);
        return pkt.array();
    }

    private static short ipChecksum(byte[] buf, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len - 1; i += 2)
            sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
        if ((len & 1) != 0) sum += (buf[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (short) ~sum;
    }

    // ── Notification ─────────────────────────────────────────────────────

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "OxClient", NotificationManager.IMPORTANCE_LOW));
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
