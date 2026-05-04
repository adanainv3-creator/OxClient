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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG        = "OverlayService"
        private const val CHANNEL_ID = "ox_overlay"
        private const val NOTIF_ID   = 1002

        fun start(ctx: Context) {
            Log.d(TAG, "start() çağrıldı")
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            Log.d(TAG, "stop() çağrıldı")
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private val lcReg = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lcReg
    private val ssrCtrl = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrCtrl.savedStateRegistry

    private lateinit var wm: WindowManager
    private var overlayView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isAttached = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        ssrCtrl.performRestore(null)
        lcReg.currentState = Lifecycle.State.CREATED
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        startForeground(NOTIF_ID, buildNotif())
        showOverlay()
        lcReg.currentState = Lifecycle.State.RESUMED
        OverlayState.setOverlayVisible(true)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        lcReg.currentState = Lifecycle.State.DESTROYED
        OverlayState.setOverlayVisible(false)
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Window Yönetimi ────────────────────────────────────────────────────

    private fun showOverlay() {
        if (isAttached) {
            Log.d(TAG, "Overlay zaten ekli, tekrar eklenmiyor")
            return
        }

        Log.d(TAG, "showOverlay başlatılıyor")

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // FLAG_NOT_FOCUSABLE: tuş olaylarını arka plana iletir (oyunun çalışması için)
            // FLAG_NOT_TOUCHABLE: dokunma olaylarını arka plana iletir (menü kapalıyken oyun tıklanabilir olur)
            // FLAG_LAYOUT_IN_SCREEN: ekranın tamamını kaplar
            // FLAG_LAYOUT_NO_LIMITS: sistem çubuklarının altına da uzanabilir
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowParams = params

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                OxOverlay(
                    onMenuOpenChanged = { menuOpen ->
                        // Menü durumu değiştiğinde window flags'ı güncelle
                        updateWindowFlags(menuOpen)
                    }
                )
            }
        }

        try {
            wm.addView(overlayView, params)
            isAttached = true
            Log.d(TAG, "Overlay başarıyla eklendi")
        } catch (e: Exception) {
            Log.e(TAG, "Overlay eklenirken hata: ${e.message}", e)
        }
    }

    /**
     * Menü açıkken dokunma olaylarını overlay'in almasını sağla.
     * Menü kapalıyken tüm dokunma olaylarını oyuna ilet.
     */
    private fun updateWindowFlags(menuOpen: Boolean) {
        val params = windowParams ?: return
        val view   = overlayView  ?: return

        try {
            if (menuOpen) {
                // Menü açık: dokunma olaylarını overlay alır, tuş olayları hâlâ oyuna gider
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                Log.d(TAG, "Menü açıldı - overlay dokunma alıyor")
            } else {
                // Menü kapalı: dokunma olayları oyuna gider
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
                Log.d(TAG, "Menü kapandı - dokunma olayları oyuna gidiyor")
            }
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Window flags güncellenirken hata: ${e.message}", e)
        }
    }

    private fun removeOverlay() {
        if (!isAttached) return
        try {
            overlayView?.let {
                wm.removeViewImmediate(it)
                overlayView = null
            }
            isAttached = false
            Log.d(TAG, "Overlay kaldırıldı")
        } catch (e: Exception) {
            Log.e(TAG, "Overlay kaldırılırken hata: ${e.message}", e)
        }
    }

    // ── Compose Overlay ───────────────────────────────────────────────────

    @Composable
    private fun OxOverlay(onMenuOpenChanged: (Boolean) -> Unit) {
        val totemCount    by OverlayState.totemCount.collectAsState()
        val toast         by OverlayState.moduleToast.collectAsState()
        val menuOpen      by OverlayState.menuOpen.collectAsState()
        val moduleVersion by ModuleManager.version.collectAsState()

        // FAB pozisyonu
        var fabOffsetX by remember { mutableStateOf(50f) }
        var fabOffsetY by remember { mutableStateOf(300f) }

        // Shortcut bar pozisyonu (sürüklenebilir)
        var shortcutOffsetX by remember { mutableStateOf(20f) }
        var shortcutOffsetY by remember { mutableStateOf(500f) }

        // Menü açıldığında/kapandığında window flags'ı güncelle
        LaunchedEffect(menuOpen) {
            onMenuOpenChanged(menuOpen)
        }

        Box(modifier = Modifier.fillMaxSize()) {

            // ── Totem counter ──
            if (totemCount > 0) {
                TotemPill(
                    count    = totemCount,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }

            // ── Module toast ──
            ModuleToastBanner(
                toast    = toast,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
            )

            // ── Full-screen hile menüsü ──
            AnimatedVisibility(
                visible  = menuOpen,
                enter    = fadeIn() + slideInVertically { it / 2 },
                exit     = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.fillMaxSize()
            ) {
                // Menü arka planı — tıklayınca kapanır
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            OverlayState.setMenuOpen(false)
                        }
                ) {
                    HileMenu(
                        onClose       = { OverlayState.setMenuOpen(false) },
                        moduleVersion = moduleVersion,
                        modifier      = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            // ── Kısayol tuşları (menü kapalıyken) ──
            if (!menuOpen) {
                DraggableShortcutBar(
                    offsetX       = shortcutOffsetX,
                    offsetY       = shortcutOffsetY,
                    onDrag        = { dx, dy -> shortcutOffsetX += dx; shortcutOffsetY += dy },
                    moduleVersion = moduleVersion,
                    modifier      = Modifier.offset(shortcutOffsetX.dp, shortcutOffsetY.dp)
                )
            }

            // ── FAB ──
            if (!menuOpen) {
                DraggableFab(
                    offsetX  = fabOffsetX,
                    offsetY  = fabOffsetY,
                    onDrag   = { dx, dy -> fabOffsetX += dx; fabOffsetY += dy },
                    onClick  = { OverlayState.setMenuOpen(true) },
                    modifier = Modifier.offset(fabOffsetX.dp, fabOffsetY.dp)
                )
            }
        }
    }

    // ── Draggable Shortcut Bar ────────────────────────────────────────────

    @Composable
    private fun DraggableShortcutBar(
        offsetX:       Float,
        offsetY:       Float,
        onDrag:        (Float, Float) -> Unit,
        moduleVersion: Int,
        modifier:      Modifier = Modifier
    ) {
        val shortcutModules = remember(moduleVersion) {
            (ModuleManager.byCategory(ModuleCategory.COMBAT) +
             ModuleManager.byCategory(ModuleCategory.MOVEMENT))
                .take(5)
        }

        var isDragging  by remember { mutableStateOf(false) }
        var dragDist    by remember { mutableStateOf(0f) }
        var barExpanded by remember { mutableStateOf(true) }

        Column(
            modifier = modifier
                .wrapContentSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDragging = true; dragDist = 0f },
                        onDragEnd   = { isDragging = false },
                        onDrag      = { _, d ->
                            dragDist += d.getDistance()
                            if (dragDist > 8f) onDrag(d.x / density, d.y / density)
                        }
                    )
                },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Tutamaç + daralt/genişlet butonu
            Box(
                modifier = Modifier
                    .size(width = 90.dp, height = 18.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(Color(0xAA1A1A2E))
                    .clickable { barExpanded = !barExpanded },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = if (barExpanded) "▲ Shortcuts" else "▼ Shortcuts",
                    fontSize   = 8.sp,
                    color      = OxPurpleLight.copy(0.8f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Kısayol tuşları
            AnimatedVisibility(
                visible = barExpanded,
                enter   = fadeIn() + expandVertically(),
                exit    = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .wrapContentSize()
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp, topEnd = 8.dp))
                        .background(Color(0xBB0D0D1A))
                        .border(
                            1.dp,
                            OxPurple.copy(0.3f),
                            RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp, topEnd = 8.dp)
                        )
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (shortcutModules.isEmpty()) {
                        Text(
                            "Modül yok",
                            fontSize   = 9.sp,
                            color      = OxOnSurface.copy(0.4f),
                            fontFamily = FontFamily.Monospace,
                            modifier   = Modifier.padding(4.dp)
                        )
                    } else {
                        shortcutModules.forEach { module ->
                            ShortcutButton(module = module)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ShortcutButton(module: BaseModule) {
        var enabled by remember { mutableStateOf(module.isEnabled) }

        LaunchedEffect(module) {
            module.enabledFlow.collect { enabled = it }
        }

        val bgColor by animateColorAsState(
            targetValue   = if (enabled) OxPurple.copy(0.85f) else Color(0xFF1A1A2E).copy(0.9f),
            animationSpec = tween(200),
            label         = "shortcutBg"
        )
        val borderColor by animateColorAsState(
            targetValue   = if (enabled) OxPurpleLight else OxOutline.copy(0.4f),
            animationSpec = tween(200),
            label         = "shortcutBorder"
        )
        val scale by animateFloatAsState(
            targetValue   = if (enabled) 1f else 0.95f,
            animationSpec = spring(Spring.DampingRatioMediumBouncy),
            label         = "shortcutScale"
        )

        Box(
            modifier = Modifier
                .width(84.dp)
                .height(28.dp)
                .scale(scale)
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .border(
                    width = if (enabled) 1.5.dp else 0.5.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable {
                    ModuleManager.toggle(module)
                    enabled = module.isEnabled
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = module.name,
                fontSize   = 10.sp,
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
                color      = if (enabled) Color.White else OxOnSurface.copy(0.7f),
                fontFamily = FontFamily.Monospace,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    // ── FAB ───────────────────────────────────────────────────────────────

    @Composable
    private fun DraggableFab(
        offsetX:  Float,
        offsetY:  Float,
        onDrag:   (Float, Float) -> Unit,
        onClick:  () -> Unit,
        modifier: Modifier = Modifier
    ) {
        var isDragging by remember { mutableStateOf(false) }
        var dragDist   by remember { mutableStateOf(0f) }

        val scale by animateFloatAsState(
            targetValue   = if (isDragging) 0.9f else 1f,
            animationSpec = spring(Spring.DampingRatioMediumBouncy),
            label         = "fabScale"
        )

        Box(
            modifier = modifier
                .size(52.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(OxPurple, OxPurpleDark)))
                .border(1.5.dp, OxPurpleLight.copy(alpha = 0.5f), CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDragging = true; dragDist = 0f },
                        onDragEnd   = {
                            isDragging = false
                            if (dragDist < 10f) onClick()
                        },
                        onDrag = { _, dragAmount ->
                            dragDist += dragAmount.getDistance()
                            onDrag(dragAmount.x / density, dragAmount.y / density)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Ox",
                color      = Color.White,
                fontSize   = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    // ── Hile Menüsü ───────────────────────────────────────────────────────

    @Composable
    private fun HileMenu(
        onClose: () -> Unit,
        moduleVersion: Int,
        modifier: Modifier = Modifier
    ) {
        var selectedCat by remember { mutableStateOf(ModuleCategory.COMBAT) }
        val modules = remember(moduleVersion, selectedCat) {
            ModuleManager.byCategory(selectedCat)
        }

        Box(
            modifier = modifier
                .fillMaxHeight()
                .width(280.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1A1A2E), Color(0xFF16213E))
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(OxPurple.copy(0.6f), OxPurpleDark.copy(0.3f))
                    ),
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                )
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    // Menü içi tıklamaları engelleme — hiçbir şey yapma
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Header
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .background(OxPurpleDark.copy(alpha = 0.5f))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "OxClient",
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color      = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(OxError.copy(alpha = 0.2f))
                            .border(1.dp, OxError.copy(0.5f), CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "✕",
                            color      = OxError,
                            fontSize   = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Kategori seçici
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModuleCategory.values().forEach { cat ->
                        val sel = cat == selectedCat
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (sel) OxPurple else OxSurface)
                                .border(
                                    1.dp,
                                    if (sel) OxPurple else OxOutline,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { selectedCat = cat }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                cat.displayName,
                                fontSize   = 11.sp,
                                color      = if (sel) Color.White else OxOnSurface,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                HorizontalDivider(color = OxOutline.copy(0.3f))

                // Modül listesi
                LazyColumn(
                    modifier            = Modifier.fillMaxWidth().weight(1f),
                    contentPadding      = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(modules, key = { it.name }) { module ->
                        OverlayModuleCard(module)
                    }
                    if (modules.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Bu kategoride modül yok",
                                    color      = OxOnSurface.copy(0.4f),
                                    fontSize   = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign  = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = OxOutline.copy(0.3f))
                Text(
                    "2b2tpe.org:19132",
                    modifier   = Modifier.fillMaxWidth().padding(12.dp),
                    fontSize   = 10.sp,
                    color      = OxOnSurface.copy(0.3f),
                    fontFamily = FontFamily.Monospace,
                    textAlign  = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun OverlayModuleCard(module: BaseModule) {
        var enabled  by remember { mutableStateOf(module.isEnabled) }
        var expanded by remember { mutableStateOf(false) }

        LaunchedEffect(module) {
            module.enabledFlow.collect { enabled = it }
        }

        val borderColor by animateColorAsState(
            targetValue   = if (enabled) OxPurple.copy(0.8f) else OxOutline.copy(0.3f),
            animationSpec = tween(300),
            label         = "border"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(OxSurface.copy(alpha = 0.7f))
                .border(
                    if (enabled) 1.dp else 0.5.dp,
                    borderColor,
                    RoundedCornerShape(10.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = module.settings.isNotEmpty()) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        module.name,
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color      = if (enabled) Color.White else OxOnSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        module.description,
                        fontSize   = 10.sp,
                        color      = OxOnSurface.copy(0.5f),
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked         = enabled,
                    onCheckedChange = {
                        ModuleManager.toggle(module)
                        enabled = module.isEnabled
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color.White,
                        checkedTrackColor   = OxPurple,
                        uncheckedThumbColor = OxOnSurface.copy(0.4f),
                        uncheckedTrackColor = OxOutline
                    )
                )
            }

            AnimatedVisibility(visible = expanded && module.settings.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D0D1A))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    module.settings.forEach { setting ->
                        OverlaySettingRow(setting)
                    }
                }
            }
        }
    }

    @Composable
    private fun OverlaySettingRow(s: ModuleSetting<*>) {
        when (s) {
            is FloatSetting -> {
                var v by remember { mutableStateOf(s.value) }
                Column {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(
                            s.name,
                            fontSize   = 11.sp,
                            color      = OxOnSurface,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "%.2f".format(v),
                            fontSize   = 11.sp,
                            color      = OxPurpleLight,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Slider(
                        value         = v,
                        onValueChange = { v = it; s.value = it },
                        valueRange    = s.min..s.max,
                        colors        = SliderDefaults.colors(
                            thumbColor       = OxPurple,
                            activeTrackColor = OxPurple
                        )
                    )
                }
            }
            is IntSetting -> {
                var v by remember { mutableFloatStateOf(s.value.toFloat()) }
                Column {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(
                            s.name,
                            fontSize   = 11.sp,
                            color      = OxOnSurface,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            v.toInt().toString(),
                            fontSize   = 11.sp,
                            color      = OxPurpleLight,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Slider(
                        value         = v,
                        onValueChange = { v = it; s.value = it.toInt() },
                        valueRange    = s.min.toFloat()..s.max.toFloat(),
                        steps         = (s.max - s.min - 1).coerceAtLeast(0),
                        colors        = SliderDefaults.colors(
                            thumbColor       = OxPurple,
                            activeTrackColor = OxPurple
                        )
                    )
                }
            }
            is BoolSetting -> {
                var v by remember { mutableStateOf(s.value) }
                Row(
                    Modifier.fillMaxWidth(),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically
                ) {
                    Text(
                        s.name,
                        fontSize   = 11.sp,
                        color      = OxOnSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    Switch(
                        checked         = v,
                        onCheckedChange = { v = it; s.value = it },
                        colors          = SwitchDefaults.colors(
                            checkedTrackColor = OxPurple
                        )
                    )
                }
            }
            is EnumSetting<*> -> {
                var sel by remember { mutableStateOf(s.value) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        s.name,
                        fontSize   = 11.sp,
                        color      = OxOnSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        @Suppress("UNCHECKED_CAST")
                        (s as EnumSetting<Enum<*>>).values.forEach { opt ->
                            val isSel = sel == opt
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) OxPurple else OxSurfaceVar)
                                    .clickable {
                                        sel = opt
                                        @Suppress("UNCHECKED_CAST")
                                        (s as EnumSetting<Enum<*>>).value = opt
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    opt.name.lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    fontSize   = 10.sp,
                                    color      = if (isSel) Color.White else OxOnSurface,
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

    // ── HUD Bileşenleri ───────────────────────────────────────────────────

    @Composable
    private fun TotemPill(count: Int, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    OverlayState.TOTEM_SYMBOL,
                    color      = Color(0xFFFFD700),
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    count.toString(),
                    color      = Color.White,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    @Composable
    private fun ModuleToastBanner(toast: ModuleToast?, modifier: Modifier = Modifier) {
        AnimatedVisibility(
            visible  = toast != null,
            enter    = fadeIn() + slideInVertically { -it },
            exit     = fadeOut() + slideOutVertically { -it },
            modifier = modifier
        ) {
            if (toast == null) return@AnimatedVisibility
            val bg = if (toast.enabled) Color(0xCC1AFF1A) else Color(0xCCFF3C3C)
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .background(bg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Text(
                    toast.displayText,
                    color      = Color.White,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign  = TextAlign.Center
                )
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "OxClient Overlay",
                NotificationManager.IMPORTANCE_MIN
            ).also {
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it)
            }
        }
    }

    private fun buildNotif(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ox_logo)
            .setContentTitle("OxClient Overlay")
            .setContentText("Oyun üzerinde aktif — 2b2tpe.org")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
}