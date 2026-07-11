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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.oxclient.auth.AccountManager
import com.oxclient.auth.AuthState
import com.oxclient.auth.DeviceCodeLoginActivity
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.auth.SavedAccount
import com.oxclient.config.ServerConfig
import com.oxclient.config.Config
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

// SETTINGS was removed as a standalone tab - server configuration now lives
// behind the top-right overflow menu on the Dashboard tab instead.
private enum class DashTab { RELAY, CONFIG, ACCOUNTS }

class DashboardActivity : ComponentActivity() {

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge so the bottom tab bar can be pulled up above the
        // system navigation bar with its own inset padding.
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
                    onCancelAuth  = { MicrosoftAuthManager.cancelSignIn() },
                    onSelectAccount = { account -> MicrosoftAuthManager.switchAccount(account) }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    installedApps : List<Pair<String, String>>,
    relayActive   : Boolean = false,
    onConnect     : (String) -> Unit,
    onDisconnect  : () -> Unit,
    onSignIn      : () -> Unit,
    onSignOut     : () -> Unit,
    onCancelAuth  : () -> Unit,
    onSelectAccount : (SavedAccount) -> Unit
) {
    val authState     by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
    val scope          = rememberCoroutineScope()
    val serverHost    by ServerConfig.host.collectAsState(initial = ServerConfig.DEFAULT_HOST)
    val serverPort    by ServerConfig.port.collectAsState(initial = ServerConfig.DEFAULT_PORT)
    val recentServers by ServerConfig.recents.collectAsState(initial = emptyList())
    val savedAccounts     by AccountManager.accountsFlow.collectAsStateWithLifecycle()
    val selectedGamertag  by AccountManager.selectedGamertagFlow.collectAsStateWithLifecycle()

    var showSignIn      by remember { mutableStateOf(false) }
    var showServerPanel by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { DashTab.values().size })
    val currentTab = DashTab.values()[pagerState.currentPage]

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

    Box(modifier = Modifier.fillMaxSize().background(OxBackground)) {
        Box(modifier = Modifier.fillMaxWidth().height(280.dp)
            .background(Brush.verticalGradient(listOf(OxAccentDark.copy(0.30f), Color.Transparent))))

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(28.dp))

                HorizontalPager(
                    state    = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { page ->
                    when (DashTab.values()[page]) {
                        DashTab.RELAY -> DashboardTab(
                            relayActive          = relayActive,
                            onToggle             = { if (relayActive) onDisconnect() else onConnect(targetApp.first) },
                            showServerPanel      = showServerPanel,
                            onToggleServerPanel  = { showServerPanel = !showServerPanel },
                            serverHost           = serverHost,
                            serverPort           = serverPort,
                            recentServers        = recentServers,
                            onSaveServer         = { h, p -> scope.launch { ServerConfig.save(h, p) }; showServerPanel = false },
                            onResetServer        = { scope.launch { ServerConfig.reset() } },
                            onDismissServerPanel = { showServerPanel = false }
                        )
                        DashTab.ACCOUNTS -> AccountsTab(
                            accounts         = savedAccounts,
                            selectedGamertag = selectedGamertag,
                            onSelectAccount  = onSelectAccount,
                            onAddAccount     = { showSignIn = true }
                        )
                        DashTab.CONFIG -> ConfigTab()
                    }
                }
            }
            BottomTabBar(
                current  = currentTab,
                onSelect = { tab -> scope.launch { pagerState.animateScrollToPage(tab.ordinal) } }
            )
        }
    }
}

// ---------------------------------------------------------------------
// Shared screen header - identical title style/spacing on every tab so
// Dashboard, Accounts and Configs all read as one consistent surface.
// ---------------------------------------------------------------------

@Composable
private fun ScreenHeader(
    title: String,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = trailing
    )
    Spacer(Modifier.height(18.dp))
    Text(
        title,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = OxOnBackground,
        fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun AddIconButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            .background(OxSurface)
            .border(1.dp, OxOutlineStrong, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OxAccentLight)
    }
}

