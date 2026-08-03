package com.rubidiumclient.ui.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import com.rubidiumclient.R
import com.rubidiumclient.config.Config
import com.rubidiumclient.config.MapArtPlan
import com.rubidiumclient.config.OverlayPositions
import com.rubidiumclient.config.Pos
import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.misc.AutoMapArt
import com.rubidiumclient.module.misc.ComboShortcut
import com.rubidiumclient.module.misc.CommandHelper
import com.rubidiumclient.module.social.FriendManager
import com.rubidiumclient.module.visual.TargetHud
import com.rubidiumclient.session.SessionManager
import com.rubidiumclient.ui.theme.*
import com.rubidiumclient.utils.BlockPalette
import com.rubidiumclient.utils.InventoryUtil
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

    private var menuView : ComposeView? = null
    private var totemView : ComposeView? = null
    private var targetView: ComposeView? = null
    private var fabView   : ComposeView? = null
    private var espView  : ESPOverlayView? = null
    private val shortcutViews = mutableMapOf<String, ComposeView>()
    private val commandShortcutViews = mutableMapOf<String, ComposeView>()

    private lateinit var audioManager: AudioManager
    private var mediaSession: android.support.v4.media.session.MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        ssrCtrl.performRestore(null)
        lcReg.currentState = Lifecycle.State.CREATED
        wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        OverlayPositions.onChanged = { serviceScope.launch { applyPositionsToViews() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif())
        if (mediaSession == null) setupVolumeInterceptor()
        showOverlay()
        lcReg.currentState = Lifecycle.State.RESUMED
        OverlayState.setOverlayVisible(true)
        startStatsPoller()
        return START_STICKY
    }

    override fun onDestroy() {
        lcReg.currentState = Lifecycle.State.DESTROYED
        OverlayPositions.onChanged = null
        OverlayState.setOverlayVisible(false)
        try { mediaSession?.isActive = false; mediaSession?.release() } catch (_: Exception) {}
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

            val totemParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                OverlayPositions.totem.x, OverlayPositions.totem.y
            )
            totemView = composeView {
                TotemCounterIcon(
                    onDrag = { dx, dy ->
                        val next = Pos(OverlayPositions.totem.x + dx, OverlayPositions.totem.y + dy)
                        OverlayPositions.totem = next
                        totemParams.x = next.x.roundToInt()
                        totemParams.y = next.y.roundToInt()
                        safeUpdate(totemView, totemParams)
                    }
                )
            }
            wm.addView(totemView, totemParams)

            val targetParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                OverlayPositions.target.x, OverlayPositions.target.y
            )
            targetView = composeView {
                TargetHudBox(
                    onDrag = { dx, dy ->
                        val next = Pos(OverlayPositions.target.x + dx, OverlayPositions.target.y + dy)
                        OverlayPositions.target = next
                        targetParams.x = next.x.roundToInt()
                        targetParams.y = next.y.roundToInt()
                        safeUpdate(targetView, targetParams)
                    }
                )
            }
            wm.addView(targetView, targetParams)

            val fabParams = overlayParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                OverlayPositions.fab.x, OverlayPositions.fab.y
            )
            fabView = composeView {
                MenuFab(
                    onClick = { toggleMenu() },
                    onDrag  = { dx, dy ->
                        val next = Pos(OverlayPositions.fab.x + dx, OverlayPositions.fab.y + dy)
                        OverlayPositions.fab = next
                        fabParams.x = next.x.roundToInt()
                        fabParams.y = next.y.roundToInt()
                        safeUpdate(fabView, fabParams)
                    }
                )
            }
            wm.addView(fabView, fabParams)

            refreshShortcuts()
            refreshCommandShortcuts()
            isAttached = true
        } catch (_: Exception) {}
    }

    private fun refreshShortcuts() {
        val active = ModuleManager.shortcutModules().map { it.name }.toSet()
        shortcutViews.entries.filter { it.key !in active }.forEach { (name, view) ->
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
            shortcutViews.remove(name)
        }
        ModuleManager.shortcutModules().forEachIndexed { idx, mod ->
            if (mod.name !in shortcutViews) {
                val pos = OverlayPositions.shortcuts.getOrPut(mod.name) { Pos(50f, 420f + idx * 50f) }
                val params = overlayParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    pos.x, pos.y
                )
                val view = composeView {
                    ShortcutButton(
                        module   = mod,
                        onDrag   = { dx, dy ->
                            val cur = OverlayPositions.shortcuts[mod.name] ?: Pos(0f, 0f)
                            val next = Pos(cur.x + dx, cur.y + dy)
                            OverlayPositions.shortcuts[mod.name] = next
                            params.x = next.x.roundToInt(); params.y = next.y.roundToInt()
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

    private fun refreshCommandShortcuts() {
        val helper = ModuleManager.byName("CommandHelper") as? CommandHelper
        val activeEntries = if (helper?.isEnabled == true) helper.entries.toSet() else emptySet()

        commandShortcutViews.entries.filter { it.key !in activeEntries }.forEach { (entry, view) ->
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
            commandShortcutViews.remove(entry)
            OverlayPositions.commandShortcuts.remove(entry)
        }
        activeEntries.forEachIndexed { idx, entry ->
            if (entry !in commandShortcutViews) {
                val pos = OverlayPositions.commandShortcuts.getOrPut(entry) { Pos(50f, 620f + idx * 50f) }
                val params = overlayParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    pos.x, pos.y
                )
                val view = composeView {
                    CommandEntryButton(
                        text   = entry,
                        onDrag = { dx, dy ->
                            val cur = OverlayPositions.commandShortcuts[entry] ?: Pos(0f, 0f)
                            val next = Pos(cur.x + dx, cur.y + dy)
                            OverlayPositions.commandShortcuts[entry] = next
                            params.x = next.x.roundToInt(); params.y = next.y.roundToInt()
                            safeUpdate(commandShortcutViews[entry], params)
                        },
                        onTap = { helper?.send(entry) }
                    )
                }
                commandShortcutViews[entry] = view
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
                    .pointerInput(Unit) { detectTapGestures { hideMenu() } }
            ) {
                HileMenu(
                    onClose           = { hideMenu() },
                    moduleVersion     = moduleVersion,
                    onShortcutChanged = { refreshShortcuts(); refreshCommandShortcuts() },
                    modifier          = Modifier.align(Alignment.CenterStart)
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
        listOfNotNull(totemView, targetView, fabView, espView).plus(shortcutViews.values).plus(commandShortcutViews.values).forEach { v ->
            try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        }
        totemView = null; targetView = null; fabView = null; espView = null; shortcutViews.clear(); commandShortcutViews.clear(); isAttached = false
    }

    private fun safeUpdate(view: ComposeView?, params: android.view.WindowManager.LayoutParams) {
        view?.let { try { wm.updateViewLayout(it, params) } catch (_: Exception) {} }
    }

    /** Config.load() sonrası (overlay zaten görünürken) tüm elemanları kaydedilmiş konumlara taşır. */
    private fun applyPositionsToViews() {
        fun snap(view: ComposeView?, pos: Pos) {
            view ?: return
            val params = view.layoutParams as? android.view.WindowManager.LayoutParams ?: return
            params.x = pos.x.roundToInt(); params.y = pos.y.roundToInt()
            safeUpdate(view, params)
        }
        snap(totemView, OverlayPositions.totem)
        snap(targetView, OverlayPositions.target)
        snap(fabView, OverlayPositions.fab)
        shortcutViews.forEach { (name, view) -> OverlayPositions.shortcuts[name]?.let { snap(view, it) } }
        commandShortcutViews.forEach { (entry, view) -> OverlayPositions.commandShortcuts[entry]?.let { snap(view, it) } }
    }

    private fun composeView(content: @Composable () -> Unit) =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent(content)
        }

    private fun setupVolumeInterceptor() {
        val max = 20
        val start = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) *
                max / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        val provider = object : androidx.media.VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE, max, start
        ) {
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) toggleMenu()
            }
        }

        mediaSession = android.support.v4.media.session.MediaSessionCompat(this, "RubidiumOverlayVolume").apply {
            setFlags(
                android.support.v4.media.session.MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                android.support.v4.media.session.MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackState(
                android.support.v4.media.session.PlaybackStateCompat.Builder()
                    .setState(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .setActions(
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE
                    )
                    .build()
            )
            isActive = true
            setPlaybackToRemote(provider)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Rubidium Client Overlay", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_rubidium_logo)
        .setContentTitle("Rubidium Client Overlay")
        .setContentText("HUD aktif")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}


@Composable
private fun TotemCounterIcon(onDrag: (Float, Float) -> Unit) {
    val count      = OverlayState.totemCount
    var totalDrag  by remember { mutableFloatStateOf(0f) }

    val autoTotem  = remember { ModuleManager.byName("AutoTotem") }
    var autoTotemEnabled by remember { mutableStateOf(autoTotem?.isEnabled ?: false) }
    LaunchedEffect(autoTotem) {
        autoTotem?.enabledFlow?.collect { autoTotemEnabled = it }
    }

    AnimatedVisibility(
        visible = autoTotemEnabled,
        enter   = fadeIn() + expandHorizontally(),
        exit    = fadeOut() + shrinkHorizontally()
    ) {
    Row(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(50.dp))
            .background(Color(0xDD111827))
            .border(1.dp, Color(0xFF2D3F6E), RoundedCornerShape(50.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd   = { },
                    onDrag      = { change, offset ->
                        change.consume()
                        totalDrag += kotlin.math.abs(offset.x) + kotlin.math.abs(offset.y)
                        onDrag(offset.x, offset.y)
                    }
                )
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.foundation.Image(
            painter            = painterResource(id = R.drawable.ic_totem),
            contentDescription = "Totem",
            modifier           = Modifier.size(22.dp)
        )
        Text(
            text       = "$count",
            fontSize   = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color      = when {
                count > 5 -> Color.White
                else      -> Color(0xFFFF453A)
            }
        )
    }
    }
}


@Composable
private fun TargetHudBox(onDrag: (Float, Float) -> Unit) {
    val module = remember { ModuleManager.byName("TargetHud") as? TargetHud }

    var enabled by remember { mutableStateOf(module?.isEnabled ?: false) }
    LaunchedEffect(module) {
        module?.enabledFlow?.collect { enabled = it }
    }

    val target = module?.target?.collectAsState()?.value
    val isCritical = target?.isCritical == true

    val accentColor by animateColorAsState(
        targetValue = if (isCritical) Color(0xFFFF453A) else Color(0xFF4DD0E1),
        animationSpec = tween(200),
        label = "targetHudAccent"
    )

    AnimatedVisibility(
        visible = enabled && target != null,
        enter   = fadeIn() + expandHorizontally(),
        exit    = fadeOut() + shrinkHorizontally()
    ) {
        Row(
            modifier = Modifier
                .wrapContentSize()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xDD111827))
                .border(1.dp, if (isCritical) accentColor.copy(alpha = 0.8f) else Color(0xFF2D3F6E), RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, offset ->
                        change.consume()
                        onDrag(offset.x, offset.y)
                    }
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text       = target?.name ?: "-",
                fontSize   = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                color      = if (isCritical) accentColor else Color.White,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black,
                        offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                        blurRadius = 4f
                    )
                )
            )
        }
    }
}


