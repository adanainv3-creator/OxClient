package com.oxclient.ui.dashboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleManager
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.KillAura
import com.oxclient.module.movement.TPAura
import com.oxclient.proxy.BedrockRelay
import com.oxclient.proxy.BedrockRelayService
import com.oxclient.session.ServerConfig
import com.oxclient.session.SessionManager
import com.oxclient.ui.overlay.OverlayService
import com.oxclient.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class DashboardActivity : ComponentActivity() {

    private var relayService: BedrockRelayService? = null
    private var relayBound   = false

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            relayService = (binder as BedrockRelayService.RelayBinder).getService()
            relayBound   = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            relayBound = false; relayService = null
        }
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) launchOverlay()
        else Toast.makeText(this, "Overlay izni gerekli!", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionManager = SessionManager(this)

        setContent {
            OxTheme {
                DashboardScreen(
                    sessionManager = sessionManager,
                    onStartRelay   = { server -> startRelay(server) },
                    onStopRelay    = { stopRelay() },
                    onToggleOverlay= { checkAndLaunchOverlay() },
                    onLaunchMinecraft = { launchMinecraft() },
                    getRelayRunning = { relayService?.isRunning ?: false },
                    getStats = { relayService?.getStats() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, BedrockRelayService::class.java),
            serviceConn, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (relayBound) { unbindService(serviceConn); relayBound = false }
    }

    private fun startRelay(server: ServerConfig) {
        val intent = Intent(this, BedrockRelayService::class.java).apply {
            putExtra(BedrockRelayService.EXTRA_HOST, server.host)
            putExtra(BedrockRelayService.EXTRA_PORT, server.port)
            putExtra(BedrockRelayService.EXTRA_NAME, server.name)
        }
        startForegroundService(intent)
    }

    private fun stopRelay() {
        relayService?.stopRelay()
        stopService(Intent(this, BedrockRelayService::class.java))
    }

    private fun checkAndLaunchOverlay() {
        if (Settings.canDrawOverlays(this)) launchOverlay()
        else overlayPermLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
        )
    }

    private fun launchOverlay() {
        startService(Intent(this, OverlayService::class.java))
    }

    private fun launchMinecraft() {
        val packages = listOf("com.mojang.minecraftpe", "com.netease.mc")
        for (pkg in packages) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it); return
            }
        }
        Toast.makeText(this, "Minecraft bulunamadı", Toast.LENGTH_SHORT).show()
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  COMPOSE EKRANI
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    sessionManager : SessionManager,
    onStartRelay   : (ServerConfig) -> Unit,
    onStopRelay    : () -> Unit,
    onToggleOverlay: () -> Unit,
    onLaunchMinecraft: () -> Unit,
    getRelayRunning: () -> Boolean,
    getStats       : () -> BedrockRelay.RelayStats?
) {
    var selectedServer  by remember { mutableStateOf<ServerConfig?>(null) }
    var isRelayRunning  by remember { mutableStateOf(false) }
    var servers         by remember { mutableStateOf(ServerConfig.PRESETS) }
    var stats           by remember { mutableStateOf<BedrockRelay.RelayStats?>(null) }
    var selectedTab     by remember { mutableIntStateOf(0) }
    var showAddDialog   by remember { mutableStateOf(false) }

    // Modül ayar paneli state'i
    var settingsModule   by remember { mutableStateOf<BaseModule?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            isRelayRunning = getRelayRunning()
            stats          = getStats()
            delay(500)
        }
    }

    LaunchedEffect(Unit) {
        sessionManager.serversFlow.collectLatest { servers = it }
    }

    Scaffold(
        containerColor = OxBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(OxPurple, OxPurpleDark))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Ox", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                        Text("OxClient", color = OxText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (isRelayRunning) {
                            Surface(shape = RoundedCornerShape(6.dp), color = OxGreen.copy(alpha = 0.2f)) {
                                Text("LIVE", color = OxGreen,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OxSurface),
                actions = {
                    IconButton(onClick = onLaunchMinecraft) {
                        Icon(Icons.Default.SportsEsports, "MC Aç", tint = OxTextSub)
                    }
                    IconButton(onClick = onToggleOverlay) {
                        Icon(Icons.Default.Layers, "Overlay", tint = OxTextSub)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = OxSurface,
                contentColor     = OxPurpleLight,
                indicator        = { tabs -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tabs[selectedTab]), color = OxPurple) }
            ) {
                listOf("Sunucular", "Modüller", "İstatistik").forEachIndexed { i, title ->
                    Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                        text = { Text(title, color = if (selectedTab == i) OxPurpleLight else OxTextSub) })
                }
            }

            when (selectedTab) {
                0 -> ServersTab(
                    servers         = servers,
                    selectedServer  = selectedServer,
                    isRelayRunning  = isRelayRunning,
                    onSelect        = { selectedServer = it },
                    onStart         = { selectedServer?.let { onStartRelay(it) } },
                    onStop          = onStopRelay,
                    onAdd           = { showAddDialog = true },
                    sessionManager  = sessionManager
                )
                1 -> ModulesTab(
                    onOpenSettings = { settingsModule = it }
                )
                2 -> StatsTab(stats = stats, isRunning = isRelayRunning)
            }
        }
    }

    // Sunucu ekleme dialog'u
    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd     = { server ->
                kotlinx.coroutines.GlobalScope.launch { sessionManager.saveServer(server) }
                showAddDialog = false
            }
        )
    }

    // Modül ayarları dialog'u
    if (settingsModule != null) {
        ModuleSettingsDialog(
            module     = settingsModule!!,
            onDismiss  = { settingsModule = null }
        )
    }
}

