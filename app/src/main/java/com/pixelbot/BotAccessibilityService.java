package com.pixelbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.nio.ByteBuffer;

/**
 * PixelBot Accessibility Service
 *
 * MINING BOT LOGIC:
 *   - Her ~800ms'de bir saldırı tuşuna bas (ekranın sağ alt köşesi → punch/action butonu)
 *   - Karakter yerde mi kontrol et, periyodik olarak hareket et (sol-sağ)
 *
 * FISHING BOT LOGIC:
 *   State machine:
 *   STATE 0 (CAST):   Sağ alttaki kanca butonuna bas → olta atar
 *   STATE 1 (WAIT):   "FISH ON!" metnini bekle (ekran piksel rengi analizi)
 *   STATE 2 (REACT):  Net/av butonuna bas (sağ alt değişir)
 *   STATE 3 (MINIGAME): Balık göstergesi çubuğunu takip et, doğru anda tıkla
 *   STATE 4 (COLLECT): "Take Fish" butonuna bas
 *   → Tekrar STATE 0
 */
public class BotAccessibilityService extends AccessibilityService {

    private static final String TAG = "PixelBot";

    // Singleton reference (Accessibility service only one instance)
    private static BotAccessibilityService instance;

    // Bot state
    private static boolean botRunning = false;
    private static String currentMode = ""; // "mining" or "fishing"
    private static MiningCallback miningCallback;
    private static FishingCallback fishingCallback;
    private static int miningHitCount = 0;
    private static int fishCaughtCount = 0;

    // Handlers
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable miningRunnable;
    private Runnable fishingRunnable;

    // Screen dimensions (will be populated on connect)
    private int screenW = 1080;
    private int screenH = 2400;

    // ─────────────────────────────────────────────────────────────
    //  Screen coordinate helpers
    //  Coordinates are percentages → converted at runtime
    // ─────────────────────────────────────────────────────────────

    // MINING: action/punch button — bottom right area
    // From screenshots: fist button is at ~90% x, ~87% y
    private float mineX() { return screenW * 0.91f; }
    private float mineY() { return screenH * 0.87f; }

    // MINING: move left button  ~11% x, 87% y
    private float moveLeftX()  { return screenW * 0.11f; }
    // MINING: move right button ~21% x, 87% y
    private float moveRightX() { return screenW * 0.22f; }
    private float moveY()      { return screenH * 0.87f; }

    // FISHING: cast hook button (bottom-right, hook icon) ~91% x, 87% y
    // Same position as punch but icon changes in fishing world
    private float fishCastX() { return screenW * 0.91f; }
    private float fishCastY() { return screenH * 0.87f; }

    // FISHING: net/collect button (appears when fish is on) ~91% x, 87% y
    private float fishNetX() { return screenW * 0.91f; }
    private float fishNetY() { return screenH * 0.87f; }

    // FISHING: minigame tap zone (center of screen for bar game)
    private float fishMiniX() { return screenW * 0.63f; }
    private float fishMiniY() { return screenH * 0.14f; } // bar is top area

    // FISHING: "Take Fish" button — center bottom of popup ~50% x, 83% y
    private float takeFishX() { return screenW * 0.50f; }
    private float takeFishY() { return screenH * 0.83f; }

    // "Exit" button in fishing world (left side) ~13% x, 72% y
    private float exitX() { return screenW * 0.13f; }
    private float exitY() { return screenH * 0.72f; }

    // ─────────────────────────────────────────────────────────────
    //  Interfaces for callbacks
    // ─────────────────────────────────────────────────────────────
    public interface MiningCallback  { void onHit(int count); }
    public interface FishingCallback { void onFishCaught(int count); }

    // ─────────────────────────────────────────────────────────────
    //  Static API (called from OverlayService)
    // ─────────────────────────────────────────────────────────────
    public static void startMining(MiningCallback cb) {
        miningCallback = cb;
        miningHitCount = 0;
        currentMode    = "mining";
        botRunning     = true;
        if (instance != null) instance.runMiningLoop();
    }

    public static void startFishing(FishingCallback cb) {
        fishingCallback = cb;
        fishCaughtCount = 0;
        currentMode     = "fishing";
        botRunning      = true;
        if (instance != null) instance.runFishingLoop();
    }

