package com.pixelbot;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.app.Activity;
import android.content.Context;
import java.util.List;

public class MainActivity extends Activity {

    private TextView tvOverlayStatus, tvAccessibilityStatus, tvBotStatus;
    private TextView tvStep1, tvStep2, tvStep3, tvStep4;
    private Button btnOverlay, btnAccessibility, btnLaunchGame;

    private static final int OVERLAY_PERMISSION_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvOverlayStatus     = findViewById(R.id.tvOverlayStatus);
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus);
        tvBotStatus         = findViewById(R.id.tvBotStatus);
        tvStep1             = findViewById(R.id.tvStep1);
        tvStep2             = findViewById(R.id.tvStep2);
        tvStep3             = findViewById(R.id.tvStep3);
        tvStep4             = findViewById(R.id.tvStep4);

        btnOverlay          = findViewById(R.id.btnOverlay);
        btnAccessibility    = findViewById(R.id.btnAccessibility);
        btnLaunchGame       = findViewById(R.id.btnLaunchGame);

        // Overlay permission
        btnOverlay.setOnClickListener(v -> requestOverlayPermission());

        // Accessibility settings
        btnAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        // Launch game + start overlay service
        btnLaunchGame.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission();
                return;
            }
            // Start overlay service
            startService(new Intent(this, OverlayService.class));
            // Open Pixel Worlds
            Intent gameIntent = getPackageManager().getLaunchIntentForPackage("com.pixelworlds.game");
            if (gameIntent != null) {
                gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(gameIntent);
            } else {
                // Open Play Store if not installed
                Intent store = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.pixelworlds.game"));
                startActivity(store);
            }
        });

        // Auto-request overlay if not granted
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            updateStatus();
        }
    }

    private void updateStatus() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean accessOk  = isAccessibilityEnabled();

        // Overlay status
        tvOverlayStatus.setText(overlayOk ? "✓ Verildi" : "✗ Yok");
        tvOverlayStatus.setTextColor(overlayOk ? 0xFF00CC44 : 0xFFFF4444);

        // Accessibility status
        tvAccessibilityStatus.setText(accessOk ? "✓ Açık" : "✗ Kapalı");
        tvAccessibilityStatus.setTextColor(accessOk ? 0xFF00CC44 : 0xFFFF4444);

        // Bot status
        if (overlayOk && accessOk) {
            tvBotStatus.setText("Hazır");
            tvBotStatus.setTextColor(0xFF00CC44);
        } else {
            tvBotStatus.setText("Kurulum Gerekli");
            tvBotStatus.setTextColor(0xFFFFAA00);
        }

        // Step highlights
        tvStep1.setTextColor(overlayOk ? 0xFF00CC44 : 0xFFFFFFFF);
        tvStep1.setText(overlayOk ? "✓ 1. Overlay İzni Verildi" : "1. Overlay İzni Ver  →  Butona bas");
        tvStep2.setTextColor(accessOk ? 0xFF00CC44 : (overlayOk ? 0xFFFFFFFF : 0xFF8888AA));
        tvStep2.setText(accessOk ? "✓ 2. Accessibility Açık" : "2. Accessibility Aç  →  PixelBot'u etkinleştir");
        tvStep3.setTextColor((overlayOk && accessOk) ? 0xFFFFFFFF : 0xFF8888AA);
        tvStep4.setTextColor((overlayOk && accessOk) ? 0xFFFFFFFF : 0xFF8888AA);

        // Button states
        btnOverlay.setAlpha(overlayOk ? 0.5f : 1.0f);
        btnOverlay.setText(overlayOk ? "✓  OVERLAY İZNİ VERİLDİ" : "📱  OVERLAY İZNİ VER");
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager)
                getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabledServices) {
            if (info.getId().contains(getPackageName())) return true;
        }
        return false;
    }
}