// ── Sunucular Tab ─────────────────────────────────────────────────────────
// (Mevcut haliyle aynı, değişiklik yok)

@Composable
private fun ServersTab(
    servers        : List<ServerConfig>,
    selectedServer : ServerConfig?,
    isRelayRunning : Boolean,
    onSelect       : (ServerConfig) -> Unit,
    onStart        : () -> Unit,
    onStop         : () -> Unit,
    onAdd          : () -> Unit,
    sessionManager : SessionManager
) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(
                onClick = { if (isRelayRunning) onStop() else onStart() },
                enabled = selectedServer != null || isRelayRunning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = if (isRelayRunning) OxRed else OxPurple
                ),
                shape   = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    if (isRelayRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    null, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRelayRunning) "Relay Durdur" else "Relay Başlat",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        item {
            if (selectedServer != null && !isRelayRunning) {
                InfoCard(
                    text  = "Hazır: ${selectedServer.name} → ${selectedServer.host}:${selectedServer.port}",
                    color = OxPurpleLight
                )
            }
            if (isRelayRunning) {
                InfoCard(text = "MC'de sunucu olarak 127.0.0.1:19132 gir", color = OxYellow)
            }
        }

        items(servers, key = { it.id }) { server ->
            ServerCard(
                server   = server,
                selected = selectedServer?.id == server.id,
                onClick  = { onSelect(server) }
            )
        }

        item {
            OutlinedButton(
                onClick  = onAdd,
                modifier = Modifier.fillMaxWidth(),
                border   = BorderStroke(1.dp, OxBorder),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = OxPurpleLight)
                Spacer(Modifier.width(8.dp))
                Text("Sunucu Ekle", color = OxPurpleLight)
            }
        }
    }
}

