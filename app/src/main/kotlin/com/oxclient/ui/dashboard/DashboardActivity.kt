package com.oxclient.ui.dashboard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.oxclient.auth.AuthState
import com.oxclient.auth.DeviceCodeLoginActivity
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.config.ServerConfig
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEventBus
import com.oxclient.module.ModuleManager
import com.oxclient.session.SessionManager
import com.oxclient.ui.overlay.OverlayService
import com.oxclient.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Supported target packages - no longer user-selectable, chosen automatically
val SUPPORTED_PACKAGES = listOf(
    "com.mojang.minecraftpe"      to "Minecraft",
    "com.netease.mc"              to "Minecraft (China)",
    "com.mojang.minecrafttrialpe" to "Minecraft Trial",
)

private enum class DashTab { RELAY, SETTINGS, CONFIG }

class DashboardActivity : ComponentActivity() {

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        // Observe WaitingForWebView state — open the WebView activity here
        // (from the Activity, not from Compose, for correct lifecycle handling)
        lifecycleScope.launch {
            MicrosoftAuthManager.authState.collect { state ->
                if (state is AuthState.WaitingForWebView) {
                    startActivity(Intent(this@DashboardActivity, DeviceCodeLoginActivity::class.java))
                }
            }
        }

        setContent {
            OxClientTheme {
                val authState   by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
                val relayActive by SessionManager.isActive.collectAsStateWithLifecycle()

                DashboardScreen(
                    installedApps = getInstalledGames(),
                    relayActive   = relayActive,
                    onConnect     = { pkg -> startRelay(pkg) },
                    onDisconnect  = { stopRelay() },
                    onSignIn      = { MicrosoftAuthManager.startSignIn() },
                    onSignOut     = { MicrosoftAuthManager.signOut() },
                    onCancelAuth  = { MicrosoftAuthManager.cancelSignIn() }
                )
            }
        }
    }

    private fun startRelay(targetPkg: String) {
        stopRelay()
        EntityTracker.init()

        lifecycleScope.launch(Dispatchers.IO) {
            SessionManager.start()
        }

        OverlayService.start(this)

        window.decorView.postDelayed({
            val intent = packageManager.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            } else {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$targetPkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }, 800)
    }

    private fun stopRelay() {
        SessionManager.stop()
        PacketEventBus.clear()
        EntityTracker.reset()
        OverlayService.stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRelay()
    }

    private fun getInstalledGames(): List<Pair<String, String>> =
        SUPPORTED_PACKAGES.filter { (pkg, _) ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                else
                    @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
        }
}

@Composable
fun DashboardScreen(
    installedApps : List<Pair<String, String>>,
    relayActive   : Boolean = false,
    onConnect     : (String) -> Unit,
    onDisconnect  : () -> Unit,
    onSignIn      : () -> Unit,
    onSignOut     : () -> Unit,
    onCancelAuth  : () -> Unit
) {
    val authState     by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
    val scope          = rememberCoroutineScope()
    val serverHost    by ServerConfig.host.collectAsState(initial = ServerConfig.DEFAULT_HOST)
    val serverPort    by ServerConfig.port.collectAsState(initial = ServerConfig.DEFAULT_PORT)
    val statusMessage by SessionManager.statusMessage.collectAsState()
    val recentServers by ServerConfig.recents.collectAsState(initial = emptyList())

    var showSignIn     by remember { mutableStateOf(false) }
    var showServerEdit by remember { mutableStateOf(false) }
    var currentTab     by remember { mutableStateOf(DashTab.RELAY) }

    // Target package is no longer asked from the user, it's picked automatically
    val targetApp = remember(installedApps) { installedApps.firstOrNull() ?: SUPPORTED_PACKAGES.first() }

    // Close the dialog once WaitingForWebView state is reached (WebView opened)
    LaunchedEffect(authState) {
        if (authState is AuthState.WaitingForWebView) showSignIn = false
    }

    if (showSignIn) {
        AuthDialog(
            authState = authState,
            onDismiss = { showSignIn = false },
            onSignIn  = onSignIn,
            onSignOut = { onSignOut(); showSignIn = false },
            onCancel  = { onCancelAuth(); showSignIn = false }
        )
    }

    if (showServerEdit) {
        ServerEditDialog(
            currentHost   = serverHost,
            currentPort   = serverPort,
            recentServers = recentServers,
            onSave        = { host, port -> scope.launch { ServerConfig.save(host, port) }; showServerEdit = false },
            onReset       = { scope.launch { ServerConfig.reset() }; showServerEdit = false },
            onDismiss     = { showServerEdit = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(OxBackground)) {
        Box(modifier = Modifier.fillMaxWidth().height(280.dp)
            .background(Brush.verticalGradient(listOf(OxAccentDark.copy(0.30f), Color.Transparent))))

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(28.dp))
                TopBar(authState = authState, onAvatarClick = { showSignIn = true })
                Spacer(Modifier.height(28.dp))

                when (currentTab) {
                    DashTab.RELAY -> RelayTab(
                        serverHost    = serverHost,
                        serverPort    = serverPort,
                        relayActive   = relayActive,
                        statusMessage = statusMessage,
                        onServerClick = { showServerEdit = true },
                        onToggle      = { if (relayActive) onDisconnect() else onConnect(targetApp.first) }
                    )
                    DashTab.SETTINGS -> SettingsTab()
                    DashTab.CONFIG   -> ConfigTab()
                }
            }
            BottomTabBar(current = currentTab, onSelect = { currentTab = it })
        }
    }
}