@Composable
private fun DashboardTab(
    relayActive          : Boolean,
    onToggle             : () -> Unit,
    showServerPanel      : Boolean,
    onToggleServerPanel  : () -> Unit,
    serverHost           : String,
    serverPort           : Int,
    recentServers        : List<Pair<String, Int>>,
    onSaveServer         : (String, Int) -> Unit,
    onResetServer        : () -> Unit,
    onDismissServerPanel : () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "OxClient V1.1") {
            IconButton(onClick = onToggleServerPanel, modifier = Modifier.size(32.dp)) {
                MoreVertGlyph(tint = if (showServerPanel) OxAccentLight else OxOnSurfaceDim)
            }
        }

        AnimatedVisibility(
            visible = showServerPanel,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
        ) {
            Column {
                ServerSettingsPanel(
                    currentHost   = serverHost,
                    currentPort   = serverPort,
                    recentServers = recentServers,
                    onSave        = onSaveServer,
                    onReset       = onResetServer,
                    onDismiss     = onDismissServerPanel
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            if (relayActive) {
                LiveStatsCard()
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ConnectButton(running = relayActive, onToggle = onToggle)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AccountsTab(
    accounts         : List<SavedAccount>,
    selectedGamertag : String?,
    onSelectAccount  : (SavedAccount) -> Unit,
    onAddAccount     : () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Accounts") {
            AddIconButton(onClick = onAddAccount)
        }

        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "No accounts yet.\nTap + to sign in with Microsoft.",
                    color = OxOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                accounts.forEach { account ->
                    AccountRow(
                        account  = account,
                        selected = account.gamertag == selectedGamertag,
                        onClick  = { onSelectAccount(account) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account  : SavedAccount,
    selected : Boolean,
    onClick  : () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) OxAccentDark else OxSurface)
            .border(1.dp, if (selected) OxAccent else OxOutline, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) OxAccent.copy(alpha = 0.25f) else OxSurfaceVar),
                contentAlignment = Alignment.Center
            ) {
                PersonGlyph(tint = if (selected) OxAccentLight else OxOnSurfaceDim)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    account.gamertag,
                    color = OxOnBackground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (account.isExpired()) "Token expired — will refresh" else "Signed in",
                    color = if (account.isExpired()) OxWarning else OxOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
        if (selected) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(OxSuccess))
        }
    }
}

@Composable
private fun ConfigTab() {
    val scope = rememberCoroutineScope()
    val profiles       by Config.profiles.collectAsState(initial = emptyList())
    val activeProfile  by Config.activeProfile.collectAsState(initial = null)

    var showSaveDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Configs") {
            AddIconButton(onClick = { showSaveDialog = true })
        }

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No saved profiles.\nTap + to save current settings.",
                    color = OxOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                profiles.forEach { profile ->
                    ProfileRow(
                        name     = profile.name,
                        active   = profile.name == activeProfile,
                        onLoad   = { scope.launch { Config.load(profile.name) } },
                        onDelete = { scope.launch { Config.delete(profile.name) } }
                    )
                }
            }
        }
    }

    // Yeni profil kaydetme dialog'u
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Profile", fontFamily = FontFamily.Monospace) },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile Name", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OxAccent,
                        unfocusedBorderColor = OxOutlineStrong,
                        focusedLabelColor = OxAccentLight,
                        cursorColor = OxAccentLight
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        color = OxOnBackground
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newProfileName.trim()
                        if (trimmed.isNotEmpty()) {
                            scope.launch { Config.save(trimmed) }
                            newProfileName = ""
                            showSaveDialog = false
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OxAccent)
                ) {
                    Text("Save", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false; newProfileName = "" }) {
                    Text("Cancel", color = OxOnSurfaceDim, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = OxSurface,
            shape = RoundedCornerShape(10.dp)
        )
    }
}