@Composable
private fun ServerCard(server: ServerConfig, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape    = RoundedCornerShape(14.dp),
        color    = if (selected) OxPurple.copy(alpha = 0.2f) else OxSurface,
        border   = BorderStroke(1.dp, if (selected) OxPurple else OxBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(server.icon, fontSize = 28.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, color = OxText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("${server.host}:${server.port}", color = OxTextSub, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = OxPurpleLight, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun InfoCard(text: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        color    = color.copy(alpha = 0.12f),
        border   = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, null, tint = color, modifier = Modifier.size(16.dp))
            Text(text, color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Modüller Tab (Ayarlar butonu eklendi) ────────────────────────────────

@Composable
private fun ModulesTab(
    onOpenSettings: (BaseModule) -> Unit
) {
    val modules = ModuleManager.getAll()
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BaseModule.Category.entries.forEach { cat ->
            val catModules = modules.filter { it.category == cat }
            if (catModules.isEmpty()) return@forEach
            item {
                Text(
                    cat.name,
                    color    = OxTextSub,
                    style    = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(catModules, key = { it.name }) { module ->
                ModuleCardWithSettings(
                    module         = module,
                    onOpenSettings = { onOpenSettings(module) }
                )
            }
        }
    }
}

@Composable
private fun ModuleCardWithSettings(
    module         : BaseModule,
    onOpenSettings : () -> Unit
) {
    var enabled by remember { mutableStateOf(module.enabled) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        color    = if (enabled) OxPurple.copy(alpha = 0.18f) else OxSurface,
        border   = BorderStroke(1.dp, if (enabled) OxPurple.copy(alpha = 0.5f) else OxBorder)
    ) {
        Column {
            Row(
                modifier  = Modifier
                    .clickable {
                        module.toggle()
                        enabled = module.enabled
                    }
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(module.name, color = if (enabled) OxPurpleLight else OxText,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(module.description, color = OxTextSub, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked         = enabled,
                    onCheckedChange = { module.toggle(); enabled = module.enabled },
                    colors          = SwitchDefaults.colors(
                        checkedThumbColor  = Color.White,
                        checkedTrackColor  = OxPurple,
                        uncheckedThumbColor= OxTextSub,
                        uncheckedTrackColor= OxBorder
                    )
                )
            }
            // Ayarlar butonu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Ayarlar",
                    tint = OxTextSub,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Ayarlar",
                    color = OxTextSub,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ── İstatistik Tab ────────────────────────────────────────────────────────

@Composable
private fun StatsTab(stats: BedrockRelay.RelayStats?, isRunning: Boolean) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("MITM Durumu", color = OxTextSub, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        item {
            StatCard("Durum",    if (isRunning) "Aktif" else "Pasif",
                icon = Icons.Default.RadioButtonChecked,
                color = if (isRunning) OxGreen else OxRed)
        }
        if (stats != null) {
            item { StatCard("Bağlı İstemci", stats.connectedClients.toString(), Icons.Default.Person, OxCyan) }
            item { StatCard("İnterceptler",  stats.packetsIntercepted.toString(), Icons.Default.SwapHoriz, OxPurpleLight) }
            item { StatCard("Enjeksiyonlar", stats.packetsInjected.toString(), Icons.Default.Add, OxYellow) }
            item { StatCard("İptal",         stats.packetsCancelled.toString(), Icons.Default.Block, OxRed) }
            item { StatCard("Toplam IN",     "${stats.bytesIn / 1024} KB", Icons.Default.Download, OxGreen) }
            item { StatCard("Toplam OUT",    "${stats.bytesOut / 1024} KB", Icons.Default.Upload, OxCyan) }
        } else {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Relay başlatılmadı", color = OxTextSub, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: ImageVector, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        color    = OxSurface,
        border   = BorderStroke(1.dp, OxBorder)
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)) }

            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = OxTextSub, style = MaterialTheme.typography.bodySmall)
                Text(value, color = OxText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  MODÜL AYARLARI DIALOG'U
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleSettingsDialog(
    module    : BaseModule,
    onDismiss : () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = OxSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Settings, null, tint = OxPurpleLight)
                Text("${module.name} Ayarları", color = OxText, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (module) {
                    is KillAura  -> KillAuraSettings(module)
                    is Criticals -> CriticalsSettings(module)
                    is TPAura    -> TPAuraSettings(module)
                    else -> Text("Bu modül için ayar bulunmuyor", color = OxTextSub)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat", color = OxPurpleLight)
            }
        }
    )
}

// ── KillAura Ayarları ─────────────────────────────────────────────────────

@Composable
private fun KillAuraSettings(ka: KillAura) {
    // CPS
    Text("CPS", color = OxText, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SliderSetting(
            label  = "Min",
            value  = ka.cpsMin,
            range  = 1f..20f,
            steps  = 19,
            format = { "%.0f".format(it) }
        ) { ka.cpsMin = it }
        SliderSetting(
            label  = "Max",
            value  = ka.cpsMax,
            range  = 1f..20f,
            steps  = 19,
            format = { "%.0f".format(it) }
        ) { ka.cpsMax = it }
    }

    HorizontalDivider(color = OxBorder, modifier = Modifier.padding(vertical = 4.dp))

    // Range
    SliderSetting(
        label  = "Range",
        value  = ka.range,
        range  = 1f..6f,
        steps  = 50,
        format = { "%.2f".format(it) }
    ) { ka.range = it }

    // FOV
    SliderSetting(
        label  = "FOV",
        value  = ka.fov,
        range  = 30f..360f,
        steps  = 33,
        format = { "%.0f°".format(it) }
    ) { ka.fov = it }

    // Switch Delay
    SliderSetting(
        label  = "Switch Delay",
        value  = ka.switchDelayMS.toFloat(),
        range  = 0f..500f,
        steps  = 50,
        format = { "%.0f ms".format(it) }
    ) { ka.switchDelayMS = it.toLong() }

    HorizontalDivider(color = OxBorder, modifier = Modifier.padding(vertical = 4.dp))

    // Attack Mode
    Text("Attack Mode", color = OxText, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KillAura.AttackMode.entries.forEach { mode ->
            FilterChip(
                selected = ka.attackMode == mode,
                onClick  = { ka.attackMode = mode },
                label    = { Text(mode.name, style = MaterialTheme.typography.labelSmall) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OxPurple.copy(alpha = 0.3f),
                    selectedLabelColor = OxPurpleLight
                )
            )
        }
    }

    // Rotation Mode
    Text("Rotation Mode", color = OxText, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KillAura.RotationMode.entries.forEach { mode ->
            FilterChip(
                selected = ka.rotationMode == mode,
                onClick  = { ka.rotationMode = mode },
                label    = { Text(mode.name, style = MaterialTheme.typography.labelSmall) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OxPurple.copy(alpha = 0.3f),
                    selectedLabelColor = OxPurpleLight
                )
            )
        }
    }

    // Swing Mode
    Text("Swing", color = OxText, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KillAura.SwingMode.entries.forEach { mode ->
            FilterChip(
                selected = ka.swingMode == mode,
                onClick  = { ka.swingMode = mode },
                label    = { Text(mode.name, style = MaterialTheme.typography.labelSmall) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OxPurple.copy(alpha = 0.3f),
                    selectedLabelColor = OxPurpleLight
                )
            )
        }
    }

    // Priority Mode
    Text("Priority Mode", color = OxText, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KillAura.PriorityMode.entries.forEach { mode ->
            FilterChip(
                selected = ka.priorityMode == mode,
                onClick  = { ka.priorityMode = mode },
                label    = { Text(mode.name, style = MaterialTheme.typography.labelSmall) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OxPurple.copy(alpha = 0.3f),
                    selectedLabelColor = OxPurpleLight
                )
            )
        }
    }

    HorizontalDivider(color = OxBorder, modifier = Modifier.padding(vertical = 4.dp))

    // Toggle ayarlar
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Reverse Priority", color = OxText)
        Switch(
            checked = ka.reversePriority,
            onCheckedChange = { ka.reversePriority = it },
            colors  = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = OxPurple
            )
        )
    }

    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Mouse Over", color = OxText)
        Switch(
            checked = ka.mouseOver,
            onCheckedChange = { ka.mouseOver = it },
            colors  = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = OxPurple
            )
        )
    }

    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Swing Sound", color = OxText)
        Switch(
            checked = ka.swingSound,
            onCheckedChange = { ka.swingSound = it },
            colors  = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = OxPurple
            )
        )
    }

    // Fail Rate
    SliderSetting(
        label  = "Fail Rate",
        value  = ka.failRate,
        range  = 0f..100f,
        steps  = 100,
        format = { "%.0f%%".format(it) }
    ) { ka.failRate = it }

    // Shortcut
    OutlinedTextField(
        value       = ka.shortcut,
        onValueChange = { ka.shortcut = it },
        label       = { Text("Shortcut", color = OxTextSub) },
        placeholder = { Text("Örn: F", color = OxTextSub.copy(alpha = 0.5f)) },
        modifier    = Modifier.fillMaxWidth(),
        singleLine  = true,
        colors      = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = OxPurple,
            unfocusedBorderColor = OxBorder,
            cursorColor          = OxPurple,
            focusedTextColor     = OxText,
            unfocusedTextColor   = OxText
        )
    )
}