@Composable
private fun RelayTab(
    serverHost    : String,
    serverPort    : Int,
    relayActive   : Boolean,
    statusMessage : String,
    onServerClick : () -> Unit,
    onToggle      : () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LogoSection()
        Spacer(Modifier.height(28.dp))
        ServerCard(host = serverHost, port = serverPort, onClick = onServerClick)
        Spacer(Modifier.height(10.dp))
        StatusCard(running = relayActive, statusMessage = statusMessage)
        Spacer(Modifier.height(10.dp))
        if (relayActive) LiveStatsCard()
        Spacer(Modifier.weight(1f))
        ConnectButton(running = relayActive, onToggle = onToggle)
        Spacer(Modifier.height(12.dp))
        Text(
            "Overlay menu will appear in-game",
            fontSize = 11.sp, color = OxOnSurfaceDim,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsTab() {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("SETTINGS")
        Spacer(Modifier.height(16.dp))
        PlaceholderToggleRow("Notifications")
        PlaceholderToggleRow("Auto Connect")
        PlaceholderToggleRow("Run in Background")
        PlaceholderToggleRow("Vibration")
        Spacer(Modifier.height(16.dp))
        InactiveNotice()
    }
}

@Composable
private fun ConfigTab() {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("CONFIGURATION")
        Spacer(Modifier.height(16.dp))
        PlaceholderValueRow("Packet Size Limit", "8192 B")
        PlaceholderValueRow("Connection Timeout", "30 sec")
        PlaceholderValueRow("Log Level", "INFO")
        PlaceholderValueRow("Compression", "Zlib")
        Spacer(Modifier.height(16.dp))
        InactiveNotice()
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        color = OxOnBackground, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = OxOutline)
}

@Composable
private fun InactiveNotice() {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(OxSurfaceVar)
        .border(1.dp, OxOutline, RoundedCornerShape(8.dp))
        .padding(14.dp)
    ) {
        Text("This section is not active yet.", fontSize = 11.sp,
            color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PlaceholderToggleRow(label: String) {
    var checked by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(OxSurface)
        .border(1.dp, OxOutline, RoundedCornerShape(8.dp))
        .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = OxOnBackground, fontFamily = FontFamily.Monospace)
        Switch(
            checked = checked, onCheckedChange = { checked = it },
            colors = SwitchDefaults.colors(
                checkedThumbColor = OxAccentLight, checkedTrackColor = OxAccentDark,
                uncheckedThumbColor = OxOnSurfaceDim, uncheckedTrackColor = OxSurfaceVar
            )
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PlaceholderValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(OxSurface)
        .border(1.dp, OxOutline, RoundedCornerShape(8.dp))
        .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = OxOnBackground, fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = OxAccentLight, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BottomTabBar(current: DashTab, onSelect: (DashTab) -> Unit) {
    Column {
        HorizontalDivider(color = OxOutline)
        Row(
            modifier = Modifier.fillMaxWidth().background(OxSurface)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TabItem("RELAY", current == DashTab.RELAY) { onSelect(DashTab.RELAY) }
            TabItem("SETTINGS", current == DashTab.SETTINGS) { onSelect(DashTab.SETTINGS) }
            TabItem("CONFIG", current == DashTab.CONFIG) { onSelect(DashTab.CONFIG) }
        }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) OxAccentLight else OxOnSurfaceDim)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier.width(20.dp).height(2.dp)
                .background(if (selected) OxAccentLight else Color.Transparent)
        )
    }
}

