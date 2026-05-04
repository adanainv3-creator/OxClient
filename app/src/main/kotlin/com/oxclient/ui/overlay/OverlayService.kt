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
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "ox_overlay"
        private const val NOTIF_ID = 1002

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
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
        ssrCtrl.performRestore(null)
        lcReg.currentState = Lifecycle.State.CREATED
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        Log.d(TAG, "onCreate")
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

    // ── Window ─────────────────────────────────────────────────────────

    private fun showOverlay() {
        if (isAttached) return

        Log.d(TAG, "showOverlay")

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // ÖNEMLİ: FLAG_NOT_TOUCHABLE KALDIRILDI — butonlara dokunabilmek için
        // Menü kapalıyken sadece buton alanları touch alır, arka plan değil
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowParams = params

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                OxOverlay()
            }
        }

        try {
            wm.addView(overlayView, params)
            isAttached = true
            Log.d(TAG, "Overlay eklendi")
        } catch (e: Exception) {
            Log.e(TAG, "Overlay eklenemedi: ${e.message}")
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
            Log.d(TAG, "Overlay kaldirildi")
        } catch (e: Exception) {
            Log.e(TAG, "Overlay kaldirilamadi: ${e.message}")
        }
    }

    // ── Compose ────────────────────────────────────────────────────────

    @Composable
    private fun OxOverlay() {
        val totemCount by OverlayState.totemCount.collectAsState()
        val toast by OverlayState.moduleToast.collectAsState()
        val menuOpen by OverlayState.menuOpen.collectAsState()
        val moduleVersion by ModuleManager.version.collectAsState()

        // FAB pozisyonu
        var fabX by remember { mutableFloatStateOf(50f) }
        var fabY by remember { mutableFloatStateOf(300f) }

        // KillAura shortcut pozisyonu
        var kaX by remember { mutableFloatStateOf(20f) }
        var kaY by remember { mutableFloatStateOf(500f) }

        // TPAura shortcut pozisyonu
        var tpX by remember { mutableFloatStateOf(20f) }
        var tpY by remember { mutableFloatStateOf(560f) }

        // Modülleri bul
        val killAura = remember { ModuleManager.byName("KillAura") }
        val tpAura   = remember { ModuleManager.byName("TPAura") }

        // Menü kapalıyken dıştaki Box touch'ları YUTMAsin — pass-through
        Box(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Hiçbir gesture tanımlamıyoruz → touch'lar alt katmana (oyuna) geçer
            }
        ) {

            // Menü açıkken arka plan — tüm ekranı kaplayan yarı saydam overlay
            AnimatedVisibility(
                visible = menuOpen,
                enter = fadeIn() + slideInHorizontally { it / 2 },
                exit = fadeOut() + slideOutHorizontally { it / 2 },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .pointerInput(Unit) {
                            detectTapGestures { OverlayState.setMenuOpen(false) }
                        }
                ) {
                    HileMenu(
                        onClose = { OverlayState.setMenuOpen(false) },
                        moduleVersion = moduleVersion,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            // Totem
            if (totemCount > 0) {
                TotemPill(
                    count = totemCount,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }

            // Toast
            ModuleToastBanner(
                toast = toast,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
            )

            // FAB — menü kapalıyken göster
            if (!menuOpen) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(fabX.roundToInt(), fabY.roundToInt()) }
                ) {
                    DraggableFab(
                        onDrag = { dx, dy ->
                            fabX += dx
                            fabY += dy
                        },
                        onClick = { OverlayState.setMenuOpen(true) }
                    )
                }

                // KillAura shortcut butonu
                if (killAura != null) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(kaX.roundToInt(), kaY.roundToInt()) }
                    ) {
                        DraggableModuleButton(
                            module = killAura,
                            onDrag = { dx, dy ->
                                kaX += dx
                                kaY += dy
                            }
                        )
                    }
                }

                // TPAura shortcut butonu
                if (tpAura != null) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(tpX.roundToInt(), tpY.roundToInt()) }
                    ) {
                        DraggableModuleButton(
                            module = tpAura,
                            onDrag = { dx, dy ->
                                tpX += dx
                                tpY += dy
                            }
                        )
                    }
                }
            }
        }
    }

    // ── Sürüklenebilir Modül Butonu (KillAura & TPAura) ──────────────

    @Composable
    private fun DraggableModuleButton(
        module: BaseModule,
        onDrag: (Float, Float) -> Unit
    ) {
        var enabled by remember { mutableStateOf(module.isEnabled) }
        var isDragging by remember { mutableStateOf(false) }
        var totalDragDist by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(module) {
            module.enabledFlow.collect { enabled = it }
        }

        val bgColor = if (enabled)
            Brush.horizontalGradient(listOf(OxPurple.copy(alpha = 0.85f), OxPurpleDark.copy(alpha = 0.85f)))
        else
            Brush.horizontalGradient(listOf(Color(0xBB0D0D1A), Color(0xBB1A1A2E)))

        Box(
            modifier = Modifier
                .wrapContentSize()
                .clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .border(
                    width = if (enabled) 1.5.dp else 0.8.dp,
                    color = if (enabled) OxPurpleLight.copy(alpha = 0.9f) else OxOutline.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp)
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            totalDragDist = 0f
                        },
                        onDragEnd = {
                            isDragging = false
                            // Kısa hareket = tap → toggle
                            if (totalDragDist < 12f) {
                                ModuleManager.toggle(module)
                                enabled = module.isEnabled
                            }
                        },
                        onDrag = { change, offset ->
                            change.consume()
                            totalDragDist += abs(offset.x) + abs(offset.y)
                            // Sürükleme eşiği aşıldıysa pozisyonu güncelle (px cinsinden)
                            if (totalDragDist > 12f) {
                                onDrag(offset.x, offset.y)
                            }
                        }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = module.name,
                fontSize = 11.sp,
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
                color = if (enabled) Color.White else OxOnSurface.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    // ── FAB ────────────────────────────────────────────────────────────

    @Composable
    private fun DraggableFab(
        onDrag: (Float, Float) -> Unit,
        onClick: () -> Unit
    ) {
        var totalDist by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(OxPurple, OxPurpleDark)
                    )
                )
                .border(2.dp, OxPurpleLight.copy(alpha = 0.7f), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onClick() })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { totalDist = 0f },
                        onDragEnd = {
                            // Çok az hareket = tıklama → menüyü aç
                            if (totalDist < 15f) {
                                onClick()
                            }
                        },
                        onDrag = { change, offset ->
                            change.consume()
                            totalDist += abs(offset.x) + abs(offset.y)
                            // Sürükleme eşiği aşılınca taşı (px cinsinden)
                            if (totalDist > 15f) {
                                onDrag(offset.x, offset.y)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Ox",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    // ── Menu ───────────────────────────────────────────────────────────

    @Composable
    private fun HileMenu(
        onClose: () -> Unit,
        moduleVersion: Int,
        modifier: Modifier = Modifier
    ) {
        var cat by remember { mutableStateOf(ModuleCategory.COMBAT) }
        val mods = remember(moduleVersion, cat) {
            ModuleManager.byCategory(cat)
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
                    1.dp,
                    OxPurple.copy(alpha = 0.5f),
                    RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                )
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                // Menü içine tıklama arka plana yayılmasın
                .pointerInput(Unit) {
                    detectTapGestures { /* yut */ }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OxPurpleDark.copy(alpha = 0.5f))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OxClient",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(OxError.copy(alpha = 0.2f))
                            .border(1.dp, OxError.copy(alpha = 0.5f), CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = OxError,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Categories
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModuleCategory.entries.forEach { c ->
                        val sel = c == cat
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (sel) OxPurple else OxSurface)
                                .border(
                                    1.dp,
                                    if (sel) OxPurple else OxOutline,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { cat = c }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = c.displayName,
                                fontSize = 11.sp,
                                color = if (sel) Color.White else OxOnSurface,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Module list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    items(mods) { mod ->
                        ModuleCard(module = mod)
                    }
                }
            }
        }
    }

    @Composable
    private fun ModuleCard(module: BaseModule) {
        var enabled by remember { mutableStateOf(module.isEnabled) }
        var expanded by remember { mutableStateOf(false) }
        val settings = remember { module.settings }

        LaunchedEffect(module) {
            module.enabledFlow.collect { enabled = it }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (enabled) OxPurple.copy(alpha = 0.15f) else OxSurface
                )
                .border(
                    1.dp,
                    if (enabled) OxPurple.copy(alpha = 0.5f) else OxOutline.copy(alpha = 0.3f),
                    RoundedCornerShape(10.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ModuleManager.toggle(module)
                        enabled = module.isEnabled
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = module.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) Color.White else OxOnSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    if (module.description.isNotBlank()) {
                        Text(
                            text = module.description,
                            fontSize = 10.sp,
                            color = OxOnSurface.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (settings.isNotEmpty()) {
                        Text(
                            text = if (expanded) "▲" else "▼",
                            fontSize = 10.sp,
                            color = OxPurpleLight,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            ModuleManager.toggle(module)
                            enabled = module.isEnabled
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = OxPurple)
                    )
                }
            }

            // Settings
            AnimatedVisibility(visible = expanded && settings.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x22000000))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    settings.forEach { s -> SettingRow(s) }
                }
            }
        }
    }

    @Composable
    private fun SettingRow(s: ModuleSetting<*>) {
        when (s) {
            is FloatSetting -> {
                var v by remember { mutableFloatStateOf(s.value) }
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = s.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                        Text(text = "%.2f".format(v), fontSize = 11.sp, color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = v,
                        onValueChange = { v = it; s.value = it },
                        valueRange = s.min..s.max,
                        colors = SliderDefaults.colors(thumbColor = OxPurple, activeTrackColor = OxPurple)
                    )
                }
            }

            is IntSetting -> {
                var v by remember { mutableFloatStateOf(s.value.toFloat()) }
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = s.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                        Text(text = v.toInt().toString(), fontSize = 11.sp, color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = v,
                        onValueChange = { v = it; s.value = it.toInt() },
                        valueRange = s.min.toFloat()..s.max.toFloat(),
                        steps = (s.max - s.min - 1).coerceAtLeast(0),
                        colors = SliderDefaults.colors(thumbColor = OxPurple, activeTrackColor = OxPurple)
                    )
                }
            }

            is BoolSetting -> {
                var v by remember { mutableStateOf(s.value) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = s.name, fontSize = 11.sp, color = OxOnSurface, fontFamily = FontFamily.Monospace)
                    Switch(
                        checked = v,
                        onCheckedChange = { v = it; s.value = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = OxPurple)
                    )
                }
            }

            is EnumSetting<*> -> {
                var sel by remember { mutableStateOf(s.value) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = s.name,
                        fontSize = 11.sp,
                        color = OxOnSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        @Suppress("UNCHECKED_CAST")
                        val enumSetting = s as EnumSetting<Enum<*>>
                        enumSetting.values.forEach { opt ->
                            val isSel = sel == opt
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) OxPurple else OxSurfaceVar)
                                    .clickable {
                                        sel = opt
                                        enumSetting.value = opt
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = opt.name.lowercase().replaceFirstChar { it.uppercase() },
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

    // ── HUD ────────────────────────────────────────────────────────────

    @Composable
    private fun TotemPill(count: Int, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = OverlayState.TOTEM_SYMBOL,
                    color = Color(0xFFFFD700),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = count.toString(),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    @Composable
    private fun ModuleToastBanner(
        toast: ModuleToast?,
        modifier: Modifier = Modifier
    ) {
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
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
                    text = toast.displayText,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OxClient Overlay",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotif(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ox_logo)
            .setContentTitle("OxClient Overlay")
            .setContentText("Oyun ici HUD aktif")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}