// ── Criticals Ayarları ────────────────────────────────────────────────────

@Composable
private fun CriticalsSettings(crit: Criticals) {
    Text("Mode", color = OxText, fontWeight = FontWeight.Medium)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Criticals.CriticalMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { crit.mode = mode }
                    .background(
                        if (crit.mode == mode) OxPurple.copy(alpha = 0.2f)
                        else Color.Transparent
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RadioButton(
                    selected = crit.mode == mode,
                    onClick  = { crit.mode = mode },
                    colors   = RadioButtonDefaults.colors(
                        selectedColor = OxPurpleLight
                    )
                )
                Column {
                    Text(
                        mode.name,
                        color = if (crit.mode == mode) OxPurpleLight else OxText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        when (mode) {
                            Criticals.CriticalMode.VANILLA    -> "onGround=false ile kritik"
                            Criticals.CriticalMode.JUMP       -> "Küçük y-offset enjekte et"
                            Criticals.CriticalMode.TPJUMP     -> "Hedefe ışınla + kritik"
                            Criticals.CriticalMode.MOVEPACKET -> "MovePlayer manipülasyonu"
                        },
                        color = OxTextSub,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ── TPAura Ayarları ───────────────────────────────────────────────────────

@Composable
private fun TPAuraSettings(tp: TPAura) {
    // Mode
    Text("Mode", color = OxText, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TPAura.TPMode.entries.forEach { mode ->
            FilterChip(
                selected = tp.mode == mode,
                onClick  = { tp.mode = mode },
                label    = { Text(mode.name, style = MaterialTheme.typography.labelSmall) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OxPurple.copy(alpha = 0.3f),
                    selectedLabelColor = OxPurpleLight
                )
            )
        }
    }

    HorizontalDivider(color = OxBorder, modifier = Modifier.padding(vertical = 4.dp))

    // Range
    SliderSetting(
        label  = "Range",
        value  = tp.range,
        range  = 1.5f..6f,
        steps  = 45,
        format = { "%.2f".format(it) }
    ) { tp.range = it }

    // Y Offset
    SliderSetting(
        label  = "Y Offset",
        value  = tp.yOffset,
        range  = -2f..2f,
        steps  = 40,
        format = { "%.2f".format(it) }
    ) { tp.yOffset = it }

    // Horizontal Speed
    SliderSetting(
        label  = "Horizontal Speed",
        value  = tp.horizontalSpeed,
        range  = 1f..20f,
        steps  = 19,
        format = { "%.2f".format(it) }
    ) { tp.horizontalSpeed = it }

    // Vertical Speed
    SliderSetting(
        label  = "Vertical Speed",
        value  = tp.verticalSpeed,
        range  = 1f..20f,
        steps  = 19,
        format = { "%.2f".format(it) }
    ) { tp.verticalSpeed = it }

    // Strafe Speed
    SliderSetting(
        label  = "Strafe Speed",
        value  = tp.strafeSpeed,
        range  = 0.5f..10f,
        steps  = 19,
        format = { "%.2f".format(it) }
    ) { tp.strafeSpeed = it }

    HorizontalDivider(color = OxBorder, modifier = Modifier.padding(vertical = 4.dp))

    // Passive
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Passive", color = OxText)
        Switch(
            checked = tp.passive,
            onCheckedChange = { tp.passive = it },
            colors  = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = OxPurple
            )
        )
    }

    // Shortcut
    OutlinedTextField(
        value       = tp.shortcut,
        onValueChange = { tp.shortcut = it },
        label       = { Text("Shortcut", color = OxTextSub) },
        placeholder = { Text("Örn: R", color = OxTextSub.copy(alpha = 0.5f)) },
        modifier    = Modifier.fillMaxWidth(),
        singleLine  = true,
        colors      = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = OxPurple,
            unfocusedBorderColor = OxBorder,
            cursorColor          = OxPurple,
            focusedTextColor     = OxText,
            unfocusedTextColor   = OxText
        )
    )
}

// ── Slider Bileşeni ──────────────────────────────────────────────────────

@Composable
private fun SliderSetting(
    label  : String,
    value  : Float,
    range  : ClosedFloatingPointRange<Float>,
    steps  : Int,
    format : (Float) -> String,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = OxTextSub, style = MaterialTheme.typography.bodySmall)
            Text(format(value), color = OxPurpleLight, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        Slider(
            value      = value,
            onValueChange = onChange,
            valueRange = range,
            steps      = steps,
            colors     = SliderDefaults.colors(
                thumbColor   = OxPurpleLight,
                activeTrackColor = OxPurple
            )
        )
    }
}