@Composable
private fun LiveStatsCard() {
    var entityCount by remember { mutableIntStateOf(0) }
    var playerCount by remember { mutableIntStateOf(0) }
    var selfHp      by remember { mutableFloatStateOf(20f) }
    var activeCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            entityCount = EntityTracker.count()
            playerCount = EntityTracker.playerCount()
            selfHp      = EntityTracker.selfHealth
            activeCount = ModuleManager.enabledCount()
            kotlinx.coroutines.delay(1000L)
        }
    }

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(8.dp),
        colors    = CardDefaults.cardColors(containerColor = OxSurface),
        border    = BorderStroke(1.dp, OxOutline)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LiveStatChip("HP",  "%.0f".format(selfHp),
                if (selfHp <= 6f) OxError else OxSuccess)
            LiveStatChip("ENT", "$entityCount", OxOnSurface)
            LiveStatChip("PLR", "$playerCount", OxAccentLight)
            LiveStatChip("MOD", "$activeCount", OxAccentLight)
        }
    }
}

@Composable
private fun LiveStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = color, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TopBar(authState: AuthState, onAvatarClick: () -> Unit) {
    val isLoggedIn = authState is AuthState.Success
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("OXCLIENT", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold,
                color = OxOnBackground, fontFamily = FontFamily.Monospace)
            Text("RELAY CLIENT", fontSize = 10.sp, letterSpacing = 1.sp,
                color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoggedIn) {
                Text((authState as AuthState.Success).gamertag, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = OxAccentLight,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (isLoggedIn) OxAccentDark else OxSurface)
                    .border(1.dp, if (isLoggedIn) OxAccent else OxOutlineStrong, RoundedCornerShape(6.dp))
                    .clickable { onAvatarClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLoggedIn) (authState as AuthState.Success).gamertag.firstOrNull()?.uppercase() ?: "-" else "-",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (isLoggedIn) OxAccentLight else OxOnSurfaceDim,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun LogoSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(10.dp))
                .background(OxSurfaceRaised)
                .border(1.dp, OxOutlineStrong, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("0x", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                color = OxAccentLight, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(12.dp))
        Text("BEDROCK RELAY", fontSize = 12.sp, letterSpacing = 1.sp,
            color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ServerCard(host: String, port: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = OxSurface),
        border = BorderStroke(1.dp, OxOutline)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("TARGET SERVER", fontSize = 10.sp, letterSpacing = 0.5.sp,
                    color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
                Text("$host:$port", fontSize = 13.sp, color = OxOnBackground,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(OxAccent))
                Text("EDIT", fontSize = 10.sp, color = OxAccentLight, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, statusMessage: String) {
    val statusColor by animateColorAsState(
        targetValue  = if (running) OxSuccess else OxOnSurfaceDim,
        animationSpec = tween(500), label = "status"
    )
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = OxSurface),
        border = BorderStroke(1.dp, OxOutline)
    ) {
        Row(modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(statusColor))
            Column {
                Text(if (running) "RELAY ACTIVE" else "RELAY OFFLINE", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) OxSuccess else OxOnBackground,
                    fontFamily = FontFamily.Monospace)
                Text(statusMessage.ifBlank { if (running) "Routing traffic" else "Tap Connect to start" },
                    fontSize = 11.sp, color = OxOnSurfaceDim,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConnectButton(running: Boolean, onToggle: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue   = if (running) OxError else OxAccent,
        animationSpec = tween(300), label = "btnColor"
    )
    Button(
        onClick   = onToggle,
        modifier  = Modifier.fillMaxWidth().height(54.dp),
        shape     = RoundedCornerShape(8.dp),
        colors    = ButtonDefaults.buttonColors(containerColor = bgColor),
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        Text(if (running) "DISCONNECT" else "CONNECT",
            fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ServerEditDialog(
    currentHost   : String,
    currentPort   : Int,
    recentServers : List<Pair<String, Int>>,
    onSave        : (String, Int) -> Unit,
    onReset       : () -> Unit,
    onDismiss     : () -> Unit
) {
    var hostInput by remember { mutableStateOf(currentHost) }
    var portInput by remember { mutableStateOf(currentPort.toString()) }
    var portError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface),
            border = BorderStroke(1.dp, OxOutline)
        ) {
            Column(modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SERVER SETTINGS", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = OxOutline)
                OutlinedTextField(
                    value = hostInput, onValueChange = { hostInput = it },
                    label = { Text("Server Address", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OxAccent, unfocusedBorderColor = OxOutlineStrong,
                        focusedLabelColor = OxAccentLight, cursorColor = OxAccentLight),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = OxOnBackground)
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it; portError = it.toIntOrNull()?.let { p -> p < 1 || p > 65535 } ?: true },
                    label = { Text("Port", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    singleLine = true, isError = portError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OxAccent, unfocusedBorderColor = OxOutlineStrong,
                        focusedLabelColor = OxAccentLight, cursorColor = OxAccentLight),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = OxOnBackground),
                    supportingText = if (portError) {
                        { Text("Valid port range is 1-65535", color = OxError, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                    } else null
                )
                if (recentServers.isNotEmpty()) {
                    Text("RECENT SERVERS", fontSize = 10.sp,
                        color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recentServers.forEach { (h, p) ->
                            Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(OxSurfaceVar)
                                .border(1.dp, OxOutlineStrong, RoundedCornerShape(6.dp))
                                .clickable { hostInput = h; portInput = p.toString(); portError = false }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("$h:$p", fontSize = 9.sp,
                                    color = OxAccentLight, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, OxOutlineStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OxOnSurface)
                    ) { Text("DEFAULT", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    Button(
                        onClick = {
                            val p = portInput.toIntOrNull()
                            if (hostInput.isBlank() || p == null || p < 1 || p > 65535) { portError = true; return@Button }
                            onSave(hostInput.trim(), p)
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OxAccent)
                    ) { Text("SAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("CANCEL", color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun AuthDialog(
    authState : AuthState,
    onDismiss : () -> Unit,
    onSignIn  : () -> Unit,
    onSignOut : () -> Unit,
    onCancel  : () -> Unit
) {
    val canDismiss = authState !is AuthState.Loading && authState !is AuthState.WaitingForWebView
    Dialog(onDismissRequest = { if (canDismiss) onDismiss() }) {
        Card(shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface),
            border = BorderStroke(1.dp, OxOutline)
        ) {
            Column(modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("MICROSOFT ACCOUNT", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = OxOutline)
                when (authState) {
                    is AuthState.Success -> {
                        Text("SIGNED IN", color = OxSuccess, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(authState.gamertag, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = OxOnBackground, fontFamily = FontFamily.Monospace)
                        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxError),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("SIGN OUT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.WaitingForWebView, is AuthState.Loading -> {
                        // WebView opening or token being processed
                        CircularProgressIndicator(color = OxAccentLight, strokeWidth = 2.dp)
                        Text(
                            if (authState is AuthState.WaitingForWebView)
                                "Opening Microsoft sign-in window..."
                            else
                                "Verifying account...",
                            color = OxOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = onCancel) {
                            Text("CANCEL", color = OxError, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.Error -> {
                        Text("ERROR: ${authState.message}", color = OxError,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxAccent),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("RETRY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                    else -> {
                        // Idle or WaitingForUser (legacy flow compatibility)
                        Text("Sign in with your Xbox/Microsoft account.\nThe sign-in page will open inside the app.",
                            color = OxOnSurface, fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center, fontSize = 13.sp)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxAccent),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("SIGN IN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}