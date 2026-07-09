package com.oxclient.ui.dashboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
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
import com.oxclient.auth.AccountManager
import com.oxclient.auth.AuthState
import com.oxclient.auth.DeviceCodeLoginActivity
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.auth.SavedAccount
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

// SETTINGS was removed as a standalone tab - server configuration now lives
// behind the gear icon under the top bar instead.
private enum class DashTab { RELAY, ACCOUNTS, CONFIG }

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
    val isOnline      by rememberIsOnline()
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
                TopBar(authState = authState, onAvatarClick = { showSignIn = true })
                Spacer(Modifier.height(14.dp))

                // Settings gear (opens the target-server panel) + icon-only
                // network status indicator, right below the login section.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showServerPanel = !showServerPanel },
                        modifier = Modifier.size(32.dp)
                    ) {
                        GearGlyph(tint = if (showServerPanel) OxAccentLight else OxOnSurfaceDim)
                    }
                    WifiStatusGlyph(online = isOnline)
                }

                AnimatedVisibility(
                    visible = showServerPanel,
                    enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
                    exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
                ) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        ServerSettingsPanel(
                            currentHost   = serverHost,
                            currentPort   = serverPort,
                            recentServers = recentServers,
                            onSave        = { h, p -> scope.launch { ServerConfig.save(h, p) }; showServerPanel = false },
                            onReset       = { scope.launch { ServerConfig.reset() } },
                            onDismiss     = { showServerPanel = false }
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                HorizontalPager(
                    state    = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { page ->
                    when (DashTab.values()[page]) {
                        DashTab.RELAY -> RelayTab(
                            relayActive = relayActive,
                            onToggle    = { if (relayActive) onDisconnect() else onConnect(targetApp.first) }
                        )
                        DashTab.ACCOUNTS -> AccountsTab(
                            accounts         = savedAccounts,
                            selectedGamertag = selectedGamertag,
                            onSelectAccount  = onSelectAccount,
                            onAddAccount     = onSignIn
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

@Composable
private fun RelayTab(
    relayActive : Boolean,
    onToggle    : () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
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

@Composable
private fun AccountsTab(
    accounts         : List<SavedAccount>,
    selectedGamertag : String?,
    onSelectAccount  : (SavedAccount) -> Unit,
    onAddAccount     : () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Başlık + sağ üstte "+" (referans görseldeki gibi)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Accounts",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = OxOnBackground,
                fontFamily = FontFamily.Monospace
            )
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                    .background(OxSurface)
                    .border(1.dp, OxOutlineStrong, RoundedCornerShape(8.dp))
                    .clickable { onAddAccount() },
                contentAlignment = Alignment.Center
            ) {
                Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OxAccentLight)
            }
        }
        Spacer(Modifier.height(20.dp))

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
                .navigationBarsPadding()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TabItem("RELAY", current == DashTab.RELAY) { onSelect(DashTab.RELAY) }
            TabItem("ACCOUNTS", current == DashTab.ACCOUNTS) { onSelect(DashTab.ACCOUNTS) }
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
        Text("OXCLIENT", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold,
            color = OxOnBackground, fontFamily = FontFamily.Monospace)
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
                PersonGlyph(tint = if (isLoggedIn) OxAccentLight else OxOnSurfaceDim)
            }
        }
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
// Network connectivity observation (icon-only status indicator)
// ---------------------------------------------------------------------

@Composable
private fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(currentlyOnline(context)) }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = true }
            override fun onLost(network: Network) { state.value = currentlyOnline(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    return state
}

private fun currentlyOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
private fun GearGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = size.minDimension * 0.46f
        val innerR = size.minDimension * 0.28f
        val toothW = size.minDimension * 0.16f
        val toothLen = size.minDimension * 0.14f
        for (i in 0 until 8) {
            rotate(degrees = i * 45f, pivot = Offset(cx, cy)) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(cx - toothW / 2f, cy - outerR - toothLen / 2f),
                    size = Size(toothW, toothLen),
                    cornerRadius = CornerRadius(toothW * 0.3f)
                )
            }
        }
        drawCircle(color = tint, radius = outerR * 0.72f, center = Offset(cx, cy))
        drawCircle(color = OxBackground, radius = innerR * 0.55f, center = Offset(cx, cy))
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
private fun WifiStatusGlyph(online: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        targetValue = if (online) OxSuccess else OxOnSurfaceDim,
        animationSpec = tween(400), label = "wifiStatus"
    )
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val baseY = h * 0.85f
        drawCircle(color = color, radius = h * 0.06f, center = Offset(cx, baseY))
        for (i in 0..2) {
            val r = h * (0.18f + i * 0.20f)
            val fade = if (!online && i > 0) 0.35f else 1f
            drawArc(
                color = color.copy(alpha = color.alpha * fade),
                startAngle = 210f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(cx - r, baseY - r * 1.15f),
                size = Size(r * 2, r * 2),
                style = Stroke(width = h * 0.065f, cap = StrokeCap.Round)
            )
        }
    }
}
