package com.oxclient.ui.overlay

import android.app.*
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.oxclient.module.ModuleManager
import com.oxclient.module.BaseModule
import com.oxclient.module.combat.KillAura
import com.oxclient.module.combat.Criticals
import com.oxclient.module.movement.TPAura
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

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

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
            try {
                val method = ComposeView::class.java.getMethod(
                    "setViewTreeSavedStateRegistryOwner",
                    SavedStateRegistryOwner::class.java
                )
                method.invoke(this, this@OverlayService)
            } catch (_: Exception) {}

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

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.oxclient.R.drawable.ic_ox_logo)
            .setContentTitle("OxClient")
            .setContentText("MITM Aktif")
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  OVERLAY ANA MENÜ
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun OverlayContent(
    onDrag          : (Float, Float) -> Unit,
    onOpenDashboard : () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    var settingsModule by remember { mutableStateOf<BaseModule?>(null) }

    Column(horizontalAlignment = Alignment.End) {

        // ── FAB Butonu ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(48.dp)
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
                modifier = Modifier.size(24.dp)
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
                    .widthIn(min = 220.dp, max = 260.dp),
                shape   = RoundedCornerShape(14.dp),
                color   = OxSurface.copy(alpha = 0.95f),
                border  = BorderStroke(1.dp, OxBorder)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {

                    // Başlık
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("OxClient", color = OxPurpleLight,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        IconButton(onClick = onOpenDashboard, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.OpenInFull, null,
                                tint = OxTextSub, modifier = Modifier.size(14.dp))
                        }
                    }

                    HorizontalDivider(color = OxBorder, modifier = Modifier.padding(vertical = 4.dp))

                    // Kategori sekmeleri
                    val categories = listOf("Combat", "Movement", "Visual", "Misc")
                    ScrollableTabRow(
                        selectedTabIndex = selectedCategory,
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = Color.Transparent,
                        edgePadding = 0.dp,
                        divider = {},
                        indicator = { tabPositions ->
                            Box(
                                Modifier
                                    .tabIndicatorOffset(tabPositions[selectedCategory])
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(OxPurple)
                            )
                        }
                    ) {
                        categories.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedCategory == index,
                                onClick  = { selectedCategory = index },
                                text = {
                                    Text(title,
                                        color = if (selectedCategory == index) OxPurpleLight else OxTextSub,
                                        fontSize = 11.sp,
                                        fontWeight = if (selectedCategory == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Kategoriye göre modüller
                    when (selectedCategory) {
                        0 -> CombatModules(
                            onOpenSettings = { settingsModule = it }
                        )
                        1 -> MovementModules(
                            onOpenSettings = { settingsModule = it }
                        )
                        2 -> EmptyCategory("Modül yok")
                        3 -> EmptyCategory("Modül yok")
                    }
                }
            }
        }

        // ── Ayarlar Paneli ────────────────────────────────────────────────
        if (settingsModule != null) {
            AnimatedVisibility(
                visible = settingsModule != null,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it })
            ) {
                Surface(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .widthIn(min = 220.dp, max = 260.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = OxSurface.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, OxBorder)
                ) {
                    ModuleSettingsPanel(
                        module = settingsModule!!,
                        onClose = { settingsModule = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCategory(message: String) {
    Box(modifier = Modifier.padding(16.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(message, color = OxTextSub, fontSize = 12.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  KATEGORİ PANELLERİ
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun CombatModules(onOpenSettings: (BaseModule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ModuleRow("KillAura", ModuleManager.killAura, onOpenSettings)
        ModuleRow("Criticals", ModuleManager.criticals, onOpenSettings)
        ModuleRow("AutoTotem", ModuleManager.autoTotem, onOpenSettings)
    }
}

@Composable
private fun MovementModules(onOpenSettings: (BaseModule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ModuleRow("TPAura", ModuleManager.tpAura, onOpenSettings)
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  MODÜL SATIRI (Enable + Shortcut + Settings)
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ModuleRow(
    name          : String,
    module        : BaseModule,
    onOpenSettings: (BaseModule) -> Unit
) {
    var enabled by remember { mutableStateOf(module.enabled) }
    var shortcutActive by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) OxPurple.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Modül adı
        Text(
            text  = name,
            color = if (enabled) OxPurpleLight else OxText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        // Kontrol butonları
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shortcut toggle (S)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (shortcutActive) OxPurple.copy(alpha = 0.3f) 
                        else Color.Transparent
                    )
                    .border(
                        1.dp, 
                        if (shortcutActive) OxPurpleLight else OxBorder, 
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { shortcutActive = !shortcutActive },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "S",
                    color = if (shortcutActive) OxPurpleLight else OxTextSub,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Ayarlar butonu
            IconButton(
                onClick = { onOpenSettings(module) },
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Ayarlar",
                    tint = OxTextSub,
                    modifier = Modifier.size(14.dp)
                )
            }

            // Enable/Disable switch
            Switch(
                checked = enabled,
                onCheckedChange = {
                    module.toggle()
                    enabled = module.enabled
                },
                modifier = Modifier.size(0.65f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = Color.White,
                    checkedTrackColor   = OxPurple,
                    uncheckedThumbColor = OxTextSub,
                    uncheckedTrackColor = OxBorder
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  MODÜL AYARLARI PANELİ
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ModuleSettingsPanel(
    module  : BaseModule,
    onClose : () -> Unit
) {
    Column(modifier = Modifier.padding(8.dp)) {

        // Başlık
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${module.name} Ayarları",
                color = OxPurpleLight,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Close, null, tint = OxTextSub, modifier = Modifier.size(14.dp))
            }
        }

        HorizontalDivider(color = OxBorder, modifier = Modifier.padding(vertical = 4.dp))

        // Modüle özel ayarlar
        when (module) {
            is KillAura  -> KillAuraOverlaySettings(module)
            is Criticals -> CriticalsOverlaySettings(module)
            is TPAura    -> TPAuraOverlaySettings(module)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  KILLAURA AYARLARI
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun KillAuraOverlaySettings(ka: KillAura) {
    Column(
        modifier = Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // CPS
        Text("CPS", color = OxText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniSlider("Min", ka.cpsMin, 1f..20f, "%.0f") { ka.cpsMin = it }
            MiniSlider("Max", ka.cpsMax, 1f..20f, "%.0f") { ka.cpsMax = it }
        }

        // Range
        MiniSlider("Range", ka.range, 1f..6f, "%.1f") { ka.range = it }

        // FOV
        MiniSlider("FOV", ka.fov, 30f..360f, "%.0f°") { ka.fov = it }

        // Switch Delay
        MiniSlider("Switch", ka.switchDelayMS.toFloat(), 0f..500f, "%.0fms") { 
            ka.switchDelayMS = it.toLong() 
        }

        HorizontalDivider(color = OxBorder.copy(alpha = 0.5f))

        // Attack Mode
        Text("Attack Mode", color = OxText, fontSize = 11.sp)
        ChipGroup(
            options = KillAura.AttackMode.entries.map { it.name },
            selected = ka.attackMode.name,
            onSelect = { ka.attackMode = KillAura.AttackMode.valueOf(it) }
        )

        // Rotation Mode
        Text("Rotation", color = OxText, fontSize = 11.sp)
        ChipGroup(
            options = KillAura.RotationMode.entries.map { it.name },
            selected = ka.rotationMode.name,
            onSelect = { ka.rotationMode = KillAura.RotationMode.valueOf(it) }
        )

        // Swing
        Text("Swing", color = OxText, fontSize = 11.sp)
        ChipGroup(
            options = KillAura.SwingMode.entries.map { it.name },
            selected = ka.swingMode.name,
            onSelect = { ka.swingMode = KillAura.SwingMode.valueOf(it) }
        )

        // Priority
        Text("Priority", color = OxText, fontSize = 11.sp)
        ChipGroup(
            options = KillAura.PriorityMode.entries.map { it.name },
            selected = ka.priorityMode.name,
            onSelect = { ka.priorityMode = KillAura.PriorityMode.valueOf(it) }
        )

        HorizontalDivider(color = OxBorder.copy(alpha = 0.5f))

        // Toggle'lar
        MiniToggle("Reverse Priority", ka.reversePriority) { ka.reversePriority = it }
        MiniToggle("Mouse Over", ka.mouseOver) { ka.mouseOver = it }
        MiniToggle("Swing Sound", ka.swingSound) { ka.swingSound = it }

        // Fail Rate
        MiniSlider("Fail Rate", ka.failRate, 0f..100f, "%.0f%%") { ka.failRate = it }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  CRITICALS AYARLARI
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun CriticalsOverlaySettings(crit: Criticals) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Mode", color = OxText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Criticals.CriticalMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { crit.mode = mode }
                    .background(
                        if (crit.mode == mode) OxPurple.copy(alpha = 0.2f)
                        else Color.Transparent
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RadioButton(
                    selected = crit.mode == mode,
                    onClick = { crit.mode = mode },
                    modifier = Modifier.size(16.dp),
                    colors = RadioButtonDefaults.colors(selectedColor = OxPurpleLight)
                )
                Text(
                    mode.name,
                    color = if (crit.mode == mode) OxPurpleLight else OxText,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  TPAURA AYARLARI
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun TPAuraOverlaySettings(tp: TPAura) {
    Column(
        modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Mode
        Text("Mode", color = OxText, fontSize = 11.sp)
        ChipGroup(
            options = TPAura.TPMode.entries.map { it.name },
            selected = tp.mode.name,
            onSelect = { tp.mode = TPAura.TPMode.valueOf(it) }
        )

        // Range
        MiniSlider("Range", tp.range, 1.5f..6f, "%.1f") { tp.range = it }

        // Y Offset
        MiniSlider("Y Offset", tp.yOffset, -2f..2f, "%.1f") { tp.yOffset = it }

        // Horizontal Speed
        MiniSlider("H Speed", tp.horizontalSpeed, 1f..20f, "%.1f") { tp.horizontalSpeed = it }

        // Vertical Speed
        MiniSlider("V Speed", tp.verticalSpeed, 1f..20f, "%.1f") { tp.verticalSpeed = it }

        // Strafe Speed
        MiniSlider("Strafe Spd", tp.strafeSpeed, 0.5f..10f, "%.1f") { tp.strafeSpeed = it }

        HorizontalDivider(color = OxBorder.copy(alpha = 0.5f))

        // Passive
        MiniToggle("Passive Mode", tp.passive) { tp.passive = it }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  MINİ AYAR BİLEŞENLERİ
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun MiniSlider(
    label   : String,
    value   : Float,
    range   : ClosedFloatingPointRange<Float>,
    format  : String,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = OxTextSub, fontSize = 10.sp)
            Text(
                format.replace("%.0f", "%.0f").replace("%.1f", "%.1f").replace("%.2f", "%.2f")
                    .let { fmt -> java.lang.String.format(fmt, value) },
                color = OxPurpleLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value      = value,
            onValueChange = onChange,
            valueRange = range,
            modifier   = Modifier.height(16.dp),
            colors     = SliderDefaults.colors(
                thumbColor        = OxPurpleLight,
                activeTrackColor  = OxPurple
            )
        )
    }
}

@Composable
private fun MiniToggle(
    label    : String,
    checked  : Boolean,
    onChange : (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = OxText, fontSize = 11.sp)
        Switch(
            checked         = checked,
            onCheckedChange = onChange,
            modifier        = Modifier.size(0.6f),
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = OxPurple,
                uncheckedThumbColor = OxTextSub,
                uncheckedTrackColor = OxBorder
            )
        )
    }
}

@Composable
private fun ChipGroup(
    options  : List<String>,
    selected : String,
    onSelect : (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(option) },
                shape = RoundedCornerShape(6.dp),
                color = if (option == selected) OxPurple.copy(alpha = 0.3f) 
                        else Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    if (option == selected) OxPurpleLight else OxBorder
                )
            ) {
                Text(
                    option,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color = if (option == selected) OxPurpleLight else OxTextSub,
                    fontSize = 9.sp,
                    fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// Yardımcı extension
fun Modifier.tabIndicatorOffset(tabPosition: TabPosition): Modifier =
    fillMaxWidth()
        .wrapContentSize(Alignment.BottomStart)
        .offset(x = tabPosition.left)
        .width(tabPosition.width)