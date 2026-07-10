package com.oxclient.ui.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        w: Int, h: Int, x: Float = 0f, y: Float = 0f,
        focusable: Boolean = false, touchable: Boolean = true
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

        if (!touchable) flags = flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

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
        } catch (_: Exception) {}
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
                try { wm.addView(view, params) } catch (_: Exception) {}
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
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
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
        } catch (_: Exception) {
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
        .setContentText("HUD aktif")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}

// ── Totem counter (sol üst köşe) ──────────────────────────────────────────────

@Composable
private fun TotemCounterIcon() {
    val count = OverlayState.totemCount
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC1E2340))
            .border(1.dp, Color(0xFF2D3561), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$count",
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) OxOnBackground else OxError
        )
    }
}

// ── FAB ───────────────────────────────────────────────────────────────────────

@Composable
private fun FabButton(onDrag: (Float, Float) -> Unit, onClick: () -> Unit) {
    var totalDist  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val activeCount = OverlayState.activeModuleCount

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(listOf(OxAccent, OxAccentDark))
            )
            .border(2.dp, OxAccentLight.copy(0.7f), CircleShape)
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
                Text("$activeCount", color = OxOnBackground.copy(0.8f),
                    fontSize = 7.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Shortcut button (modern pill) ─────────────────────────────────────────────

@Composable
private fun ShortcutButton(module: BaseModule, onDrag: (Float, Float) -> Unit, onToggle: () -> Unit) {
    var enabled    by remember { mutableStateOf(module.isEnabled) }
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    val bgColor = if (enabled) OxSurfaceVar else OxSurface
    val borderColor = if (enabled) OxAccentLight.copy(0.9f) else OxOutline.copy(0.5f)
    val textColor   = if (enabled) Color.White else OxOnSurface.copy(0.6f)

    Box(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(50.dp))
            .background(bgColor)
            .border(if (enabled) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(50.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onToggle() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDrag = 0f },
                    onDragEnd   = { isDragging = false; if (totalDrag < 12f) onToggle() },
                    onDrag      = { c, o -> c.consume(); totalDrag += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                )
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            module.name,
            fontSize = 12.sp,
            fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Ana menü ──────────────────────────────────────────────────────────────────

@Composable
private fun HileMenu(
    onClose          : () -> Unit,
    moduleVersion    : Int,
    onShortcutChanged: () -> Unit,
    modifier         : Modifier = Modifier
) {
    var cat  by remember { mutableStateOf(ModuleCategory.COMBAT) }
    val mods = remember(moduleVersion, cat) { ModuleManager.byCategory(cat) }
    val relayActive by SessionManager.isActive.collectAsState()

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(
                Brush.verticalGradient(listOf(OxBackground, Color(0xFF141830)))
            )
            .border(1.dp, OxOutlineStrong, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OxSurface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OxClient", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                        color = OxOnBackground)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (relayActive) OxSuccess.copy(0.2f) else OxError.copy(0.15f))
                            .border(1.dp,
                                if (relayActive) OxSuccess.copy(0.5f) else OxError.copy(0.4f),
                                RoundedCornerShape(20.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(if (relayActive) "Bagli" else "Bagli degil",
                            fontSize = 9.sp,
                            color = if (relayActive) OxSuccess else OxError,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(OxError.copy(0.15f))
                        .border(1.dp, OxError.copy(0.4f), CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("x", color = OxError, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = OxOutlineStrong)

            // ── Kategori sekmeler (yatay scroll) ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OxSurface.copy(0.5f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModuleCategory.entries.forEach { c ->
                    val sel = c == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50.dp))
                            .background(if (sel) OxSurfaceVar else Color.Transparent)
                            .border(1.dp,
                                if (sel) OxOutlineStrong else OxOutline,
                                RoundedCornerShape(50.dp))
                            .clickable { cat = c }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(c.displayName, fontSize = 11.sp,
                            color = if (sel) OxOnBackground else OxOnSurface,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }

            HorizontalDivider(color = OxOutlineStrong)

            // ── Modül listesi ─────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                items(mods) { mod ->
                    ModuleCard(module = mod, onShortcutChanged = onShortcutChanged)
                }
            }
        }
    }
}

// ── Modül kartı ───────────────────────────────────────────────────────────────

@Composable
private fun ModuleCard(module: BaseModule, onShortcutChanged: () -> Unit) {
    var enabled  by remember { mutableStateOf(module.isEnabled) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) OxSurfaceVar else OxSurface)
            .border(1.dp,
                if (enabled) OxOutlineStrong else OxOutline.copy(0.6f),
                RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (module.settings.isNotEmpty()) expanded = !expanded
                    else ModuleManager.toggle(module)
                }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                module.name,
                fontSize = 14.sp,
                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) OxOnBackground else OxOnSurface,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = { ModuleManager.toggle(module); onShortcutChanged() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor   = OxAccent,
                    checkedThumbColor   = Color.White,
                    uncheckedTrackColor = OxOutlineStrong,
                    uncheckedThumbColor = OxOnSurfaceDim
                ),
                modifier = Modifier.height(20.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded && module.settings.isNotEmpty(),
            enter   = expandVertically() + fadeIn(),
            exit    = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x22000000))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                module.settings.forEach { s ->
                    SettingRow(setting = s, onShortcutChanged = onShortcutChanged)
                }
            }
        }
    }
}