@Composable
private fun ProfileRow(
    name: String,
    active: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) OxAccentDark else OxSurface)
            .border(1.dp, if (active) OxAccent else OxOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            name,
            color = OxOnBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onLoad,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OxAccentLight)
            ) {
                Text("Load", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = onDelete,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OxError)
            ) {
                Text("Delete", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
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

// ---------------------------------------------------------------------
// Bottom navigation - icon-only for inactive tabs, a highlighted pill
// with a label for the active tab. Same three destinations everywhere.
// ---------------------------------------------------------------------

@Composable
private fun BottomTabBar(current: DashTab, onSelect: (DashTab) -> Unit) {
    Column {
        HorizontalDivider(color = OxOutline)
        Row(
            modifier = Modifier.fillMaxWidth().background(OxSurface)
                .navigationBarsPadding()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabItem(
                icon     = { tint -> HomeGlyph(tint = tint) },
                label    = "Dashboard",
                selected = current == DashTab.RELAY,
                onClick  = { onSelect(DashTab.RELAY) }
            )
            TabItem(
                icon     = { tint -> DocumentGlyph(tint = tint) },
                label    = "Configs",
                selected = current == DashTab.CONFIG,
                onClick  = { onSelect(DashTab.CONFIG) }
            )
            TabItem(
                icon     = { tint -> PersonGlyph(tint = tint) },
                label    = "Accounts",
                selected = current == DashTab.ACCOUNTS,
                onClick  = { onSelect(DashTab.ACCOUNTS) }
            )
        }
    }
}

@Composable
private fun TabItem(
    icon     : @Composable (Color) -> Unit,
    label    : String,
    selected : Boolean,
    onClick  : () -> Unit
) {
    Column(
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) OxSurfaceVar else Color.Transparent)
                .padding(horizontal = if (selected) 18.dp else 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            icon(if (selected) OxAccentLight else OxOnSurfaceDim)
        }
        if (selected) {
            Spacer(Modifier.height(4.dp))
            Text(
                label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, color = OxAccentLight
            )
        }
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
private fun ConnectButton(running: Boolean, onToggle: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue   = if (running) OxError else OxConnectIdle,
        animationSpec = tween(300), label = "btnColor"
    )
    Button(
        onClick        = onToggle,
        shape          = RoundedCornerShape(50),
        colors         = ButtonDefaults.buttonColors(containerColor = bgColor),
        elevation      = ButtonDefaults.buttonElevation(0.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        modifier       = Modifier.height(52.dp)
    ) {
        RouterGlyph(tint = OxOnBackground)
        Spacer(Modifier.width(10.dp))
        Text(
            if (running) "Disconnect" else "Connect",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, color = OxOnBackground
        )
    }
}

@Composable
private fun ServerSettingsPanel(
    currentHost   : String,
    currentPort   : Int,
    recentServers : List<Pair<String, Int>>,
    onSave        : (String, Int) -> Unit,
    onReset       : () -> Unit,
    onDismiss     : () -> Unit
) {
    var hostInput by remember(currentHost) { mutableStateOf(currentHost) }
    var portInput by remember(currentPort) { mutableStateOf(currentPort.toString()) }
    var portError by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = OxSurface.copy(alpha = 0.90f)),
        border   = BorderStroke(1.dp, OxOutlineStrong)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TARGET SERVER", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                Text("CLOSE", fontSize = 10.sp, color = OxOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onDismiss() })
            }
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

// ---------------------------------------------------------------------
// Hand-drawn glyphs (Canvas) - avoids depending on the material-icons-
// extended artifact, which may not be pulled in by the CI build.
// ---------------------------------------------------------------------

@Composable
private fun RouterGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val arcCenter = Offset(w * 0.32f, h * 0.40f)
        for (i in 0..1) {
            val r = h * (0.20f + i * 0.16f)
            drawArc(
                color = tint,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(arcCenter.x - r, arcCenter.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = h * 0.07f, cap = StrokeCap.Round)
            )
        }
        drawCircle(color = tint, radius = h * 0.045f, center = Offset(arcCenter.x, arcCenter.y + h * 0.02f))

        val bodyTop = h * 0.58f
        val bodyHeight = h * 0.30f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, bodyTop),
            size = Size(w * 0.84f, bodyHeight),
            cornerRadius = CornerRadius(bodyHeight * 0.4f)
        )
        val dotY = bodyTop + bodyHeight / 2f
        val dotR = bodyHeight * 0.14f
        listOf(0.30f, 0.5f, 0.70f).forEach { fx ->
            drawCircle(color = OxBackground, radius = dotR, center = Offset(w * fx, dotY))
        }
    }
}

@Composable
private fun PersonGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(color = tint, radius = h * 0.20f, center = Offset(w / 2f, h * 0.32f))
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.88f)
            quadraticBezierTo(w * 0.20f, h * 0.55f, w * 0.5f, h * 0.55f)
            quadraticBezierTo(w * 0.80f, h * 0.55f, w * 0.80f, h * 0.88f)
            close()
        }
        drawPath(path = path, color = tint)
    }
}

@Composable
private fun HomeGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.90f, h * 0.42f)
            lineTo(w * 0.90f, h * 0.90f)
            lineTo(w * 0.58f, h * 0.90f)
            lineTo(w * 0.58f, h * 0.60f)
            lineTo(w * 0.42f, h * 0.60f)
            lineTo(w * 0.42f, h * 0.90f)
            lineTo(w * 0.10f, h * 0.90f)
            lineTo(w * 0.10f, h * 0.42f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = h * 0.09f, cap = StrokeCap.Round))
    }
}

@Composable
private fun DocumentGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.08f),
            size = Size(w * 0.64f, h * 0.84f),
            cornerRadius = CornerRadius(w * 0.06f),
            style = Stroke(width = h * 0.07f)
        )
        listOf(0.34f, 0.52f, 0.70f).forEach { fy ->
            drawLine(
                color = tint,
                start = Offset(w * 0.30f, h * fy),
                end   = Offset(w * 0.70f, h * fy),
                strokeWidth = h * 0.06f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun MoreVertGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.09f
        val spacing = size.height * 0.32f
        listOf(-1, 0, 1).forEach { i ->
            drawCircle(color = tint, radius = r, center = Offset(cx, cy + i * spacing))
        }
    }
}
