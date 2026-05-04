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
 * FIX 1: isMcUdp() artık tüm UDP paketlerini yakalar (sadece dst port değil).
 *         Sunucudan gelen cevaplar farklı dst port kullandığı için önceki filtre
 *         S2C trafiğini tamamen kaçırıyordu.
 *
 * FIX 2: tunnelLoop() artık proxy'den gelen yanıtları TUN'a geri yazıyor.
 *         Proxy→Game yönü için writeBack() callback mekanizması eklendi.
 */
public class OxVpnService extends VpnService {
    private static final String TAG          = "OxVpnService";
    private static final String CHANNEL_ID   = "ox_vpn";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_START = "com.oxclient.START_VPN";
    public  static final String ACTION_STOP  = "com.oxclient.STOP_VPN";

    private static final int TUN_MTU    = 1500;
    private static final int MC_PORT    = BuildConfig.SERVER_PORT;
    private static final int PROXY_PORT = BuildConfig.LOCAL_PROXY_PORT;

    private ParcelFileDescriptor tunFd;
    private MitmProxy            proxy;
    private Thread               tunnelThread;
    private volatile boolean     running = false;

    // FileOutputStream'e thread-safe erişim için referans tutuyoruz
    // Proxy → Game yönü için MitmProxy bu callback'i kullanacak
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

            // 2. tunOut referansını sakla — proxy S2C yazmaları için kullanacak
            tunOut = new FileOutputStream(tunFd.getFileDescriptor());

            // 3. MITM Proxy
            try {
                proxy = new MitmProxy(PROXY_PORT, this);
                // Proxy'ye TUN write callback'ini ver
                proxy.setTunWriter(this::writeToTun);
                proxy.start();
                Log.i(TAG, "✅ Proxy başladı, port=" + PROXY_PORT);
                SessionManager.INSTANCE.onSessionStart(proxy);
            } catch (Exception e) {
                Log.e(TAG, "❌ Proxy başlayamadı: " + e.getMessage());
                proxy = null;
            }

