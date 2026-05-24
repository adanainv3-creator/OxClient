package com.pixelbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;

    // State
    private boolean menuExpanded = false;
    private boolean miningActive = false;
    private boolean fishingActive = false;

    // Stats
    private int miningCount = 0;
    private int fishingCount = 0;

    // Drag
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    private static final String CHANNEL_ID = "pixelbot_channel";
    private static final int NOTIF_ID = 42;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Hazır"));
        createOverlay();
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 16;
        params.y = 200;

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_widget, null);
        windowManager.addView(overlayView, params);

        setupViews();
    }

    private void setupViews() {
        LinearLayout collapsed = overlayView.findViewById(R.id.widgetCollapsed);
        LinearLayout expanded  = overlayView.findViewById(R.id.widgetExpanded);
        Button btnMining       = overlayView.findViewById(R.id.btnMining);
        Button btnFishing      = overlayView.findViewById(R.id.btnFishing);
        Button btnClose        = overlayView.findViewById(R.id.btnClose);
        TextView tvStatus      = overlayView.findViewById(R.id.tvWidgetStatus);
        TextView tvStats       = overlayView.findViewById(R.id.tvStats);

        // Toggle expand on tap (with drag detection)
        collapsed.setOnTouchListener(new View.OnTouchListener() {
            long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downTime = System.currentTimeMillis();
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (initialTouchX - event.getRawX());
                        int dy = (int) (event.getRawY() - initialTouchY);
                        params.x = initialX + dx;
                        params.y = initialY + dy;
                        windowManager.updateViewLayout(overlayView, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        float totalMove = Math.abs(event.getRawX() - initialTouchX)
                                + Math.abs(event.getRawY() - initialTouchY);
                        long duration = System.currentTimeMillis() - downTime;
                        // Tap (not drag)
                        if (totalMove < 10 && duration < 300) {
                            toggleMenu(collapsed, expanded);
                        }
                        return true;
                }
                return false;
            }
        });

        // Auto Mining button
        btnMining.setOnClickListener(v -> {
            miningActive = !miningActive;
            if (miningActive) {
                fishingActive = false;
                updateFishingButton(btnFishing);
                btnMining.setText("⛏ MINING: ON");
                btnMining.setBackgroundResource(R.drawable.btn_active);
                tvStatus.setText("● Auto Mining...");
                tvStatus.setTextColor(0xFFFF8800);
                BotAccessibilityService.startMining(miningCount -> {
                    this.miningCount = miningCount;
                    updateStats(tvStats);
                });
                updateNotification("⛏ Auto Mining aktif");
            } else {
                btnMining.setText("⛏ AUTO MINING");
                btnMining.setBackgroundResource(R.drawable.btn_mining);
                tvStatus.setText("● Bekliyor");
                tvStatus.setTextColor(0xFFFFAA00);
                BotAccessibilityService.stopBot();
                updateNotification("Durduruldu");
            }
        });

        // Auto Fishing button
        btnFishing.setOnClickListener(v -> {
            fishingActive = !fishingActive;
            if (fishingActive) {
                miningActive = false;
                updateMiningButton(btnMining);
                btnFishing.setText("🎣 FISHING: ON");
                btnFishing.setBackgroundResource(R.drawable.btn_active);
                tvStatus.setText("● Auto Fishing...");
                tvStatus.setTextColor(0xFF0088FF);
                BotAccessibilityService.startFishing(fishCount -> {
                    this.fishingCount = fishCount;
                    updateStats(tvStats);
                });
                updateNotification("🎣 Auto Fishing aktif");
            } else {
                btnFishing.setText("🎣 AUTO FISHING");
                btnFishing.setBackgroundResource(R.drawable.btn_fishing);
                tvStatus.setText("● Bekliyor");
                tvStatus.setTextColor(0xFFFFAA00);
                BotAccessibilityService.stopBot();
                updateNotification("Durduruldu");
            }
        });

        // Close button
        btnClose.setOnClickListener(v -> stopSelf());

        updateStats(tvStats);
    }

    private void toggleMenu(LinearLayout collapsed, LinearLayout expanded) {
        menuExpanded = !menuExpanded;
        if (menuExpanded) {
            collapsed.setVisibility(View.GONE);
            expanded.setVisibility(View.VISIBLE);
        } else {
            collapsed.setVisibility(View.VISIBLE);
            expanded.setVisibility(View.GONE);
        }
    }

    private void updateMiningButton(Button btn) {
        miningActive = false;
        btn.setText("⛏ AUTO MINING");
        btn.setBackgroundResource(R.drawable.btn_mining);
    }

    private void updateFishingButton(Button btn) {
        fishingActive = false;
        btn.setText("🎣 AUTO FISHING");
        btn.setBackgroundResource(R.drawable.btn_fishing);
    }

    private void updateStats(TextView tv) {
        tv.post(() -> tv.setText("Mining: " + miningCount + "  |  Fish: " + fishingCount));
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PixelBot")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "PixelBot Service", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Bot çalışıyor");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        BotAccessibilityService.stopBot();
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