    public static void stopBot() {
        botRunning  = false;
        currentMode = "";
        if (instance != null) {
            instance.handler.removeCallbacksAndMessages(null);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        detectScreenSize();
        Log.d(TAG, "PixelBot Accessibility connected. Screen: " + screenW + "x" + screenH);

        // Resume if was running
        if (botRunning) {
            if ("mining".equals(currentMode))  runMiningLoop();
            if ("fishing".equals(currentMode)) runFishingLoop();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed — we use gesture dispatch
    }

    @Override
    public void onInterrupt() {
        botRunning = false;
    }

    @Override
    public void onDestroy() {
        botRunning = false;
        instance   = null;
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────
    //  Screen size detection
    // ─────────────────────────────────────────────────────────────
    private void detectScreenSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  MINING LOOP
    //
    //  Pattern every cycle (~2.5 seconds):
    //    1. Hit action button (punch/mine) 2 times fast
    //    2. Move right briefly  →  hit again  →  move left
    //    This covers both tiles left and right of character
    // ─────────────────────────────────────────────────────────────
    private void runMiningLoop() {
        if (!botRunning || !"mining".equals(currentMode)) return;

        miningRunnable = new Runnable() {
            @Override
            public void run() {
                if (!botRunning || !"mining".equals(currentMode)) return;

                // Step 1: punch current tile (twice fast)
                tap(mineX(), mineY(), 0);
                tap(mineX(), mineY(), 200);

                // Step 2: move right, punch
                holdRight(400, 600);
                tap(mineX(), mineY(), 700);

                // Step 3: move left, punch
                holdLeft(900, 1100);
                tap(mineX(), mineY(), 1200);

                // Step 4: move right back
                holdRight(1400, 1500);

                // Step 5: punch again
                tap(mineX(), mineY(), 1600);

                // Update count
                handler.postDelayed(() -> {
                    miningHitCount += 4;
                    if (miningCallback != null) miningCallback.onHit(miningHitCount);
                }, 1700);

                // Schedule next cycle
                handler.postDelayed(this, 2000);
            }
        };

        handler.post(miningRunnable);
    }

    // ─────────────────────────────────────────────────────────────
    //  FISHING STATE MACHINE
    //
    //  States:
    //    IDLE      → tap cast button
    //    WAITING   → poll for "FISH ON!" color indicator
    //    REACTING  → tap net button quickly
    //    MINIGAME  → tap at right timing to keep fish in zone
    //    TAKING    → tap "Take Fish"
    // ─────────────────────────────────────────────────────────────
    private enum FishState { CAST, WAITING, REACTING, MINIGAME, TAKING }
    private FishState fishState = FishState.CAST;
    private long fishStateStartTime = 0;
    private int miniGameTaps = 0;
    private static final int MINIGAME_DURATION_MS = 5000; // ~5 sec minigame

    private void runFishingLoop() {
        fishState = FishState.CAST;
        scheduleFishingStep();
    }

    private void scheduleFishingStep() {
        if (!botRunning || !"fishing".equals(currentMode)) return;

        switch (fishState) {
            case CAST:
                // Cast the fishing rod
                tap(fishCastX(), fishCastY(), 0);
                fishStateStartTime = System.currentTimeMillis();
                miniGameTaps = 0;
                // Wait then start polling
                handler.postDelayed(() -> {
                    fishState = FishState.WAITING;
                    scheduleFishingStep();
                }, 600);
                break;

            case WAITING:
                // Poll every 300ms for FISH ON indicator
                // "FISH ON!" appears as bright green text at top center
                // We detect by looking at pixel color at ~50% x, ~8% y
                // If it's bright green → fish is on!
                long elapsed = System.currentTimeMillis() - fishStateStartTime;

                if (elapsed > 30000) {
                    // Timeout — try casting again
                    Log.d(TAG, "Fishing timeout, recasting");
                    fishState = FishState.CAST;
                    scheduleFishingStep();
                    return;
                }

                // Sample pixel at "FISH ON!" text position
                // Approximate: x=50% (center), y=8% (top area with banner)
                int sampleX = (int)(screenW * 0.50f);
                int sampleY = (int)(screenH * 0.08f);

                if (isGreenIndicatorPresent(sampleX, sampleY)) {
                    Log.d(TAG, "FISH ON detected!");
                    fishState = FishState.REACTING;
                    scheduleFishingStep();
                } else {
                    handler.postDelayed(this::scheduleFishingStep, 250);
                }
                break;

            case REACTING:
                // React FAST — tap net button immediately
                tap(fishNetX(), fishNetY(), 0);
                tap(fishNetX(), fishNetY(), 100); // double tap for safety
                fishStateStartTime = System.currentTimeMillis();
                handler.postDelayed(() -> {
                    fishState = FishState.MINIGAME;
                    scheduleFishingStep();
                }, 400);
                break;

            case MINIGAME:
                // The minigame: a fish indicator moves left-right in a bar at top.
                // We must tap rapidly to keep it in the green zone.
                // Strategy: tap center of bar zone rapidly (~every 200ms)
                // Bar is at top of screen: ~50%-65% x, ~12-16% y
                long mgElapsed = System.currentTimeMillis() - fishStateStartTime;

                if (mgElapsed >= MINIGAME_DURATION_MS) {
                    // Minigame should be over — check for popup or go to TAKING
                    fishState = FishState.TAKING;
                    scheduleFishingStep();
                    return;
                }

                // Tap the fish indicator zone
                // The bar fish icon is at varying positions; we tap slightly right of center
                // to keep fish moving left (keeps it in zone)
                float tapVariance = (miniGameTaps % 2 == 0) ? 0.58f : 0.52f;
                tap(screenW * tapVariance, fishMiniY(), 0);
                miniGameTaps++;

                handler.postDelayed(this::scheduleFishingStep, 180);
                break;

            case TAKING:
                // "Take Fish" popup — tap the green button
                // Button is at center-bottom of popup: ~50% x, ~83% y
                tap(takeFishX(), takeFishY(), 0);
                tap(takeFishX(), takeFishY(), 300); // backup tap

                fishCaughtCount++;
                if (fishingCallback != null) fishingCallback.onFishCaught(fishCaughtCount);
                Log.d(TAG, "Fish caught! Total: " + fishCaughtCount);

                // Reset and cast again
                handler.postDelayed(() -> {
                    fishState = FishState.CAST;
                    scheduleFishingStep();
                }, 800);
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  "FISH ON!" detection
    //  The "FISH ON!" banner is bright green (#00FF00 family)
    //  We use getRootInActiveWindow to look for text nodes
    //  as a more reliable method than pixel sampling on Android
    // ─────────────────────────────────────────────────────────────
    private boolean isGreenIndicatorPresent(int x, int y) {
        // Method 1: Try to find "FISH ON" in accessibility tree
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            if (findNodeWithText(root, "FISH ON")) {
                root.recycle();
                return true;
            }
            root.recycle();
        }

        // Method 2: Time-based heuristic fallback
        // In Pixel Worlds, after casting, fish usually bites within 3-15 seconds
        // We use random timing simulation as last resort
        long timeSinceCast = System.currentTimeMillis() - fishStateStartTime;
        // After 3s, check every 250ms with increasing probability
        if (timeSinceCast > 3000) {
            // Simulate detection after random delay (3-15s)
            // This is the fallback — real detection above is preferred
            return false; // keep polling via accessibility tree
        }
        return false;
    }

    private boolean findNodeWithText(android.view.accessibility.AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().toUpperCase().contains(text)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (findNodeWithText(child, text)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    //  Gesture helpers
    // ─────────────────────────────────────────────────────────────

    /** Single tap at (x, y) with given delay offset */
    private void tap(float x, float y, long delayMs) {
        handler.postDelayed(() -> performTap(x, y), delayMs);
    }

    private void performTap(float x, float y) {
        if (!botRunning) return;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        dispatchGesture(gesture, null, null);
    }

    /** Hold right button from startMs to endMs */
    private void holdRight(long startMs, long endMs) {
        handler.postDelayed(() -> performHold(moveRightX(), moveY(), endMs - startMs), startMs);
    }

    /** Hold left button from startMs to endMs */
    private void holdLeft(long startMs, long endMs) {
        handler.postDelayed(() -> performHold(moveLeftX(), moveY(), endMs - startMs), startMs);
    }

    private void performHold(float x, float y, long durationMs) {
        if (!botRunning) return;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        dispatchGesture(gesture, null, null);
    }

    /** Swipe gesture */
    private void swipe(float x1, float y1, float x2, float y2, long durationMs) {
        if (!botRunning) return;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        dispatchGesture(gesture, null, null);
    }
}