// ── Sunucu Ekle Dialog ────────────────────────────────────────────────────

@Composable
private fun AddServerDialog(onDismiss: () -> Unit, onAdd: (ServerConfig) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("19132") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = OxSurface,
        title = { Text("Sunucu Ekle", color = OxText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OxTextField(name, { name = it }, "Sunucu Adı")
                OxTextField(host, { host = it }, "Host / IP")
                OxTextField(port, { port = it }, "Port")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && host.isNotBlank()) {
                        onAdd(ServerConfig(
                            name = name, host = host,
                            port = port.toIntOrNull() ?: 19132
                        ))
                    }
                }
            ) { Text("Ekle", color = OxPurpleLight) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("İptal", color = OxTextSub) }
        }
    )
}

@Composable
private fun OxTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, color = OxTextSub) },
        modifier      = Modifier.fillMaxWidth(),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = OxPurple,
            unfocusedBorderColor = OxBorder,
            cursorColor          = OxPurple,
            focusedTextColor     = OxText,
            unfocusedTextColor   = OxText
        )
    )
}

@Composable
fun TabRowDefaults.SecondaryIndicator(modifier: Modifier, color: Color) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(color))
}

fun Modifier.tabIndicatorOffset(tabPosition: TabPosition): Modifier =
    fillMaxWidth()
        .wrapContentSize(Alignment.BottomStart)
        .offset(x = tabPosition.left)
        .width(tabPosition.width)