@Composable
private fun MenuFab(onClick: () -> Unit, onDrag: (Float, Float) -> Unit) {
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0xDD1C1C1E))
            .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onClick() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDrag = 0f },
                    onDragEnd   = { isDragging = false; if (totalDrag < 12f) onClick() },
                    onDrag      = { c, o -> c.consume(); totalDrag += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter            = painterResource(id = R.mipmap.ic_rubidium_logo),
            contentDescription = "Rubidium",
            modifier           = Modifier
                .size(30.dp)
                .clip(CircleShape)
        )
    }
}

@Composable
private fun ShortcutButton(module: BaseModule, onDrag: (Float, Float) -> Unit, onToggle: () -> Unit) {
    var enabled    by remember { mutableStateOf(module.isEnabled) }
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    val bgColor = if (enabled) RubidiumSurfaceVar else RubidiumSurface
    val borderColor = if (enabled) RubidiumAccentLight.copy(0.9f) else RubidiumOutline.copy(0.5f)
    val textColor   = if (enabled) Color.White else RubidiumOnSurface.copy(0.6f)

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


@Composable
private fun CommandEntryButton(text: String, onDrag: (Float, Float) -> Unit, onTap: () -> Unit) {
    var totalDrag  by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(50.dp))
            .background(RubidiumSurfaceVar)
            .border(1.5.dp, RubidiumAccentLight.copy(0.9f), RoundedCornerShape(50.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!isDragging) onTap() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true; totalDrag = 0f },
                    onDragEnd   = { isDragging = false; if (totalDrag < 12f) onTap() },
                    onDrag      = { c, o -> c.consume(); totalDrag += abs(o.x) + abs(o.y); onDrag(o.x, o.y) }
                )
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp)
        )
    }
}