            // 4. Tunnel loop (Game → Proxy yönü)
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
            try { tunnelThread.join(1000); } catch (InterruptedException ignored) {}
            tunnelThread = null;
        }

        SessionManager.INSTANCE.onSessionStop();

        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }

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
        if (proxy != null) {
            try { proxy.stop(); } catch (Exception ignored) {}
            proxy = null;
        }
        if (tunOut != null) {
            try { tunOut.close(); } catch (IOException ignored) {}
            tunOut = null;
        }
        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException ignored) {}
            tunFd = null;
        }
        running = false;
        stopSelf();
    }

    // ── TUN write callback (Proxy → Game) ────────────────────────────────

    /**
     * MitmProxy bu metodu çağırarak sunucudan gelen işlenmiş UDP paketini
     * TUN arayüzüne (yani oyuna) yazar.
     *
     * rawUdpPayload: Sadece UDP payload (RakNet datagramı).
     * Bu metod eksik IP/UDP başlık oluşturur ve TUN'a yazar.
     */
    public void writeToTun(byte[] rawUdpPayload, java.net.InetSocketAddress src, java.net.InetSocketAddress dst) {
        if (!running || tunOut == null || rawUdpPayload == null) return;
        try {
            byte[] packet = buildIpUdpPacket(rawUdpPayload, src, dst);
            synchronized (this) {
                tunOut.write(packet);
            }
        } catch (Exception e) {
            Log.e(TAG, "writeToTun hata: " + e.getMessage());
        }
    }

    /**
     * Verilen UDP payload etrafına minimal IPv4 + UDP başlık sararak
     * TUN'a yazılabilir ham IP paketi oluşturur.
     */
    private byte[] buildIpUdpPacket(byte[] udpPayload, java.net.InetSocketAddress src, java.net.InetSocketAddress dst) throws Exception {
        int udpLen   = 8 + udpPayload.length;
        int totalLen = 20 + udpLen;
        ByteBuffer pkt = ByteBuffer.allocate(totalLen);

        byte[] srcIp  = src.getAddress().getAddress();
        byte[] dstIp  = dst.getAddress().getAddress();
        int    srcPort = src.getPort();
        int    dstPort = dst.getPort();

        // IPv4 header (20 byte, no options)
        pkt.put((byte) 0x45);              // version=4, IHL=5
        pkt.put((byte) 0x00);              // DSCP/ECN
        pkt.putShort((short) totalLen);    // total length
        pkt.putShort((short) 0);           // identification
        pkt.putShort((short) 0x4000);      // flags: DF
        pkt.put((byte) 64);                // TTL
        pkt.put((byte) 17);                // protocol: UDP
        pkt.putShort((short) 0);           // checksum placeholder
        pkt.put(srcIp);
        pkt.put(dstIp);

        // IPv4 checksum
        pkt.putShort(18, ipChecksum(pkt.array(), 0, 20));

        // UDP header (8 byte)
        pkt.putShort((short) srcPort);
        pkt.putShort((short) dstPort);
        pkt.putShort((short) udpLen);
        pkt.putShort((short) 0);           // UDP checksum (0 = disabled)

        // Payload
        pkt.put(udpPayload);

        return pkt.array();
    }

    private static short ipChecksum(byte[] buf, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length - 1; i += 2) {
            sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
        }
        if ((length & 1) != 0) sum += (buf[offset + length - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (short) ~sum;
    }

    // ── TUN Interface ─────────────────────────────────────────────────────

    private ParcelFileDescriptor buildTun() throws Exception {
        Builder b = new Builder();
        b.setSession("OxClient");
        b.setMtu(TUN_MTU);
        b.addAddress("10.0.0.2", 32);

        // Sunucu IP'sini route'a ekle; başarısız olursa tüm trafiği yönlendir
        try {
            InetAddress addr = InetAddress.getByName(BuildConfig.SERVER_HOST);
            b.addRoute(addr.getHostAddress(), 32);
        } catch (Exception e) {
            Log.w(TAG, "Sunucu IP çözümlenemedi, full route ekleniyor");
            b.addRoute("0.0.0.0", 0);
        }

        b.addRoute("1.1.1.1", 32);
        b.addDnsServer("1.1.1.1");
        b.addDnsServer("8.8.8.8");
        b.allowFamily(OsConstants.AF_INET);

        try {
            b.addAllowedApplication("com.mojang.minecraftpe");
        } catch (Exception e) {
            Log.w(TAG, "addAllowedApplication başarısız: " + e.getMessage());
        }

        ParcelFileDescriptor pfd = b.establish();
        if (pfd == null) {
            Thread.sleep(300);
            pfd = b.establish();
        }
        if (pfd == null) throw new Exception("TUN establish null döndü");
        return pfd;
    }

    // ── Tunnel Loop (Game → Proxy) ────────────────────────────────────────

    private void tunnelLoop() {
        Log.i(TAG, "Tunnel başladı");
        byte[] buf = new byte[TUN_MTU * 2];

        try (FileInputStream in = new FileInputStream(tunFd.getFileDescriptor())) {
            while (running) {
                int len = in.read(buf);
                if (len <= 0) continue;

                // FIX: Tüm UDP paketlerini yakala, sadece destination port 19132 değil.
                // Sunucudan gelen yanıtlar oyunun rastgele kaynak portuna gider,
                // bu nedenle önceki dport==MC_PORT filtresi S2C trafiğini kaçırıyordu.
                if (isUdpPacket(buf, len) && proxy != null) {
                    byte[] copy = new byte[len];
                    System.arraycopy(buf, 0, copy, 0, len);
                    proxy.handleIncomingPacket(copy, len);
                }
                // Diğer protokoller (TCP, ICMP vb.) için pass-through gerekmez
                // çünkü Minecraft Bedrock yalnızca UDP kullanır.
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "Tunnel hata: " + e.getMessage());
        }
        Log.i(TAG, "Tunnel bitti");
    }

    /**
     * FIX: Sadece IPv4 + UDP kontrolü. Destination port filtresi KALDIRILDI.
     * Önceki kod: dport == MC_PORT — bu sunucudan gelen paketleri kaçırıyordu.
     */
    private boolean isUdpPacket(byte[] d, int len) {
        if (len < 20) return false;
        if (((d[0] >> 4) & 0xF) != 4) return false;  // IPv4 mı?
        return (d[9] & 0xFF) == 17;                   // UDP protokolü mü?
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