// ── Ayar satırları ────────────────────────────────────────────────────────────

@Composable
private fun SettingRow(setting: ModuleSetting<*>, onShortcutChanged: () -> Unit) {
    when (setting) {
        is FloatSetting -> {
            var v by remember { mutableFloatStateOf(setting.value) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 12.sp, color = OxOnSurface)
                    Text("%.2f".format(v), fontSize = 12.sp, color = OxAccentLight,
                        fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = v,
                    onValueChange = { v = it; setting.value = it },
                    valueRange = setting.min..setting.max,
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor         = OxAccentLight,
                        activeTrackColor   = OxAccent,
                        inactiveTrackColor = OxOutlineStrong
                    )
                )
            }
        }
        is IntSetting -> {
            var v by remember { mutableFloatStateOf(setting.value.toFloat()) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 12.sp, color = OxOnSurface)
                    Text(v.roundToInt().toString(), fontSize = 12.sp, color = OxAccentLight,
                        fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = v,
                    onValueChange = { v = it; setting.value = it.roundToInt() },
                    valueRange = setting.min.toFloat()..setting.max.toFloat(),
                    steps = (setting.max - setting.min - 1).coerceAtLeast(0),
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor         = OxAccentLight,
                        activeTrackColor   = OxAccent,
                        inactiveTrackColor = OxOutlineStrong
                    )
                )
            }
        }
        is BoolSetting -> {
            var v by remember { mutableStateOf(setting.value) }
            val isShortcut = setting.name == "Shortcut"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(setting.name, fontSize = 12.sp, color = OxOnSurface)
                if (isShortcut) {
                    ShortcutToggle(checked = v) {
                        v = it; setting.value = it; onShortcutChanged()
                    }
                } else {
                    Switch(
                        checked = v,
                        onCheckedChange = { v = it; setting.value = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor   = OxAccent,
                            checkedThumbColor   = Color.White,
                            uncheckedTrackColor = OxOutlineStrong,
                            uncheckedThumbColor = OxOnSurfaceDim
                        )
                    )
                }
            }
        }
        is EnumSetting<*> -> {
            @Suppress("UNCHECKED_CAST")
            val es = setting as EnumSetting<Enum<*>>
            var sel by remember { mutableStateOf(es.value) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(es.name, fontSize = 12.sp, color = OxOnSurface)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    es.values.forEach { opt ->
                        val isSel = sel == opt
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(if (isSel) OxSurfaceRaised else OxSurface)
                                .border(1.dp,
                                    if (isSel) OxOutlineStrong else OxOutline,
                                    RoundedCornerShape(50.dp))
                                .clickable { sel = opt; es.value = opt }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                opt.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp,
                                color = if (isSel) OxOnBackground else OxOnSurface,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
        is StringSetting -> {
            var v by remember { mutableStateOf(setting.value) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(setting.name, fontSize = 12.sp, color = OxOnSurface,
                    modifier = Modifier.weight(0.4f))
                OutlinedTextField(
                    value = v,
                    onValueChange = { v = it; setting.value = it },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f).height(40.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = OxOnSurface),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = OxAccent,
                        unfocusedBorderColor = OxOutline
                    )
                )
            }
        }
        else -> {}
    }
}

// ── Shortcut toggle (modern pill checkbox) ────────────────────────────────────

@Composable
private fun ShortcutToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(if (checked) OxSurfaceRaised else Color.Transparent)
            .border(1.5.dp,
                if (checked) OxOutlineStrong else OxOutline,
                RoundedCornerShape(50.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (checked) "Acik" else "Kapali",
            fontSize = 11.sp,
            color = if (checked) OxOnBackground else OxOnSurfaceDim,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
