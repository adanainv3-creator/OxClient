package com.oxclient.ui.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.oxclient.module.ModuleManager
import com.oxclient.ui.dashboard.DashboardActivity
import com.oxclient.ui.theme.*
import kotlinx.coroutines.*

class OverlayService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner
{
    companion object {
        const val CHANNEL_ID = "ox_overlay_channel"
        const val NOTIF_ID   = 1001
    }

    // ── Lifecycle plumbing ────────────────────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ── Overlay ───────────────────────────────────────────────────────────
    private lateinit var wm       : WindowManager
    private lateinit var rootView : ComposeView
    private lateinit var params   : WindowManager.LayoutParams

    private var posX = 100; private var posY = 300

    override fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        showOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (::rootView.isInitialized) wm.removeView(rootView)
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────
    //  OVERLAY OLUŞTUR
    // ─────────────────────────────────────────────────────────────────────

    private fun showOverlay() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX; y = posY
        }

        rootView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            
            // SavedStateRegistryOwner - reflection ile set edelim (eski API uyumluluğu)
            try {
                val method = ComposeView::class.java.getMethod(
                    "setViewTreeSavedStateRegistryOwner",
                    SavedStateRegistryOwner::class.java
                )
                method.invoke(this, this@OverlayService)
            } catch (e: Exception) {
                // Eski Android versiyonlarında bu metod olmayabilir
                Log.w("OverlayService", "setViewTreeSavedStateRegistryOwner bulunamadı", e)
            }

            setContent {
                OxTheme {
                    OverlayContent(
                        onDrag = { dx, dy ->
                            posX += dx.toInt(); posY += dy.toInt()
                            params.x = posX; params.y = posY
                            wm.updateViewLayout(rootView, params)
                        },
                        onOpenDashboard = {
                            startActivity(Intent(this@OverlayService, DashboardActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    )
                }
            }
        }

        wm.addView(rootView, params)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BİLDİRİM
    // ─────────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.oxclient.R.drawable.ic_ox_logo)
            .setContentTitle("OxClient")
            .setContentText(getString(com.oxclient.R.string.overlay_notif_text))
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  COMPOSE UI - Modül durumları canlı okunacak
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun OverlayContent(
    onDrag          : (Float, Float) -> Unit,
    onOpenDashboard : () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Modül durumlarını gerçek zamanlı oku
    val killAuraEnabled by remember { 
        derivedStateOf { ModuleManager.killAura.enabled }
    }
    val criticalsEnabled by remember { 
        derivedStateOf { ModuleManager.criticals.enabled }
    }
    val autoTotemEnabled by remember { 
        derivedStateOf { ModuleManager.autoTotem.enabled }
    }
    val tpAuraEnabled by remember { 
        derivedStateOf { ModuleManager.tpAura.enabled }
    }

    Column(horizontalAlignment = Alignment.End) {

        // ── FAB Butonu ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(OxPurple.copy(alpha = 0.92f))
                .pointerInput(Unit) {
                    var accX = 0f; var accY = 0f
                    detectDragGestures(
                        onDragStart = { accX = 0f; accY = 0f },
                        onDrag = { change, drag ->
                            change.consume()
                            accX += drag.x; accY += drag.y
                            if (kotlin.math.abs(accX) > 8f || kotlin.math.abs(accY) > 8f) {
                                onDrag(drag.x, drag.y)
                            }
                        },
                        onDragEnd = {
                            if (kotlin.math.abs(accX) < 8f && kotlin.math.abs(accY) < 8f) {
                                menuOpen = !menuOpen
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (menuOpen) Icons.Default.Close else Icons.Default.Shield,
                contentDescription = "OxClient",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }

        // ── Modül Menüsü ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = menuOpen,
            enter   = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit    = fadeOut() + slideOutVertically(targetOffsetY = { -it })
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .widthIn(min = 180.dp),
                shape   = RoundedCornerShape(14.dp),
                color   = OxSurface.copy(alpha = 0.95f),
                border  = BorderStroke(1.dp, OxBorder)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Başlık
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "OxClient",
                            color = OxPurpleLight,
                            style = MaterialTheme.typography.labelLarge
                        )
                        IconButton(
                            onClick = onOpenDashboard,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.OpenInFull,
                                null,
                                tint = OxTextSub,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = OxBorder)

                    // Modül satırları - gerçek durumlarla
                    ModuleRow("KillAura",  killAuraEnabled)  { ModuleManager.killAura.toggle() }
                    ModuleRow("Criticals", criticalsEnabled) { ModuleManager.criticals.toggle() }
                    ModuleRow("AutoTotem", autoTotemEnabled) { ModuleManager.autoTotem.toggle() }
                    ModuleRow("TPAura",    tpAuraEnabled)    { ModuleManager.tpAura.toggle() }

                    HorizontalDivider(color = OxBorder)

                    // İstatistik
                    val stats = remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        while (true) {
                            val p = com.oxclient.proxy.PacketProcessor.packetsIntercepted
                            stats.value = "📦 $p pkt"
                            delay(1000)
                        }
                    }
                    Text(
                        stats.value,
                        color = OxTextSub,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleRow(name: String, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .background(if (enabled) OxPurple.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text  = name,
            color = if (enabled) OxPurpleLight else OxText,
            style = MaterialTheme.typography.bodyMedium
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (enabled) OxGreen else OxBorder)
        )
    }
}