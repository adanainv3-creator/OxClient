package com.oxclient.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
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
import com.oxclient.module.*
import com.oxclient.ui.theme.*
import kotlin.math.abs

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

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }

    // ── Lifecycle / SavedState ────────────────────────────────────────────────

    private val lcReg  = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lcReg

    private val ssrCtrl = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrCtrl.savedStateRegistry

    // ── Window ────────────────────────────────────────────────────────────────

    private lateinit var wm: WindowManager
    private var fabView  : ComposeView? = null
    private var menuView : ComposeView? = null

    // Dinamik modül kısa yol butonları: her modül için ayrı view + params
    private val shortcutViews  = mutableListOf<ComposeView>()
    private val shortcutParams = mutableListOf<WindowManager.LayoutParams>()

    private var isAttached = false

    // FAB konumu
    private var fabX = 50; private var fabY = 300

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        ssrCtrl.performRestore(null)
        lcReg.currentState = Lifecycle.State.CREATED
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        Log.d(TAG, "onCreate")
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
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Window helpers ────────────────────────────────────────────────────────

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun wrapParams(x: Int = 0, y: Int = 0) =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay() {
        if (isAttached) return

        // FAB
        val fabParams = wrapParams(fabX, fabY)
        fabView = makeComposeView {
            DraggableFab(
                onDrag = { dx, dy ->
                    fabX += dx.toInt(); fabY += dy.toInt()
                    fabParams.x = fabX;  fabParams.y = fabY
                    runCatching { wm.updateViewLayout(fabView, fabParams) }
                },
                onClick = { showMenu() }
            )
        }

        // Kayıtlı her modül için sürüklenebilir kısa yol butonu
        val modules = ModuleManager.modules
        modules.forEachIndexed { i, module ->
            val p = wrapParams(20, 500 + i * 60)
            shortcutParams.add(p)
            val view = makeComposeView {
                DraggableModuleButton(module = module, onDrag = { dx, dy ->
                    p.x += dx.toInt(); p.y += dy.toInt()
                    runCatching { wm.updateViewLayout(shortcutViews.getOrNull(i), p) }
                })
            }
            shortcutViews.add(view)
        }

        runCatching {
            wm.addView(fabView, fabParams)
            shortcutViews.forEachIndexed { i, v -> wm.addView(v, shortcutParams[i]) }
            isAttached = true
            Log.d(TAG, "Overlay eklendi (${shortcutViews.size} modül butonu)")
        }.onFailure { Log.e(TAG, "Overlay eklenemedi: ${it.message}") }
    }

    private fun showMenu() {
        if (menuView != null) return
        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        menuView = makeComposeView {
            val version by ModuleManager.version.collectAsState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
                    .pointerInput(Unit) { detectTapGestures { hideMenu() } }
            ) {
                HileMenu(
                    onClose       = { hideMenu() },
                    moduleVersion = version,
                    modifier      = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
        runCatching {
            wm.addView(menuView, menuParams)
            OverlayState.setMenuOpen(true)
        }.onFailure { Log.e(TAG, "Menü eklenemedi: ${it.message}") }
    }

    private fun hideMenu() {
        menuView?.let { runCatching { wm.removeViewImmediate(it) } }
        menuView = null
        OverlayState.setMenuOpen(false)
    }

    private fun removeOverlay() {
        hideMenu()
        runCatching { fabView?.let { wm.removeViewImmediate(it) } }
        shortcutViews.forEach { runCatching { wm.removeViewImmediate(it) } }
        fabView = null; shortcutViews.clear(); shortcutParams.clear()
        isAttached = false
        Log.d(TAG, "Overlay kaldırıldı")
    }

    // ── ComposeView factory ───────────────────────────────────────────────────

    private fun makeComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent { content() }
        }

    // ── Composables ───────────────────────────────────────────────────────────

    @Composable
    private fun DraggableFab(onDrag: (Float, Float) -> Unit, onClick: () -> Unit) {
        var totalDist by remember { mutableFloatStateOf(0f) }
        var dragging  by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(OxPurple, OxPurpleDark)))
                .border(2.dp, OxPurpleLight.copy(alpha = 0.7f), CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { if (!dragging) onClick() }) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true; totalDist = 0f },
                        onDragEnd   = { dragging = false; if (totalDist < 15f) onClick() },
                        onDrag      = { c, o -> c.consume(); totalDist += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Ox", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun DraggableModuleButton(module: BaseModule, onDrag: (Float, Float) -> Unit) {
        var enabled      by remember { mutableStateOf(module.isEnabled) }
        var totalDrag    by remember { mutableFloatStateOf(0f) }
        var dragging     by remember { mutableStateOf(false) }

        LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

        val bg = if (enabled)
            Brush.horizontalGradient(listOf(OxPurple.copy(0.85f), OxPurpleDark.copy(0.85f)))
        else
            Brush.horizontalGradient(listOf(Color(0xBB0D0D1A), Color(0xBB1A1A2E)))

        Box(
            modifier = Modifier
                .wrapContentSize()
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .border(
                    if (enabled) 1.5.dp else 0.8.dp,
                    if (enabled) OxPurpleLight.copy(0.9f) else OxOutline.copy(0.4f),
                    RoundedCornerShape(10.dp)
                )
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { if (!dragging) { ModuleManager.toggle(module); enabled = module.isEnabled } })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true; totalDrag = 0f },
                        onDragEnd   = { dragging = false; if (totalDrag < 12f) { ModuleManager.toggle(module); enabled = module.isEnabled } },
                        onDrag      = { c, o -> c.consume(); totalDrag += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = module.name,
                fontSize   = 11.sp,
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
                color      = if (enabled) Color.White else OxOnSurface.copy(0.7f),
                fontFamily = FontFamily.Monospace,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun HileMenu(onClose: () -> Unit, moduleVersion: Int, modifier: Modifier = Modifier) {
        var cat  by remember { mutableStateOf(ModuleCategory.COMBAT) }
        val mods = remember(moduleVersion, cat) { ModuleManager.byCategory(cat) }

        Box(
            modifier = modifier
                .fillMaxHeight()
                .width(300.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color(0xFF16213E))))
                .border(1.dp, OxPurple.copy(0.5f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .pointerInput(Unit) { detectTapGestures { /* yut */ } }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Header
                Row(
                    modifier              = Modifier.fillMaxWidth().background(OxPurpleDark.copy(0.5f)).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("OxClient", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFamily = FontFamily.Monospace)
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape)
                            .background(OxError.copy(0.2f)).border(1.dp, OxError.copy(0.5f), CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) { Text("✕", color = OxError, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }

                // Kategori sekmeler
                Row(
                    modifier              = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModuleCategory.entries.forEach { c ->
                        val sel = c == cat
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (sel) OxPurple else OxSurface)
                                .border(1.dp, if (sel) OxPurple else OxOutline, RoundedCornerShape(20.dp))
                                .clickable { cat = c }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(c.displayName, fontSize = 11.sp, color = if (sel) Color.White else OxOnSurface,
                                fontFamily = FontFamily.Monospace, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                // Modül listesi
                if (mods.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("Modül yok", fontSize = 12.sp, color = OxOnSurface.copy(0.4f), fontFamily = FontFamily.Monospace)
                    }
                } else {
                    LazyColumn(
                        modifier        = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding  = PaddingValues(vertical = 6.dp)
                    ) { items(mods) { ModuleCard(it) } }
                }
            }
        }
    }

    @Composable
    private fun ModuleCard(module: BaseModule) {
        var enabled  by remember { mutableStateOf(module.isEnabled) }
        var expanded by remember { mutableStateOf(false) }
        val settings = module.settings

        LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (enabled) OxPurple.copy(0.15f) else OxSurface)
                .border(1.dp, if (enabled) OxPurple.copy(0.5f) else OxOutline.copy(0.3f), RoundedCornerShape(10.dp))
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth()
                    .clickable { ModuleManager.toggle(module); enabled = module.isEnabled }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(module.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (enabled) Color.White else OxOnSurface, fontFamily = FontFamily.Monospace)
                    if (module.description.isNotBlank())
                        Text(module.description, fontSize = 10.sp, color = OxOnSurface.copy(0.5f),
                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settings.isNotEmpty())
                        Text(if (expanded) "▲" else "▼", fontSize = 12.sp, color = OxPurpleLight,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { expanded = !expanded }.padding(4.dp))
                    Switch(checked = enabled, onCheckedChange = { ModuleManager.toggle(module); enabled = module.isEnabled },
                        colors = SwitchDefaults.colors(checkedTrackColor = OxPurple))
                }
            }

            AnimatedVisibility(visible = expanded && settings.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().background(Color(0x22000000)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.forEach { SettingRow(it) }
                }
            }
        }
    }

    @Composable
    private fun SettingRow(s: ModuleSetting<*>) {
        when (s) {
            is FloatSetting -> {
                var v by remember { mutableFloatStateOf(s.value) }
                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(s.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                        Text("%.2f".format(v), fontSize = 11.sp, color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                    }
                    Slider(value = v, onValueChange = { v = it; s.value = it }, valueRange = s.min..s.max,
                        modifier = Modifier.height(24.dp),
                        colors   = SliderDefaults.colors(thumbColor = OxPurple, activeTrackColor = OxPurple, inactiveTrackColor = OxPurple.copy(0.3f)))
                }
            }
            is IntSetting -> {
                var v by remember { mutableFloatStateOf(s.value.toFloat()) }
                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(s.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                        Text(v.toInt().toString(), fontSize = 11.sp, color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                    }
                    Slider(value = v, onValueChange = { v = it; s.value = it.toInt() },
                        valueRange = s.min.toFloat()..s.max.toFloat(),
                        steps    = (s.max - s.min - 1).coerceAtLeast(0), modifier = Modifier.height(24.dp),
                        colors   = SliderDefaults.colors(thumbColor = OxPurple, activeTrackColor = OxPurple))
                }
            }
            is BoolSetting -> {
                var v by remember { mutableStateOf(s.value) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(s.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                    Switch(checked = v, onCheckedChange = { v = it; s.value = it }, colors = SwitchDefaults.colors(checkedTrackColor = OxPurple))
                }
            }
            is EnumSetting<*> -> {
                var sel by remember { mutableStateOf(s.value) }
                Column(modifier = Modifier.padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        @Suppress("UNCHECKED_CAST")
                        val es = s as EnumSetting<Enum<*>>
                        es.values.forEach { opt ->
                            val isSel = sel == opt
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) OxPurple else OxSurfaceVar)
                                    .border(1.dp, if (isSel) OxPurple.copy(0.5f) else OxOutline.copy(0.3f), RoundedCornerShape(12.dp))
                                    .clickable { sel = opt; es.value = opt }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(opt.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp,
                                    color = if (isSel) Color.White else OxOnSurface, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            else -> {}
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "OxClient Overlay", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ox_logo)
            .setContentTitle("OxClient Overlay")
            .setContentText("Oyun içi HUD aktif")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
}