private enum class MenuSection(val displayName: String) {
    COMBAT("Combat"), MOVEMENT("Movement"), VISUAL("Visual"), MISC("Misc"), FRIENDS("Friends"), CONFIG("Config")
}

private fun MenuSection.toModuleCategory(): ModuleCategory? = when (this) {
    MenuSection.COMBAT   -> ModuleCategory.COMBAT
    MenuSection.MOVEMENT -> ModuleCategory.MOVEMENT
    MenuSection.VISUAL   -> ModuleCategory.VISUAL
    MenuSection.MISC     -> ModuleCategory.MISC
    MenuSection.FRIENDS  -> null
    MenuSection.CONFIG   -> null
}


@Composable
private fun HileMenu(
    onClose          : () -> Unit,
    moduleVersion    : Int,
    onShortcutChanged: () -> Unit,
    modifier         : Modifier = Modifier
) {
    var section by remember { mutableStateOf(MenuSection.COMBAT) }
    val cat  = section.toModuleCategory()
    val mods = remember(moduleVersion, cat) { cat?.let { ModuleManager.byCategory(it) } ?: emptyList() }
    val relayActive by SessionManager.isActive.collectAsState()

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(
                Brush.verticalGradient(
                    listOf(RubidiumBackground.copy(alpha = 0.72f), Color(0xFF141830).copy(alpha = 0.72f))
                )
            )
            .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RubidiumSurface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rubidium Client", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                        color = RubidiumOnBackground)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (relayActive) RubidiumSuccess.copy(0.2f) else RubidiumError.copy(0.15f))
                            .border(1.dp,
                                if (relayActive) RubidiumSuccess.copy(0.5f) else RubidiumError.copy(0.4f),
                                RoundedCornerShape(20.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(if (relayActive) "Connected" else "Disconnected",
                            fontSize = 9.sp,
                            color = if (relayActive) RubidiumSuccess else RubidiumError,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(RubidiumError.copy(0.15f))
                        .border(1.dp, RubidiumError.copy(0.4f), CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("x", color = RubidiumError, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = RubidiumOutlineStrong)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RubidiumSurface.copy(0.5f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MenuSection.entries.forEach { s ->
                    val sel = s == section
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50.dp))
                            .background(if (sel) RubidiumSurfaceVar else Color.Transparent)
                            .border(1.dp,
                                if (sel) RubidiumOutlineStrong else RubidiumOutline,
                                RoundedCornerShape(50.dp))
                            .clickable { section = s }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(s.displayName, fontSize = 11.sp,
                            color = if (sel) RubidiumOnBackground else RubidiumOnSurface,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }            }

            HorizontalDivider(color = RubidiumOutlineStrong)

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (section == MenuSection.CONFIG) {
                    ConfigSection()
                } else if (section == MenuSection.FRIENDS) {
                    FriendsSection()
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(mods) { mod ->
                            ModuleCard(module = mod, onShortcutChanged = onShortcutChanged)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun ConfigSection() {
    val scope = rememberCoroutineScope()
    val profiles      by Config.profiles.collectAsState(initial = emptyList())
    val activeProfile by Config.activeProfile.collectAsState(initial = null)
    var newName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                placeholder = { Text("Profile name", fontSize = 11.sp, color = RubidiumOnSurfaceDim) },
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = RubidiumOnSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = RubidiumAccent,
                    unfocusedBorderColor = RubidiumOutline
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(RubidiumAccent)
                    .clickable {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            scope.launch { Config.save(trimmed) }
                            newName = ""
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text("Save", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }

        HorizontalDivider(color = RubidiumOutlineStrong)

        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No saved profiles yet.", fontSize = 12.sp, color = RubidiumOnSurfaceDim)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(profiles) { profile ->
                    ConfigProfileRow(
                        name     = profile.name,
                        active   = profile.name == activeProfile,
                        onLoad   = { scope.launch { Config.load(profile.name) } },
                        onDelete = { scope.launch { Config.delete(profile.name) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigProfileRow(
    name    : String,
    active  : Boolean,
    onLoad  : () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) RubidiumSurfaceVar else RubidiumSurface)
            .border(1.dp,
                if (active) RubidiumAccentLight.copy(0.6f) else RubidiumOutline,
                RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 13.sp,
            color = RubidiumOnBackground,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onLoad, colors = ButtonDefaults.textButtonColors(contentColor = RubidiumAccentLight)) {
                Text("Load", fontSize = 11.sp)
            }
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = RubidiumError)) {
                Text("Del", fontSize = 11.sp)
            }
        }
    }
}


@Composable
private fun FriendsSection() {
    var newName by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf(FriendManager.getAll()) }

    fun refresh() { friends = FriendManager.getAll() }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                placeholder = { Text("Player name", fontSize = 11.sp, color = RubidiumOnSurfaceDim) },
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = RubidiumOnSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = RubidiumAccent,
                    unfocusedBorderColor = RubidiumOutline
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(RubidiumAccent)
                    .clickable {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            FriendManager.addFriend(trimmed)
                            newName = ""
                            refresh()
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text("Add", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${friends.size} friends • ignored by KillAura and TPAura",
                fontSize = 10.sp,
                color = RubidiumOnSurfaceDim
            )
            if (friends.isNotEmpty()) {
                TextButton(
                    onClick = { FriendManager.clear(); refresh() },
                    colors = ButtonDefaults.textButtonColors(contentColor = RubidiumError)
                ) {
                    Text("Clear all", fontSize = 11.sp)
                }
            }
        }

        HorizontalDivider(color = RubidiumOutlineStrong)

        if (friends.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No friends added yet.", fontSize = 12.sp, color = RubidiumOnSurfaceDim)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(friends) { name ->
                    FriendRow(
                        name     = name,
                        onRemove = { FriendManager.removeFriend(name); refresh() }
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRow(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RubidiumSurface)
            .border(1.dp, RubidiumOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 13.sp,
            color = RubidiumOnBackground,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = RubidiumError)) {
            Text("Remove", fontSize = 11.sp)
        }
    }
}


@Composable
private fun ModuleCard(module: BaseModule, onShortcutChanged: () -> Unit) {
    var enabled  by remember { mutableStateOf(module.isEnabled) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(module) { module.enabledFlow.collect { enabled = it } }

    val cardBg = when {
        expanded -> RubidiumModuleExpanded
        enabled  -> RubidiumModuleActive
        else     -> RubidiumSurface
    }
    val borderColor = when {
        enabled -> RubidiumModuleActiveBorder
        else    -> RubidiumOutline.copy(0.6f)
    }
    val textColor = if (enabled) RubidiumModuleActiveText else RubidiumOnSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
            .clip(RoundedCornerShape(11.dp))
            .background(cardBg)
            .border(if (enabled) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(11.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (module.settings.isNotEmpty()) expanded = !expanded
                    else ModuleManager.toggle(module)
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                module.name,
                fontSize = 13.sp,
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = { ModuleManager.toggle(module); onShortcutChanged() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor   = RubidiumModuleActiveBorder,
                    checkedThumbColor   = Color.White,
                    uncheckedTrackColor = RubidiumOutlineStrong,
                    uncheckedThumbColor = RubidiumOnSurfaceDim
                ),
                modifier = Modifier
                    .scale(0.8f)
                    .height(18.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded && module.settings.isNotEmpty(),
            enter   = expandVertically(
                          animationSpec = tween(320, easing = FastOutSlowInEasing),
                          expandFrom    = Alignment.Top
                      ) + fadeIn(animationSpec = tween(280, delayMillis = 40, easing = LinearOutSlowInEasing)),
            exit    = shrinkVertically(
                          animationSpec = tween(260, easing = FastOutSlowInEasing),
                          shrinkTowards = Alignment.Top
                      ) + fadeOut(animationSpec = tween(160, easing = LinearEasing))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x22000000))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                module.settings.forEach { s ->
                    if (module is ComboShortcut && s.name == "Modules") return@forEach
                    SettingRow(setting = s, onShortcutChanged = onShortcutChanged)
                }
                if (module is AutoMapArt) {
                    AutoMapArtBlockPanel(module = module)
                }
                if (module is ComboShortcut) {
                    ComboShortcutPanel(module = module)
                }
                if (module is CommandHelper) {
                    CommandHelperPanel(module = module, onShortcutChanged = onShortcutChanged)
                }
            }
        }
    }
}


@Composable
private fun SettingRow(setting: ModuleSetting<*>, onShortcutChanged: () -> Unit) {
    when (setting) {
        is FloatSetting -> {
            var v by remember { mutableFloatStateOf(setting.value) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 12.sp, color = RubidiumOnSurface)
                    Text("%.2f".format(v), fontSize = 12.sp, color = RubidiumAccentLight,
                        fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = v,
                    onValueChange = { v = it; setting.value = it },
                    valueRange = setting.min..setting.max,
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor         = RubidiumAccentLight,
                        activeTrackColor   = RubidiumAccent,
                        inactiveTrackColor = RubidiumOutlineStrong
                    )
                )
            }
        }
        is IntSetting -> {
            var v by remember { mutableFloatStateOf(setting.value.toFloat()) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(setting.name, fontSize = 12.sp, color = RubidiumOnSurface)
                    Text(v.roundToInt().toString(), fontSize = 12.sp, color = RubidiumAccentLight,
                        fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = v,
                    onValueChange = { v = it; setting.value = it.roundToInt() },
                    valueRange = setting.min.toFloat()..setting.max.toFloat(),
                    steps = (setting.max - setting.min - 1).coerceAtLeast(0),
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor         = RubidiumAccentLight,
                        activeTrackColor   = RubidiumAccent,
                        inactiveTrackColor = RubidiumOutlineStrong
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
                Text(setting.name, fontSize = 12.sp, color = RubidiumOnSurface)
                if (isShortcut) {
                    ShortcutToggle(checked = v) {
                        v = it; setting.value = it; onShortcutChanged()
                    }
                } else {
                    Switch(
                        checked = v,
                        onCheckedChange = { v = it; setting.value = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor   = RubidiumAccent,
                            checkedThumbColor   = Color.White,
                            uncheckedTrackColor = RubidiumOutlineStrong,
                            uncheckedThumbColor = RubidiumOnSurfaceDim
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
                Text(es.name, fontSize = 12.sp, color = RubidiumOnSurface)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    es.values.forEach { opt ->
                        val isSel = sel == opt
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(if (isSel) RubidiumSurfaceRaised else RubidiumSurface)
                                .border(1.dp,
                                    if (isSel) RubidiumOutlineStrong else RubidiumOutline,
                                    RoundedCornerShape(50.dp))
                                .clickable { sel = opt; es.value = opt }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                opt.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp,
                                color = if (isSel) RubidiumOnBackground else RubidiumOnSurface,
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
                Text(setting.name, fontSize = 12.sp, color = RubidiumOnSurface,
                    modifier = Modifier.weight(0.4f))
                OutlinedTextField(
                    value = v,
                    onValueChange = { v = it; setting.value = it },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f).height(40.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = RubidiumOnSurface),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = RubidiumAccent,
                        unfocusedBorderColor = RubidiumOutline
                    )
                )
            }
        }
        else -> {}
    }
}


@Composable
private fun AutoMapArtBlockPanel(module: AutoMapArt) {
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(module.availableBlocks) }

    var scanned by remember { mutableStateOf(module.lastScannedBlocks) }

    val displayList = remember(scanned) {
        if (scanned.isNotEmpty()) scanned.toList().sortedBy { BlockPalette.displayName(it) }
        else BlockPalette.ALL.map { it.identifier }.sortedBy { BlockPalette.displayName(it) }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Available Blocks",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = RubidiumOnSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(RubidiumSurfaceRaised)
                        .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(6.dp))
                        .clickable {
                            val found = module.scanInventoryBlocks()
                            scanned = found
                            selected = found
                            module.availableBlocks = found
                            module.lastScannedBlocks = found
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Scan Inv", fontSize = 10.sp, color = RubidiumAccentLight)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(RubidiumSurfaceRaised)
                        .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(6.dp))
                        .clickable {
                            selected = emptySet()
                            scanned = emptySet()
                            module.availableBlocks = emptySet()
                            module.lastScannedBlocks = emptySet()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("All", fontSize = 10.sp, color = RubidiumOnSurfaceDim)
                }
            }
        }

        Text(
            if (selected.isEmpty()) "Using full palette"
            else "${selected.size} blocks selected — grid recalculated",
            fontSize = 10.sp,
            color = if (selected.isEmpty()) RubidiumOnSurfaceDim else RubidiumAccentLight
        )

        if (displayList.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                displayList.forEach { blockId ->
                    val isChecked = selected.isEmpty() || blockId in selected
                    val paletteColor = BlockPalette.colorOf(blockId)
                    val r = (paletteColor shr 16) and 0xFF
                    val g = (paletteColor shr 8) and 0xFF
                    val b = paletteColor and 0xFF
                    val composeColor = Color(r / 255f, g / 255f, b / 255f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isChecked && selected.isNotEmpty()) RubidiumSurface
                                else Color.Transparent
                            )
                            .clickable {
                                selected = if (selected.isEmpty()) {
                                    setOf(blockId)
                                } else {
                                    if (blockId in selected) selected - blockId
                                    else selected + blockId
                                }.also { module.availableBlocks = it }
                            }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(composeColor)
                                .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(3.dp))
                        )
                        Text(
                            BlockPalette.displayName(blockId),
                            fontSize = 11.sp,
                            color = if (isChecked && selected.isNotEmpty()) RubidiumOnSurface
                                    else RubidiumOnSurfaceDim,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (isChecked) RubidiumAccent else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (isChecked) RubidiumAccent else RubidiumOutlineStrong,
                                        RoundedCornerShape(3.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isChecked) {
                                    Text("✓", fontSize = 9.sp, color = Color.White,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        val requiredCounts by MapArtPlan.requiredCounts.collectAsState()
        if (requiredCounts.isNotEmpty() && selected.isNotEmpty()) {
            HorizontalDivider(color = RubidiumOutline.copy(0.5f), modifier = Modifier.padding(vertical = 2.dp))
            Text("Grid summary:", fontSize = 10.sp, color = RubidiumOnSurfaceDim)
            Column(
                modifier = Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                requiredCounts.take(10).forEach { (id, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(BlockPalette.displayName(id), fontSize = 10.sp, color = RubidiumOnSurface)
                        Text("$count", fontSize = 10.sp, color = RubidiumAccentLight,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                if (requiredCounts.size > 10) {
                    Text("... +${requiredCounts.size - 10} more", fontSize = 9.sp, color = RubidiumOnSurfaceDim)
                }
            }
        }
    }
}

@Composable
private fun ComboShortcutPanel(module: ComboShortcut) {
    var selected by remember(module) {
        mutableStateOf(
            module.targets.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        )
    }

    val allModules = remember(module) {
        ModuleCategory.values()
            .flatMap { ModuleManager.byCategory(it) }
            .distinctBy { it.name }
            .filter { it.name != module.name }
    }

    HorizontalDivider(color = RubidiumOutline.copy(0.5f), modifier = Modifier.padding(vertical = 2.dp))
    Text("Select modules", fontSize = 11.sp, color = RubidiumOnSurfaceDim)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        allModules.forEach { mod ->
            val isChecked = mod.name in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isChecked) RubidiumSurface else Color.Transparent)
                    .clickable {
                        selected = if (isChecked) selected - mod.name else selected + mod.name
                        module.targets.value = selected.joinToString(", ")
                    }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isChecked) RubidiumAccent else Color.Transparent)
                        .border(
                            1.dp,
                            if (isChecked) RubidiumAccent else RubidiumOutlineStrong,
                            RoundedCornerShape(3.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isChecked) {
                        Text("✓", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    mod.name,
                    fontSize = 11.sp,
                    color = RubidiumOnSurface.copy(alpha = if (isChecked) 1f else 0.7f),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (selected.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(RubidiumAccent)
                .clickable {
                    selected.forEach { name ->
                        ModuleManager.byName(name)?.let { ModuleManager.enable(it) }
                    }
                }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                module.comboName.value.ifBlank { "Combo" },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = RubidiumOnBackground
            )
        }
    }
}

@Composable
private fun CommandHelperPanel(module: CommandHelper, onShortcutChanged: () -> Unit) {
    var entries by remember(module) { mutableStateOf(module.entries) }
    var adding by remember(module) { mutableStateOf(false) }
    var newEntry by remember(module) { mutableStateOf("") }

    HorizontalDivider(color = RubidiumOutline.copy(0.5f), modifier = Modifier.padding(vertical = 2.dp))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Saved commands", fontSize = 11.sp, color = RubidiumOnSurfaceDim)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(RubidiumSurfaceRaised)
                .border(1.dp, RubidiumOutlineStrong, RoundedCornerShape(6.dp))
                .clickable { adding = !adding }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                if (adding) "×" else "+",
                fontSize = 13.sp,
                color = RubidiumAccentLight,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (adding) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newEntry,
                onValueChange = { newEntry = it },
                singleLine = true,
                placeholder = { Text("/gamemode creative or a chat message", fontSize = 10.sp) },
                modifier = Modifier.weight(1f).height(40.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = RubidiumOnSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = RubidiumAccent,
                    unfocusedBorderColor = RubidiumOutline
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(RubidiumAccent)
                    .clickable {
                        if (newEntry.isNotBlank()) {
                            module.addEntry(newEntry)
                            entries = module.entries
                            newEntry = ""
                            adding = false
                            onShortcutChanged()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text("Ekle", fontSize = 11.sp, color = RubidiumOnBackground, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (entries.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(RubidiumSurface)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        entry,
                        fontSize = 11.sp,
                        color = RubidiumOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { module.send(entry) }
                    )
                    Text(
                        "×",
                        fontSize = 13.sp,
                        color = RubidiumOnSurfaceDim,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                module.removeEntry(entry)
                                entries = module.entries
                                onShortcutChanged()
                            }
                            .padding(start = 8.dp)
                    )
                }
            }
        }
    } else if (!adding) {
        Text("No saved commands yet", fontSize = 10.sp, color = RubidiumOnSurfaceDim)
    }
}

@Composable
private fun ShortcutToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(if (checked) RubidiumSurfaceRaised else Color.Transparent)
            .border(1.5.dp,
                if (checked) RubidiumOutlineStrong else RubidiumOutline,
                RoundedCornerShape(50.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (checked) "On" else "Off",
            fontSize = 11.sp,
            color = if (checked) RubidiumOnBackground else RubidiumOnSurfaceDim,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
