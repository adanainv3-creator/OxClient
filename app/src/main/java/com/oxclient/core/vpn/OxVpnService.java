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

import com.oxclient.R;
import com.oxclient.ui.dashboard.DashboardActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * OxVpnService — MİNİMAL TEST
 * Sadece TUN açar ve hiçbir şey yapmaz.
 */
public class OxVpnService extends VpnService {
    private static final String TAG          = "OxVpnService";
    private static final String CHANNEL_ID   = "ox_vpn";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_START = "com.oxclient.START_VPN";
    public  static final String ACTION_STOP  = "com.oxclient.STOP_VPN";

    private ParcelFileDescriptor tunFd;
    private Thread tunnelThread;
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Log.i(TAG, "onCreate - MİNİMAL TEST");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        Log.d(TAG, "action=" + action);

        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotif("OxClient Test"));
            
            // ANA THREAD'DE DENE - crash noktasını görelim
            try {
                startSimpleVpn();
            } catch (Exception e) {
                Log.e(TAG, "CRASH: " + e.getMessage(), e);
                stopSelf();
            }
        } else if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
        }

        return START_STICKY;
    }

    private void startSimpleVpn() throws Exception {
        Log.i(TAG, "TUN oluşturuluyor...");
        
        Builder builder = new Builder();
        builder.setSession("OxClient Minimal");
        builder.setMtu(1500);
        builder.addAddress("10.0.0.2", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        builder.allowFamily(OsConstants.AF_INET);
        
        // Minecraft'i scoplama - HER ŞEYİ geçir
        // builder.addAllowedApplication("com.mojang.minecraftpe"); // BUNU DA KALDIR
        
        Log.i(TAG, "establish() çağrılıyor...");
        tunFd = builder.establish();
        
        if (tunFd == null) {
            Log.e(TAG, "establish() NULL döndü!");
            throw new Exception("establish null");
        }
        
        Log.i(TAG, "TUN başarılı! fd=" + tunFd.getFd());
        running = true;
        
        // Minimal tunnel - sadece oku ve yaz
        tunnelThread = new Thread(() -> {
            Log.i(TAG, "Tunnel thread başladı");
            ByteBuffer buf = ByteBuffer.allocate(1500);
            try {
                FileInputStream in = new FileInputStream(tunFd.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor());
                
                while (running) {
                    buf.clear();
                    int len = in.read(buf.array());
                    if (len > 0) {
                        out.write(buf.array(), 0, len);
                    }
                }
                
                in.close();
                out.close();
            } catch (IOException e) {
                Log.e(TAG, "Tunnel hata: " + e.getMessage());
            }
            Log.i(TAG, "Tunnel thread bitti");
        }, "OxTunnel");
        tunnelThread.setDaemon(true);
        tunnelThread.start();
        
        Log.i(TAG, "VPN AKTİF!");
    }

    private void stopVpn() {
        running = false;
        
        if (tunnelThread != null) {
            tunnelThread.interrupt();
            try { tunnelThread.join(1000); } catch (InterruptedException e) {}
            tunnelThread = null;
        }
        
        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException e) {}
            tunFd = null;
        }
        
        Log.i(TAG, "VPN durdu");
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "OxClient", NotificationManager.IMPORTANCE_LOW
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