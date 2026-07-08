package com.oxclient.ui.overlay

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
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.session.SessionManager
import com.oxclient.ui.theme.*
import com.oxclient.utils.InventoryUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))
    }

    private val lcReg   = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lcReg

    private val ssrCtrl = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrCtrl.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var wm: android.view.WindowManager
    private var isAttached = false

    private var fabView  : ComposeView? = null
    private var menuView : ComposeView? = null
    private var totemView: ComposeView? = null
    private var espView  : ESPOverlayView? = null
    private val shortcutViews = mutableMapOf<String, ComposeView>()

    private var fabX = 50f; private var fabY = 300f
    private val shortcutPositions = mutableMapOf<String, Pair<Float, Float>>()

    override fun onCreate() {
        super.onCreate()
        ssrCtrl.performRestore(null)
        lcReg.currentState = Lifecycle.State.CREATED
        wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        createChannel()
        OverlayLogger.d(TAG, "Servis başlatıldı — ${ModuleManager.modules.size} modül")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif())
        showOverlay()
        lcReg.currentState = Lifecycle.State.RESUMED
        OverlayState.setOverlayVisible(true)
        startStatsPoller()
        return START_STICKY
    }

    override fun onDestroy() {
        lcReg.currentState = Lifecycle.State.DESTROYED
        OverlayState.setOverlayVisible(false)
        removeAllOverlays()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startStatsPoller() {
        serviceScope.launch {
            while (true) {
                OverlayState.updateSelfStats(
                    EntityTracker.selfHealth, EntityTracker.selfMaxHealth,
                    EntityTracker.selfAbsorb, EntityTracker.selfArmor, EntityTracker.selfHunger
                )
                OverlayState.updatePosition(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                OverlayState.updateEntityStats(
                    EntityTracker.count(), EntityTracker.playerCount(), EntityTracker.hostileCount()
                )
                OverlayState.updatePacketStats(
                    PacketEventBus.stats.clientToServer,
                    PacketEventBus.stats.serverToClient
                )
                OverlayState.updateActiveModuleCount(ModuleManager.enabledCount())
                val invSnapshot = EntityTracker.getInventorySnapshot()
                val totemCount = invSnapshot.count { (slot, item) ->
                    (slot in 0..35 || slot == 119) && InventoryUtil.isTotem(item)
                }
                OverlayState.updateTotemCount(totemCount)
                delay(500L)
            }
        }
    }

    private fun overlayParams(
        w: Int, h: Int, x: Float = 0f, y: Float = 0f, focusable: Boolean = false,
        // ✅ ESP canvas view'ı için: tam ekranı kaplayan bu view touchable=true olsaydı,
        // altındaki Minecraft istemcisine giden TÜM dokunuşları yutardı. touchable=false
        // ile FLAG_NOT_TOUCHABLE ekleniyor — view hâlâ çiziyor ama dokunuşlar direkt
        // arkasındaki pencereye (oyuna) geçiyor.
        touchable: Boolean = true
    ): android.view.WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE

        var flags = if (focusable)
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        else
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (!touchable) {
            flags = flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return android.view.WindowManager.LayoutParams(
            w, h, type, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            this.x  = x.roundToInt()
            this.y  = y.roundToInt()
        }
    }

    private fun showOverlay() {
        if (isAttached) return
        try {
            // ✅ ESP çizim yüzeyi — tam ekran, dokunulamaz (touchable=false), FAB/menüden
            // ÖNCE eklenerek z-sırasında en altta kalıyor; FAB/shortcut/menü hep üstünde
            // görünür kalır. Bu, ESP.render()'ı ekrana basan tek yer.
            val espParams = overlayParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                touchable = false
            )
            espView = ESPOverlayView(this)
            wm.addView(espView, espParams)
            espView?.startRenderLoop()

            val fabParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                fabX, fabY
            )
            fabView = composeView {
                FabButton(
                    onDrag  = { dx, dy ->
                        fabX += dx; fabY += dy
                        fabParams.x = fabX.roundToInt(); fabParams.y = fabY.roundToInt()
                        safeUpdate(fabView, fabParams)
                    },
                    onClick = { toggleMenu() }
                )
            }
            wm.addView(fabView, fabParams)

            val totemParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                16f, 16f
            )
            totemView = composeView { TotemCounterIcon() }
            wm.addView(totemView, totemParams)

            refreshShortcuts()
            isAttached = true
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Overlay eklenemedi: ${e.message}", e)
        }
    }

    private fun refreshShortcuts() {
        val active = ModuleManager.shortcutModules().map { it.name }.toSet()
        shortcutViews.entries.filter { it.key !in active }.forEach { (name, view) ->
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
            shortcutViews.remove(name)
        }
        ModuleManager.shortcutModules().forEach { mod ->
            if (mod.name !in shortcutViews) {
                val idx = ModuleManager.shortcutModules().indexOf(mod)
                val pos = shortcutPositions.getOrPut(mod.name) { 50f to (420f + idx * 50f) }
                val params = overlayParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    pos.first, pos.second
                )
                val view = composeView {
                    ShortcutButton(
                        module   = mod,
                        onDrag   = { dx, dy ->
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

    private fun toggleMenu() { if (menuView != null) hideMenu() else showMenu() }

    private fun showMenu() {
        if (menuView != null) return
        val params = overlayParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            focusable = true
        )
        menuView = composeView {
            val moduleVersion by ModuleManager.version.collectAsState()
            Box(
                modifier = Modifier.fillMaxSize()
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
        menuView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        menuView = null
        OverlayState.setMenuOpen(false)
    }

    private fun removeAllOverlays() {
        hideMenu()
        listOfNotNull(fabView, totemView, espView).plus(shortcutViews.values).forEach { v ->
            try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        }
        fabView = null; totemView = null; espView = null; shortcutViews.clear(); isAttached = false
    }

    private fun safeUpdate(view: ComposeView?, params: android.view.WindowManager.LayoutParams) {
        view?.let { try { wm.updateViewLayout(it, params) } catch (_: Exception) {} }
    }

    private fun composeView(content: @Composable () -> Unit) =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent(content)
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "OxClient Overlay", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_ox_logo)
        .setContentTitle("OxClient Overlay")
        .setContentText("HUD aktif — ${ModuleManager.enabledCount()} modül açık")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

@Composable
private fun TotemCounterIcon() {
    val count = OverlayState.totemCount

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0A0A))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$count", fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) Color(0xFFCCCCCC) else Color(0xFFFF5555)
        )
    }
}

@Composable
private fun FabButton(onDrag: (Float, Float) -> Unit, onClick: () -> Unit) {
    var totalDist  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val activeCount = OverlayState.activeModuleCount
    val isLowHp     = OverlayState.isLowHealth

    Box(
        modifier = Modifier.size(52.dp).clip(CircleShape)
            .background(Brush.radialGradient(
                if (isLowHp) listOf(Color(0xFFCC0000), Color(0xFF880000))
                else         listOf(OxPurple, OxPurpleDark)
            ))
            .border(2.dp, if (isLowHp) Color(0xFFFF4444) else OxPurpleLight.copy(0.7f), CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onClick() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDist = 0f },
                    onDragEnd   = { isDragging = false; if (totalDist < 15f) onClick() },
                    onDrag      = { c, o -> c.consume(); totalDist += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Ox", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
            if (activeCount > 0)
                Text("$activeCount", color = Color(0xFFAAFFAA),
                    fontSize = 7.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ShortcutButton(module: BaseModule, onDrag: (Float, Float) -> Unit, onToggle: () -> Unit) {
    var enabled    by remember { mutableStateOf(module.isEnabled) }
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    val bg = if (enabled)
        Brush.horizontalGradient(listOf(OxPurple.copy(0.85f), OxPurpleDark.copy(0.85f)))
    else
        Brush.horizontalGradient(listOf(Color(0xBB0D0D1A), Color(0xBB1A1A2E)))

    Box(
        modifier = Modifier.wrapContentSize()
            .clip(RoundedCornerShape(10.dp)).background(bg)
            .border(if (enabled) 1.5.dp else 0.8.dp,
                if (enabled) OxPurpleLight.copy(0.9f) else OxOutline.copy(0.4f),
                RoundedCornerShape(10.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onToggle() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDrag = 0f },
                    onDragEnd   = { isDragging = false; if (totalDrag < 12f) onToggle() },
                    onDrag      = { c, o -> c.consume(); totalDrag += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(module.name, fontSize = 11.sp,
            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) Color.White else OxOnSurface.copy(0.7f),
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class MenuTab { MODULES, STATS, DEBUG }

@Composable
private fun HileMenu(
    onClose          : () -> Unit,
    moduleVersion    : Int,
    onShortcutChanged: () -> Unit,
    modifier         : Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(MenuTab.MODULES) }
    var cat       by remember { mutableStateOf(ModuleCategory.COMBAT) }
    val mods      = remember(moduleVersion, cat) { ModuleManager.byCategory(cat) }

    Box(
        modifier = modifier.fillMaxHeight().width(300.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color(0xFF16213E))))
            .border(1.dp, OxPurple.copy(0.5f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth()
                .background(OxPurpleDark.copy(0.5f)).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("OxClient", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White, fontFamily = FontFamily.Monospace)
                Box(modifier = Modifier.size(28.dp).clip(CircleShape)
                    .background(OxError.copy(0.2f))
                    .border(1.dp, OxError.copy(0.5f), CircleShape)
                    .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = OxError, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }

            DebugInfoBar()
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = OxPurple.copy(0.3f))

            Row(modifier = Modifier.fillMaxWidth().background(Color(0x220D0D1A))
                .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MenuTab.entries.forEach { tab ->
                    val sel   = tab == activeTab
                    val label = when (tab) {
                        MenuTab.MODULES -> "📦 Modüller"
                        MenuTab.STATS   -> "📊 Stats"
                        MenuTab.DEBUG   -> "🐛 Debug"
                    }
                    Box(modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (sel) OxPurple else OxSurface)
                        .border(1.dp, if (sel) OxPurple else OxOutline.copy(0.5f), RoundedCornerShape(16.dp))
                        .clickable { activeTab = tab }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(label, fontSize = 10.sp,
                            color = if (sel) Color.White else OxOnSurface.copy(0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            HorizontalDivider(color = OxPurple.copy(0.2f))

            when (activeTab) {
                MenuTab.MODULES -> {
                    Row(modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModuleCategory.entries.forEach { c ->
                            val sel = c == cat
                            Box(modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                .background(if (sel) OxPurple else OxSurface)
                                .border(1.dp, if (sel) OxPurple else OxOutline, RoundedCornerShape(20.dp))
                                .clickable { cat = c }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(c.displayName, fontSize = 11.sp,
                                    color = if (sel) Color.White else OxOnSurface,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        items(mods) { mod ->
                            ModuleCard(module = mod, onShortcutChanged = onShortcutChanged)
                        }
                    }
                }
                MenuTab.STATS -> StatsPanel(modifier = Modifier.weight(1f))
                MenuTab.DEBUG -> DebugLogConsole(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatsPanel(modifier: Modifier = Modifier) {
    val health     = OverlayState.selfHealth
    val maxHealth  = OverlayState.selfMaxHealth
    val absorb     = OverlayState.selfAbsorb
    val armor      = OverlayState.selfArmor
    val hunger     = OverlayState.selfHunger
    val x          = OverlayState.selfX
    val y          = OverlayState.selfY
    val z          = OverlayState.selfZ
    val entities   = OverlayState.entityCount
    val players    = OverlayState.playerCount
    val hostiles   = OverlayState.hostileCount
    val espBlocks  = OverlayState.espBlockCount
    val cToS       = OverlayState.packetCtoS
    val sToC       = OverlayState.packetStoC
    val activeMods = OverlayState.activeModuleCount

    LazyColumn(modifier = modifier.fillMaxWidth().background(Color(0xEE0A0A12)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StatSection("❤ Oyuncu") {
                val hpPct = if (maxHealth > 0f) health / maxHealth else 0f
                val hpColor = when { hpPct > 0.6f -> Color(0xFF1AFF6E); hpPct > 0.3f -> Color(0xFFFFDD00); else -> Color(0xFFFF4444) }
                StatRow("Can", "${"%.1f".format(health)} / ${"%.1f".format(maxHealth)}", hpColor)
                LinearProgressIndicator(progress = { hpPct },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = hpColor, trackColor = hpColor.copy(0.2f))
                if (absorb > 0f) StatRow("Emilim", "+${"%.1f".format(absorb)}", Color(0xFFFFDD00))
                StatRow("Zırh",   "${"%.0f".format(armor)}",  OxOnSurface.copy(0.8f))
                StatRow("Açlık", "${"%.0f".format(hunger)}/20", Color(0xFFFFAA44))
            }
        }
        item {
            StatSection("📍 Konum") {
                StatRow("X", "${"%.2f".format(x)}", OxOnSurface.copy(0.8f))
                StatRow("Y", "${"%.2f".format(y)}", OxOnSurface.copy(0.8f))
                StatRow("Z", "${"%.2f".format(z)}", OxOnSurface.copy(0.8f))
            }
        }
        item {
            StatSection("👾 Entity") {
                StatRow("Toplam",  "$entities", OxOnSurface.copy(0.8f))
                StatRow("Oyuncu", "$players",  if (players > 0) Color(0xFF4FC3F7) else OxOnSurface.copy(0.5f))
                StatRow("Düşman", "$hostiles", if (hostiles > 0) Color(0xFFFF6B6B) else OxOnSurface.copy(0.5f))
            }
        }
        item {
            StatSection("📦 ESP") {
                StatRow("Takip edilen blok", "$espBlocks",
                    if (espBlocks > 0) Color(0xFFFFAA00) else OxOnSurface.copy(0.5f))
            }
        }
        item {
            StatSection("📡 Paket") {
                StatRow("C→S", "$cToS", OxOnSurface.copy(0.7f))
                StatRow("S→C", "$sToC", OxOnSurface.copy(0.7f))
            }
        }
        item {
            StatSection("⚡ Modüller") {
                StatRow("Aktif", "$activeMods / ${ModuleManager.modules.size}",
                    if (activeMods > 0) OxPurpleLight else OxOnSurface.copy(0.5f))
            }
        }
    }
}

@Composable
private fun StatSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF))
        .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = OxPurpleLight, fontFamily = FontFamily.Monospace)
        HorizontalDivider(color = OxPurple.copy(0.2f), modifier = Modifier.padding(vertical = 2.dp))
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 10.sp, color = valueColor, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModuleCard(module: BaseModule, onShortcutChanged: () -> Unit) {
    var enabled  by remember { mutableStateOf(module.isEnabled) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (enabled) OxPurple.copy(0.15f) else OxSurface)
        .border(1.dp, if (enabled) OxPurple.copy(0.5f) else OxOutline.copy(0.3f), RoundedCornerShape(10.dp))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).clickable { ModuleManager.toggle(module) }) {
                Text(module.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (enabled) Color.White else OxOnSurface, fontFamily = FontFamily.Monospace)
                if (module.description.isNotBlank())
                    Text(module.description, fontSize = 10.sp, color = OxOnSurface.copy(0.5f),
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (module.settings.isNotEmpty())
                    Text(if (expanded) "▲" else "▼", fontSize = 12.sp, color = OxPurpleLight,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(4.dp))
                Switch(checked = enabled,
                    onCheckedChange = { ModuleManager.toggle(module); onShortcutChanged() },
                    colors = SwitchDefaults.colors(checkedTrackColor = OxPurple, checkedThumbColor = Color.White),
                    modifier = Modifier.height(20.dp))
            }
        }
        AnimatedVisibility(visible = expanded && module.settings.isNotEmpty(),
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth().background(Color(0x22000000))
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                module.settings.forEach { s -> SettingRow(setting = s, onShortcutChanged = onShortcutChanged) }
            }
        }
    }
}

@Composable
private fun SettingRow(setting: ModuleSetting<*>, onShortcutChanged: () -> Unit) {
    when (setting) {
        is FloatSetting -> {
            var v by remember { mutableFloatStateOf(setting.value) }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                    Text("%.2f".format(v), fontSize = 11.sp, color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                }
                Slider(value = v, onValueChange = { v = it; setting.value = it },
                    valueRange = setting.min..setting.max, modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(thumbColor = OxPurple, activeTrackColor = OxPurple,
                        inactiveTrackColor = OxPurple.copy(0.3f)))
            }
        }
        is IntSetting -> {
            var v by remember { mutableFloatStateOf(setting.value.toFloat()) }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                    Text(v.roundToInt().toString(), fontSize = 11.sp, color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                }
                Slider(value = v, onValueChange = { v = it; setting.value = it.roundToInt() },
                    valueRange = setting.min.toFloat()..setting.max.toFloat(),
                    steps = (setting.max - setting.min - 1).coerceAtLeast(0),
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(thumbColor = OxPurple, activeTrackColor = OxPurple))
            }
        }
        is BoolSetting -> {
            var v by remember { mutableStateOf(setting.value) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(setting.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                Switch(checked = v,
                    onCheckedChange = { v = it; setting.value = it; if (setting.name == "Shortcut") onShortcutChanged() },
                    colors = SwitchDefaults.colors(checkedTrackColor = OxPurple, checkedThumbColor = Color.White))
            }
        }
        is EnumSetting<*> -> {
            @Suppress("UNCHECKED_CAST")
            val es = setting as EnumSetting<Enum<*>>
            var sel by remember { mutableStateOf(es.value) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(es.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    es.values.forEach { opt ->
                        val isSel = sel == opt
                        Box(modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) OxPurple else OxSurfaceVar)
                            .border(1.dp, if (isSel) OxPurple.copy(0.5f) else OxOutline.copy(0.3f), RoundedCornerShape(12.dp))
                            .clickable { sel = opt; es.value = opt }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(opt.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 10.sp, color = if (isSel) Color.White else OxOnSurface,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        is StringSetting -> {
            var v by remember { mutableStateOf(setting.value) }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(setting.name, fontSize = 11.sp, color = OxOnSurface,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.4f))
                OutlinedTextField(value = v, onValueChange = { v = it; setting.value = it },
                    singleLine = true, modifier = Modifier.weight(0.6f).height(40.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp, color = OxOnSurface),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OxPurple,
                        unfocusedBorderColor = OxOutline.copy(0.5f)))
            }
        }
        else -> {}
    }
}

@Composable
private fun DebugInfoBar() {
    val relayActive by SessionManager.isActive.collectAsState()
    val relayHost   by SessionManager.connectedHostFlow.collectAsState()
    val relayPort   by SessionManager.connectedPortFlow.collectAsState()

    var selfId      by remember { mutableLongStateOf(0L) }
    var entityCount by remember { mutableIntStateOf(0) }
    var playerCount by remember { mutableIntStateOf(0) }
    var selfHp      by remember { mutableFloatStateOf(20f) }
    var selfX       by remember { mutableFloatStateOf(0f) }
    var selfY       by remember { mutableFloatStateOf(0f) }
    var selfZ       by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            selfId      = EntityTracker.selfRuntimeId
            entityCount = EntityTracker.count()
            playerCount = EntityTracker.playerCount()
            selfHp      = EntityTracker.selfHealth
            selfX       = EntityTracker.selfX
            selfY       = EntityTracker.selfY
            selfZ       = EntityTracker.selfZ
            delay(500L)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().background(Color(0xDD0D0D1A))
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("⚡ OxClient", fontSize = 10.sp, color = OxPurpleLight,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(when {
                relayActive && selfId != 0L -> "✅ AKTİF"
                relayActive                 -> "🟡 BAĞLI"
                else                        -> "❌ BEKLİYOR"
            }, fontSize = 10.sp,
                color = when {
                    relayActive && selfId != 0L -> Color(0xFF1AFF6E)
                    relayActive                 -> Color(0xFFFFDD00)
                    else                        -> OxError
                },
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = OxPurple.copy(0.2f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Relay:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text(if (relayActive) "$relayHost:$relayPort" else "Kapalı", fontSize = 9.sp,
                color = if (relayActive) Color(0xFF1AFF6E) else OxError.copy(0.8f),
                fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Can:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text("${"%.1f".format(selfHp)}", fontSize = 9.sp,
                color = if (selfHp <= 6f) Color(0xFFFF4444) else Color(0xFF1AFF6E),
                fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Konum:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text("${"%.1f".format(selfX)}, ${"%.1f".format(selfY)}, ${"%.1f".format(selfZ)}",
                fontSize = 9.sp, color = OxOnSurface.copy(0.7f), fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Entity/Oyuncu:", fontSize = 9.sp, color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
            Text("$entityCount / $playerCount", fontSize = 9.sp,
                color = if (playerCount > 0) Color(0xFF1AFF6E) else OxOnSurface.copy(0.7f),
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun DebugLogConsole(modifier: Modifier = Modifier) {
    val ctx         = LocalContext.current
    val entries    by OverlayLogger.entries.collectAsState()
    val levelCounts by OverlayLogger.levelCounts.collectAsState()
    val listState   = rememberLazyListState()
    var autoScroll  by remember { mutableStateOf(true) }
    var filterLevel by remember { mutableStateOf<OverlayLogger.Level?>(null) }
    var filterTag   by remember { mutableStateOf("") }
    var showTagInput by remember { mutableStateOf(false) }

    LaunchedEffect(entries.size) {
        if (autoScroll && entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    val filtered = remember(entries, filterLevel, filterTag) {
        OverlayLogger.filterByLevelAndTag(filterLevel, filterTag.takeIf { it.isNotBlank() })
    }

    Column(modifier = modifier.fillMaxWidth().background(Color(0xEE0A0A12))) {
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0D0D1A))
            .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                null to "ALL",
                OverlayLogger.Level.DEBUG to "D",
                OverlayLogger.Level.INFO  to "I",
                OverlayLogger.Level.WARN  to "W",
                OverlayLogger.Level.ERROR to "E"
            ).forEach { (lvl, label) ->
                val sel   = filterLevel == lvl
                val count = if (lvl == null) entries.size else (levelCounts[lvl] ?: 0)
                val color = when (lvl) {
                    OverlayLogger.Level.DEBUG   -> Color(0xFF888888)
                    OverlayLogger.Level.INFO    -> Color(0xFF4FC3F7)
                    OverlayLogger.Level.WARN    -> Color(0xFFFFB74D)
                    OverlayLogger.Level.ERROR   -> Color(0xFFEF5350)
                    OverlayLogger.Level.VERBOSE -> Color(0xFF666666)
                    null                        -> Color(0xFFBBBBBB)
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (sel) color.copy(0.25f) else Color.Transparent)
                    .border(1.dp, if (sel) color else color.copy(0.3f), RoundedCornerShape(8.dp))
                    .clickable { filterLevel = lvl }
                    .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("$label${if (count > 0) "($count)" else ""}",
                        fontSize = 8.sp, color = color, fontFamily = FontFamily.Monospace,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (showTagInput) OxPurple.copy(0.3f) else Color.Transparent)
                .border(1.dp, OxPurple.copy(if (showTagInput) 0.8f else 0.3f), RoundedCornerShape(8.dp))
                .clickable { showTagInput = !showTagInput }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("🔍", fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (autoScroll) OxPurple.copy(0.3f) else Color.Transparent)
                .border(1.dp, if (autoScroll) OxPurple else OxOutline.copy(0.4f), RoundedCornerShape(8.dp))
                .clickable { autoScroll = !autoScroll }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("↓", fontSize = 9.sp,
                    color = if (autoScroll) OxPurpleLight else OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace)
            }
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(Color(0x22FFFFFF))
                .border(1.dp, OxOutline.copy(0.4f), RoundedCornerShape(8.dp))
                .clickable {
                    val cm   = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = if (filterLevel == null && filterTag.isBlank()) OverlayLogger.allAsText()
                               else filtered.joinToString("\n") { it.toPlainString() }
                    cm.setPrimaryClip(ClipData.newPlainText("OxClient Logs", text))
                    Toast.makeText(ctx, "Kopyalandı (${filtered.size} satır)", Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("📋", fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(OxError.copy(0.15f))
                .border(1.dp, OxError.copy(0.4f), RoundedCornerShape(8.dp))
                .clickable { OverlayLogger.clear() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("🗑", fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
        }

        if (showTagInput) {
            OutlinedTextField(value = filterTag, onValueChange = { filterTag = it },
                placeholder = { Text("Tag filtrele...", fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, color = OxOnSurface.copy(0.4f)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp, vertical = 2.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, color = OxOnSurface),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OxPurple,
                    unfocusedBorderColor = OxOutline.copy(0.5f)))
        }

        HorizontalDivider(color = OxPurple.copy(0.2f))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Henüz log yok...", fontSize = 11.sp,
                    color = OxOnSurface.copy(0.3f), fontFamily = FontFamily.Monospace)
            }
        } else {
            LazyColumn(state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
                    val levelColor = when (entry.level) {
                        OverlayLogger.Level.VERBOSE -> Color(0xFF555555)
                        OverlayLogger.Level.DEBUG   -> Color(0xFF777777)
                        OverlayLogger.Level.INFO    -> Color(0xFF4FC3F7)
                        OverlayLogger.Level.WARN    -> Color(0xFFFFB74D)
                        OverlayLogger.Level.ERROR   -> Color(0xFFEF5350)
                    }
                    Row(modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(levelColor.copy(if (entry.level == OverlayLogger.Level.ERROR) 0.08f else 0.03f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(entry.timestamp, fontSize = 7.sp, color = OxOnSurface.copy(0.35f),
                            fontFamily = FontFamily.Monospace, modifier = Modifier.alignByBaseline())
                        Text("${entry.level.label}/${entry.tag}", fontSize = 8.sp,
                            color = levelColor.copy(0.85f), fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
                        Text(entry.message, fontSize = 8.sp, color = OxOnSurface.copy(0.85f),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                            overflow = TextOverflow.Ellipsis, maxLines = 3)
                    }
                }
            }
        }
    }
}
