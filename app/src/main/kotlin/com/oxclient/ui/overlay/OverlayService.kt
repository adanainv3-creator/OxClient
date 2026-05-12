package com.oxclient.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.oxclient.R
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.session.SessionManager
import com.oxclient.module.*
import com.oxclient.session.SessionManager
import com.oxclient.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG        = "OverlayService"
        private const val CHANNEL_ID = "ox_overlay"
        private const val NOTIF_ID   = 1002

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    // Lifecycle
    private val lcReg = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lcReg

    private val ssrCtrl = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrCtrl.savedStateRegistry

    // Window
    private lateinit var wm: WindowManager
    private var isAttached = false

    // Görünümler
    private var fabView: ComposeView? = null
    private var menuView: ComposeView? = null
    private val shortcutViews = mutableMapOf<String, ComposeView>()

    // Pozisyonlar
    private var fabX = 50f;  private var fabY = 300f
    private val shortcutPositions = mutableMapOf<String, Pair<Float, Float>>()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        ssrCtrl.performRestore(null)
        lcReg.currentState = Lifecycle.State.CREATED
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        ModuleManager.init()
        OverlayLogger.d(TAG, "onCreate - ${ModuleManager.modules.size} modül hazır")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif())
        showOverlay()
        lcReg.currentState = Lifecycle.State.RESUMED
        OverlayState.setOverlayVisible(true)
        return START_STICKY
    }

    override fun onDestroy() {
        lcReg.currentState = Lifecycle.State.DESTROYED
        OverlayState.setOverlayVisible(false)
        removeAllOverlays()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Pencere parametreleri ──────────────────────────────────────────────

    private fun shortcutParams(x: Float, y: Float) =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x.roundToInt(); this.y = y.roundToInt() }

    private fun menuParams() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // ✅ FIX: FLAG_NOT_FOCUSABLE kaldırıldı.
            // Bu flag aktifken Switch, Slider ve tüm interactive widget'lar
            // touch event ALAMIYOR — FullBright toggle'ı dahil hiçbir ayar
            // çalışmıyordu. Menü açıkken focus alabilmeli.
            // FLAG_NOT_TOUCH_MODAL de kaldırıldı — zaten arka planı kaplayan
            // Box(detectTapGestures { hideMenu() }) menüyü kapatıyor.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    // ── Overlay gösterimi ─────────────────────────────────────────────────

    private fun showOverlay() {
        if (isAttached) return

        try {
            val fabParams = shortcutParams(fabX, fabY)
            fabView = composeView {
                FabButton(
                    onDrag = { dx, dy ->
                        fabX += dx; fabY += dy
                        fabParams.x = fabX.roundToInt(); fabParams.y = fabY.roundToInt()
                        safeUpdate(fabView, fabParams)
                    },
                    onClick = { toggleMenu() }
                )
            }
            wm.addView(fabView, fabParams)

            refreshShortcuts()

            isAttached = true
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Overlay eklenemedi: ${e.message}", e)
        }
    }

    private fun refreshShortcuts() {
        val activeShortcuts = ModuleManager.shortcutModules().map { it.name }.toSet()

        val toRemove = shortcutViews.entries.filter { it.key !in activeShortcuts }
        toRemove.forEach { (name, view) ->
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
            shortcutViews.remove(name)
        }

        ModuleManager.shortcutModules().forEach { mod ->
            if (mod.name !in shortcutViews) {
                val pos = shortcutPositions.getOrPut(mod.name) {
                    val idx = ModuleManager.shortcutModules().indexOf(mod)
                    50f to (420f + idx * 50f)
                }
                val params = shortcutParams(pos.first, pos.second)
                val view = composeView {
                    ShortcutButton(
                        module  = mod,
                        onDrag  = { dx, dy ->
                            val (cx, cy) = shortcutPositions[mod.name] ?: (0f to 0f)
                            val nx = cx + dx; val ny = cy + dy
                            shortcutPositions[mod.name] = nx to ny
                            params.x = nx.roundToInt(); params.y = ny.roundToInt()
                            safeUpdate(shortcutViews[mod.name], params)
                        },
                        onToggle = { ModuleManager.toggle(mod) }
                    )
                }
                shortcutViews[mod.name] = view
                try { wm.addView(view, params) } catch (e: Exception) {
                    OverlayLogger.e(TAG, "Shortcut ${mod.name}: ${e.message}", e)
                }
            }
        }
    }

    // ── Menü ───────────────────────────────────────────────────────────────

    private fun toggleMenu() {
        if (menuView != null) hideMenu() else showMenu()
    }

    private fun showMenu() {
        if (menuView != null) return
        val params = menuParams()
        menuView = composeView {
            val moduleVersion by ModuleManager.version.collectAsState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
                    .pointerInput(Unit) { detectTapGestures { hideMenu() } }
            ) {
                HileMenu(
                    onClose           = { hideMenu() },
                    moduleVersion     = moduleVersion,
                    onShortcutChanged = { refreshShortcuts() },
                    modifier          = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
        try {
            wm.addView(menuView, params)
            OverlayState.setMenuOpen(true)
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Menü eklenemedi: ${e.message}", e)
            menuView = null
        }
    }

    private fun hideMenu() {
        menuView?.let {
            try { wm.removeViewImmediate(it) } catch (_: Exception) {}
        }
        menuView = null
        OverlayState.setMenuOpen(false)
    }

    private fun removeAllOverlays() {
        hideMenu()
        listOfNotNull(fabView).plus(shortcutViews.values).forEach { v ->
            try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        }
        fabView = null
        shortcutViews.clear()
        isAttached = false
    }

    private fun safeUpdate(view: ComposeView?, params: WindowManager.LayoutParams) {
        view?.let {
            try { wm.updateViewLayout(it, params) } catch (_: Exception) {}
        }
    }

    private fun composeView(content: @Composable () -> Unit) =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent(content)
        }

    // ── Bildirim ───────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "OxClient Overlay", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_ox_logo)
        .setContentTitle("OxClient Overlay")
        .setContentText("Oyun içi HUD aktif")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

// ── FAB ───────────────────────────────────────────────────────────────────────

@Composable
private fun FabButton(onDrag: (Float, Float) -> Unit, onClick: () -> Unit) {
    var totalDist by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(OxPurple, OxPurpleDark)))
            .border(2.dp, OxPurpleLight.copy(alpha = 0.7f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { if (!isDragging) onClick() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDist = 0f },
                    onDragEnd   = { isDragging = false; if (totalDist < 15f) onClick() },
                    onDrag      = { change, offset ->
                        change.consume()
                        totalDist += abs(offset.x) + abs(offset.y)
                        onDrag(offset.x, offset.y)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text("Ox", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
    }
}

// ── Shortcut Butonu ──────────────────────────────────────────────────────────

@Composable
private fun ShortcutButton(module: BaseModule, onDrag: (Float, Float) -> Unit, onToggle: () -> Unit) {
    var enabled    by remember { mutableStateOf(module.isEnabled) }
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    val bgColor = if (enabled)
        Brush.horizontalGradient(listOf(OxPurple.copy(0.85f), OxPurpleDark.copy(0.85f)))
    else
        Brush.horizontalGradient(listOf(Color(0xBB0D0D1A), Color(0xBB1A1A2E)))

    val borderColor = if (enabled) OxPurpleLight.copy(0.9f) else OxOutline.copy(0.4f)

    Box(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (enabled) 1.5.dp else 0.8.dp, borderColor, RoundedCornerShape(10.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onToggle() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDrag = 0f },
                    onDragEnd   = { isDragging = false; if (totalDrag < 12f) onToggle() },
                    onDrag      = { change, offset ->
                        change.consume()
                        totalDrag += abs(offset.x) + abs(offset.y)
                        onDrag(offset.x, offset.y)
                    }
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(module.name, fontSize = 11.sp, fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) Color.White else OxOnSurface.copy(0.7f), fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Hile Menüsü ──────────────────────────────────────────────────────────────

private enum class MenuTab { MODULES, DEBUG }

@Composable
private fun HileMenu(onClose: () -> Unit, moduleVersion: Int, onShortcutChanged: () -> Unit, modifier: Modifier = Modifier) {
    var activeTab by remember { mutableStateOf(MenuTab.MODULES) }
    var cat by remember { mutableStateOf(ModuleCategory.COMBAT) }
    val mods = remember(moduleVersion, cat) { ModuleManager.byCategory(cat) }

    Box(
        modifier = modifier
            .fillMaxHeight().width(300.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color(0xFF16213E))))
            .border(1.dp, OxPurple.copy(0.5f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .pointerInput(Unit) { detectTapGestures { /* yut */ } }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Header
            Row(
                modifier = Modifier.fillMaxWidth().background(OxPurpleDark.copy(0.5f)).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("OxClient", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFamily = FontFamily.Monospace)
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(OxError.copy(0.2f))
                        .border(1.dp, OxError.copy(0.5f), CircleShape).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = OxError, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }

            // Debug Bar
            DebugInfoBar()
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = OxPurple.copy(0.3f))

            // ── Tab Seçici ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0x220D0D1A)).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MenuTab.entries.forEach { tab ->
                    val sel = tab == activeTab
                    val label = when (tab) {
                        MenuTab.MODULES -> "📦 Modüller"
                        MenuTab.DEBUG   -> "🐛 Debug"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) OxPurple else OxSurface)
                            .border(1.dp, if (sel) OxPurple else OxOutline.copy(0.5f), RoundedCornerShape(16.dp))
                            .clickable { activeTab = tab }
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text(label, fontSize = 11.sp,
                            color = if (sel) Color.White else OxOnSurface.copy(0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            HorizontalDivider(color = OxPurple.copy(0.2f))

            // ── İçerik ──────────────────────────────────────────────────────
            when (activeTab) {
                MenuTab.MODULES -> {
                    // Kategoriler
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModuleCategory.entries.forEach { c ->
                            val sel = c == cat
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                    .background(if (sel) OxPurple else OxSurface)
                                    .border(1.dp, if (sel) OxPurple else OxOutline, RoundedCornerShape(20.dp))
                                    .clickable { cat = c }.padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(c.displayName, fontSize = 11.sp, color = if (sel) Color.White else OxOnSurface,
                                    fontFamily = FontFamily.Monospace, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    // Modüller
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        items(mods) { mod -> ModuleCard(module = mod, onShortcutChanged = onShortcutChanged) }
                    }
                }
                MenuTab.DEBUG -> {
                    DebugLogConsole(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Debug Log Console ─────────────────────────────────────────────────────────

@Composable
private fun DebugLogConsole(modifier: Modifier = Modifier) {
    val ctx        = LocalContext.current
    val entries    by OverlayLogger.entries.collectAsState()
    val listState  = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var filterLevel by remember { mutableStateOf<OverlayLogger.Level?>(null) }

    // Yeni log gelince en alta kaydır
    LaunchedEffect(entries.size) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    val filtered = remember(entries, filterLevel) {
        if (filterLevel == null) entries else entries.filter { it.level == filterLevel }
    }

    Column(modifier = modifier.fillMaxWidth().background(Color(0xEE0A0A12))) {

        // ── Toolbar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D1A))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Level filtre butonları
            listOf(null,
                OverlayLogger.Level.DEBUG,
                OverlayLogger.Level.INFO,
                OverlayLogger.Level.WARN,
                OverlayLogger.Level.ERROR
            ).forEach { lvl ->
                val sel = filterLevel == lvl
                val label = lvl?.label ?: "ALL"
                val color = when (lvl) {
                    OverlayLogger.Level.DEBUG -> Color(0xFF888888)
                    OverlayLogger.Level.INFO  -> Color(0xFF4FC3F7)
                    OverlayLogger.Level.WARN  -> Color(0xFFFFB74D)
                    OverlayLogger.Level.ERROR -> Color(0xFFEF5350)
                    null                      -> Color(0xFFBBBBBB)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) color.copy(0.25f) else Color.Transparent)
                        .border(1.dp, if (sel) color else color.copy(0.3f), RoundedCornerShape(8.dp))
                        .clickable { filterLevel = lvl }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(label, fontSize = 9.sp, color = color,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }

            Spacer(Modifier.weight(1f))

            // Auto-scroll toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (autoScroll) OxPurple.copy(0.3f) else Color.Transparent)
                    .border(1.dp, if (autoScroll) OxPurple else OxOutline.copy(0.4f), RoundedCornerShape(8.dp))
                    .clickable { autoScroll = !autoScroll }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("↓", fontSize = 9.sp,
                    color = if (autoScroll) OxPurpleLight else OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace)
            }

            // Kopyala butonu
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x22FFFFFF))
                    .border(1.dp, OxOutline.copy(0.4f), RoundedCornerShape(8.dp))
                    .clickable {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = if (filterLevel == null)
                            OverlayLogger.allAsText()
                        else
                            filtered.joinToString("\n") { it.toPlainString() }
                        cm.setPrimaryClip(ClipData.newPlainText("OxClient Logs", text))
                        Toast.makeText(ctx, "Loglar kopyalandı (${filtered.size} satır)", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("📋", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }

            // Temizle butonu
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(OxError.copy(0.15f))
                    .border(1.dp, OxError.copy(0.4f), RoundedCornerShape(8.dp))
                    .clickable { OverlayLogger.clear() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("🗑", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        HorizontalDivider(color = OxPurple.copy(0.2f))

        // ── Log Listesi ───────────────────────────────────────────────────
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Henüz log yok...",
                    fontSize = 11.sp,
                    color = OxOnSurface.copy(0.3f),
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
                    LogEntryRow(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: OverlayLogger.LogEntry) {
    val levelColor = when (entry.level) {
        OverlayLogger.Level.DEBUG -> Color(0xFF777777)
        OverlayLogger.Level.INFO  -> Color(0xFF4FC3F7)
        OverlayLogger.Level.WARN  -> Color(0xFFFFB74D)
        OverlayLogger.Level.ERROR -> Color(0xFFEF5350)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(levelColor.copy(if (entry.level == OverlayLogger.Level.ERROR) 0.08f else 0.03f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            entry.timestamp,
            fontSize = 7.sp,
            color = OxOnSurface.copy(0.35f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.alignByBaseline()
        )
        Text(
            "${entry.level.label}/${entry.tag}",
            fontSize = 8.sp,
            color = levelColor.copy(0.85f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.alignByBaseline()
        )
        Text(
            entry.message,
            fontSize = 8.sp,
            color = OxOnSurface.copy(0.85f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f).alignByBaseline(),
            overflow = TextOverflow.Ellipsis,
            maxLines = 3
        )
    }
}

// ── Debug Bilgi Çubuğu ──────────────────────────────────────────────────────
// ✅ FIX 4: selfX, selfY, selfZ LaunchedEffect polling'e dahil edildi.
//           @Volatile Float değişkenler Compose state sistemi tarafından
//           izlenemez. Önceki kodda konum satırı doğrudan EntityTracker.selfX/Y/Z
//           okuyordu — bu değerler ilk kompozisyonda 0.0 olduğundan ekranda
//           hep "0,0,0,0,0,0" görünüyordu, hiç güncellenmiyordu.

@Composable
private fun DebugInfoBar() {
    // SessionManager StateFlow'ları — Compose doğru izliyor
    val relayActive = SessionManager.isActive.collectAsState(initial = false).value
    val relayHost   = SessionManager.connectedHostFlow.collectAsState().value
    val relayPort   = SessionManager.connectedPortFlow.collectAsState().value

    // @Volatile değişkenler — polling gerekiyor
    var isBound     by remember { mutableStateOf(SessionManager.isActive.value) }
    var selfId      by remember { mutableLongStateOf(EntityTracker.selfRuntimeId) }
    var entityCount by remember { mutableIntStateOf(0) }
    var playerCount by remember { mutableIntStateOf(0) }
    // ✅ FIX: selfX/Y/Z state olarak tanımlandı ve polling'e eklendi
    var selfX       by remember { mutableFloatStateOf(EntityTracker.selfX) }
    var selfY       by remember { mutableFloatStateOf(EntityTracker.selfY) }
    var selfZ       by remember { mutableFloatStateOf(EntityTracker.selfZ) }

    // Her 500ms'de bir güncelle
    LaunchedEffect(Unit) {
        while (true) {
            isBound     = SessionManager.isActive.value
            selfId      = EntityTracker.selfRuntimeId
            entityCount = EntityTracker.getEntities().size
            playerCount = EntityTracker.getEntities().values.count { it.isPlayer }
            // ✅ FIX: koordinatlar da güncelleniyor
            selfX       = EntityTracker.selfX
            selfY       = EntityTracker.selfY
            selfZ       = EntityTracker.selfZ
            kotlinx.coroutines.delay(500L)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xDD0D0D1A)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "⚡ OxClient",
                fontSize = 10.sp, color = OxPurpleLight,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
            Text(
                when {
                    relayActive && isBound && selfId != 0L -> "✅ AKTİF"
                    relayActive && isBound                 -> "🟡 BAĞLI"
                    relayActive                            -> "🟡 RELAY VAR"
                    else                                   -> "❌ BEKLİYOR"
                },
                fontSize = 10.sp,
                color = when {
                    relayActive && isBound && selfId != 0L -> Color(0xFF1AFF6E)
                    relayActive && isBound                 -> Color(0xFFFFDD00)
                    relayActive                            -> Color(0xFFFFAA00)
                    else                                   -> OxError
                },
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
        }

        HorizontalDivider(color = OxPurple.copy(0.2f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Relay:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text(
                if (relayActive) "✅ $relayHost:$relayPort" else "❌ Kapalı",
                fontSize = 9.sp,
                color = if (relayActive) Color(0xFF1AFF6E) else OxError.copy(0.8f),
                fontFamily = FontFamily.Monospace
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Self ID:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text(
                if (selfId == 0L) "Bekleniyor..." else "$selfId",
                fontSize = 9.sp,
                color = if (selfId == 0L) OxError.copy(0.8f) else Color(0xFF1AFF6E),
                fontFamily = FontFamily.Monospace
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Varlık/Oyuncu:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text(
                "$entityCount / $playerCount",
                fontSize = 9.sp,
                color = if (playerCount > 0) Color(0xFF1AFF6E) else OxOnSurface.copy(0.7f),
                fontFamily = FontFamily.Monospace
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enjeksiyon:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text(
                if (isBound) "✅ Bağlı" else "❌ Bağlı Değil",
                fontSize = 9.sp,
                color = if (isBound) Color(0xFF1AFF6E) else OxError,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold
            )
        }

        // ✅ FIX: artık state değişkenleri (selfX/Y/Z) kullanılıyor,
        //         EntityTracker.selfX doğrudan okunmuyor
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Konum:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text(
                "${"%.1f".format(selfX)}, ${"%.1f".format(selfY)}, ${"%.1f".format(selfZ)}",
                fontSize = 9.sp, color = OxOnSurface.copy(0.7f), fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ── Modül Kartı ──────────────────────────────────────────────────────────────

@Composable
private fun ModuleCard(module: BaseModule, onShortcutChanged: () -> Unit) {
    var enabled  by remember { mutableStateOf(module.isEnabled) }
    var expanded by remember { mutableStateOf(false) }
    val settings = module.settings

    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (enabled) OxPurple.copy(0.15f) else OxSurface)
            .border(1.dp, if (enabled) OxPurple.copy(0.5f) else OxOutline.copy(0.3f), RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).clickable {
                ModuleManager.toggle(module)
                // enabled, enabledFlow üzerinden LaunchedEffect ile güncellenir ✅
            }) {
                Text(module.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (enabled) Color.White else OxOnSurface, fontFamily = FontFamily.Monospace)
                if (module.description.isNotBlank()) {
                    Text(module.description, fontSize = 10.sp, color = OxOnSurface.copy(0.5f),
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (settings.isNotEmpty()) {
                    Text(if (expanded) "▲" else "▼", fontSize = 12.sp, color = OxPurpleLight,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(4.dp))
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        ModuleManager.toggle(module)
                        // enabled, enabledFlow üzerinden LaunchedEffect ile güncellenir ✅
                        // Önceki hata: module.isEnabled toggle'dan hemen sonra okunuyordu —
                        // race condition ile bazen yanlış değer dönüyordu.
                        onShortcutChanged()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = OxPurple, checkedThumbColor = Color.White),
                    modifier = Modifier.height(20.dp)
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && settings.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit  = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0x22000000)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                settings.forEach { s -> SettingRow(setting = s, onShortcutChanged = onShortcutChanged) }
            }
        }
    }
}

// ── Ayar Satırı ──────────────────────────────────────────────────────────────

@Composable
private fun SettingRow(setting: ModuleSetting<*>, onShortcutChanged: () -> Unit) {
    when (setting) {
        is FloatSetting -> {
            var v by remember { mutableFloatStateOf(setting.value) }
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                    Text("%.2f".format(v), fontSize = 11.sp, color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = v, onValueChange = { v = it; setting.value = it },
                    valueRange = setting.min..setting.max,
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(thumbColor = OxPurple, activeTrackColor = OxPurple, inactiveTrackColor = OxPurple.copy(0.3f))
                )
            }
        }
        is IntSetting -> {
            var v by remember { mutableFloatStateOf(setting.value.toFloat()) }
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                    Text(v.roundToInt().toString(), fontSize = 11.sp, color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = v, onValueChange = { v = it; setting.value = it.roundToInt() },
                    valueRange = setting.min.toFloat()..setting.max.toFloat(),
                    steps = (setting.max - setting.min - 1).coerceAtLeast(0),
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(thumbColor = OxPurple, activeTrackColor = OxPurple)
                )
            }
        }
        is BoolSetting -> {
            var v by remember { mutableStateOf(setting.value) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(setting.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                Switch(
                    checked = v,
                    onCheckedChange = { v = it; setting.value = it; if (setting.name == "Shortcut") onShortcutChanged() },
                    colors = SwitchDefaults.colors(checkedTrackColor = OxPurple, checkedThumbColor = Color.White)
                )
            }
        }
        is EnumSetting<*> -> {
            @Suppress("UNCHECKED_CAST")
            val es = setting as EnumSetting<Enum<*>>
            var sel by remember { mutableStateOf(es.value) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(es.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    es.values.forEach { opt ->
                        val isSel = sel == opt
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) OxPurple else OxSurfaceVar)
                                .border(1.dp, if (isSel) OxPurple.copy(0.5f) else OxOutline.copy(0.3f), RoundedCornerShape(12.dp))
                                .clickable { sel = opt; es.value = opt }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                opt.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 10.sp,
                                color = if (isSel) Color.White else OxOnSurface,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
        else -> {}
    }
}
