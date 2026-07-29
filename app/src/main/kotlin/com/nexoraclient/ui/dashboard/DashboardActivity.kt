package com.nexoraclient.ui.dashboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.nexoraclient.auth.AccountManager
import com.nexoraclient.auth.AuthState
import com.nexoraclient.auth.DeviceCodeLoginActivity
import com.nexoraclient.auth.MicrosoftAuthManager
import com.nexoraclient.auth.SavedAccount
import com.nexoraclient.config.ServerConfig
import com.nexoraclient.config.Config
import com.nexoraclient.config.MapArtPlan
import com.nexoraclient.utils.BlockPalette
import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.session.SessionManager
import com.nexoraclient.ui.overlay.OverlayService
import com.nexoraclient.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val SUPPORTED_PACKAGES = listOf(
    "com.mojang.minecraftpe"      to "Minecraft",
    "com.netease.mc"              to "Minecraft (China)",
    "com.mojang.minecrafttrialpe" to "Minecraft Trial",
)

data class InstalledAppInfo(
    val packageName : String,
    val label        : String
)

/** Remembers which app the relay should target, across app restarts. */
private object SelectedAppStore {
    private const val PREFS_NAME = "nexoraclient_prefs"
    private const val KEY_SELECTED_PACKAGE = "selected_package"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PACKAGE, null)

    fun set(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_PACKAGE, packageName).apply()
    }
}

/** Caches a successful password unlock so the user isn't asked again for a while. */
private object UnlockSessionStore {
    private const val PREFS_NAME = "nexoraclient_prefs"
    private const val KEY_UNLOCKED_UNTIL = "unlocked_until"
    private const val SESSION_DURATION_MS = 3 * 60 * 60 * 1000L // 3 saat

    fun isUnlocked(context: Context): Boolean {
        val until = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_UNLOCKED_UNTIL, 0L)
        return System.currentTimeMillis() < until
    }

    fun markUnlocked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_UNLOCKED_UNTIL, System.currentTimeMillis() + SESSION_DURATION_MS)
            .apply()
    }
}

private enum class DashTab { RELAY, CONFIG, ACCOUNTS }

class DashboardActivity : ComponentActivity() {

    private var overlayPermissionGranted by mutableStateOf(false)
    private var selectedPackage by mutableStateOf("com.mojang.minecraftpe")

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { overlayPermissionGranted = Settings.canDrawOverlays(this) }

    private fun requestOverlayPermission() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        overlayPermissionGranted = Settings.canDrawOverlays(this)

        // Restore the last app the user picked; fall back to whichever
        // supported package is actually installed, then plain Minecraft.
        selectedPackage = SelectedAppStore.get(this)
            ?: getInstalledGames().firstOrNull()?.first
            ?: SUPPORTED_PACKAGES.first().first

        lifecycleScope.launch {
            MicrosoftAuthManager.authState.collect { state ->
                if (state is AuthState.WaitingForWebView) {
                    startActivity(Intent(this@DashboardActivity, DeviceCodeLoginActivity::class.java))
                }
            }
        }

        setContent {
            NexoraClientTheme {
                var unlocked by remember { mutableStateOf(UnlockSessionStore.isUnlocked(this@DashboardActivity)) }

                if (!unlocked) {
                    PasswordGateScreen(onUnlock = {
                        UnlockSessionStore.markUnlocked(this@DashboardActivity)
                        unlocked = true
                    })
                } else {
                    val authState   by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
                    val relayActive by SessionManager.isActive.collectAsStateWithLifecycle()

                    DashboardScreen(
                        installedApps   = getInstalledGames(),
                        allApps         = getAllInstalledApps(),
                        selectedPackage = selectedPackage,
                        onSelectApp     = { pkg ->
                            selectedPackage = pkg
                            SelectedAppStore.set(this, pkg)
                        },
                        relayActive   = relayActive,
                        onConnect     = { pkg -> startRelay(pkg) },
                        onDisconnect  = { stopRelay() },
                        onLaunchApp   = { pkg -> launchApp(pkg) },
                        onSignIn      = { MicrosoftAuthManager.startSignIn() },
                        onSignOut     = { MicrosoftAuthManager.signOut() },
                        onCancelAuth  = { MicrosoftAuthManager.cancelSignIn() },
                        onSelectAccount = { account -> MicrosoftAuthManager.switchAccount(account) },
                        overlayPermissionGranted   = overlayPermissionGranted,
                        onRequestOverlayPermission = { requestOverlayPermission() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayPermissionGranted = Settings.canDrawOverlays(this)
    }

    private fun startRelay(targetPkg: String) {
        stopRelay()
        EntityTracker.init()

        lifecycleScope.launch(Dispatchers.IO) {
            SessionManager.start()
        }

        OverlayService.start(this)
    }

    private fun launchApp(targetPkg: String) {
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
    }

    private fun stopRelay() {
        // SessionManager.stop() artık state'i anında günceller ve ağır Netty
        // shutdown işini kendi arka plan scope'unda yapar (bkz. SessionManager.kt),
        // bu yüzden burada main thread'i bloklamaz; lifecycleScope'a sarmaya
        // gerek yok (onDestroy() içinde zaten iptal olurdu).
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

    /** Every launchable app on the device, for the "Select an Application" picker. */
    private fun getAllInstalledApps(): List<InstalledAppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            else
                @Suppress("DEPRECATION") packageManager.queryIntentActivities(launcherIntent, 0)

        return resolveInfos
            .asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .map { info ->
                InstalledAppInfo(
                    packageName = info.activityInfo.packageName,
                    label       = info.loadLabel(packageManager).toString()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}

// Gate password check is verified against the backend (/verify) instead of a
// local hardcoded string. Only the password is sent — no email/identity leaves
// the device for this check.
private const val VERIFY_URL = "https://oxclient.com.tr/verify"

/** POSTs { "password": ... } to /verify (no email field) and returns the "valid" flag. */
private fun checkGatePassword(password: String): Boolean {
    return try {
        val url = java.net.URL(VERIFY_URL)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
        }
        val body = org.json.JSONObject().apply { put("password", password) }.toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream?.bufferedReader()?.use { it.readText() } ?: return false
        org.json.JSONObject(responseText).optBoolean("valid", false)
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun PasswordGateScreen(onUnlock: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canSubmit = input.isNotBlank() && !isChecking

    fun trySubmit() {
        if (!canSubmit) return
        isChecking = true
        errorMessage = null
        scope.launch {
            val valid = withContext(Dispatchers.IO) { checkGatePassword(input) }
            isChecking = false
            if (valid) {
                onUnlock()
            } else {
                errorMessage = "Wrong password"
                input = ""
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(NexoraBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Nexora Client",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = NexoraOnBackground,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "Made by Oxygen8315 && KillJoy739860",
                fontSize = 11.sp,
                color = NexoraOnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "Enter password",
                fontSize = 13.sp,
                color = NexoraOnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; errorMessage = null },
                singleLine = true,
                isError = errorMessage != null,
                label = { Text("Password", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                visualTransformation = if (passwordVisible)
                    androidx.compose.ui.text.input.VisualTransformation.None
                else
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { trySubmit() }),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(
                            if (passwordVisible) "HIDE" else "SHOW",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NexoraOnSurfaceDim
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NexoraAccent,
                    unfocusedBorderColor = NexoraOutlineStrong,
                    cursorColor = NexoraAccentLight
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = NexoraOnBackground),
                supportingText = errorMessage?.let { msg ->
                    { Text(msg, color = NexoraError, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
            )
            Button(
                onClick = { trySubmit() },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NexoraAccent)
            ) {
                Text(
                    if (isChecking) "CHECKING..." else "UNLOCK",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    installedApps : List<Pair<String, String>>,
    allApps         : List<InstalledAppInfo> = emptyList(),
    selectedPackage : String,
    onSelectApp     : (String) -> Unit,
    relayActive   : Boolean = false,
    onConnect     : (String) -> Unit,
    onDisconnect  : () -> Unit,
    onLaunchApp   : (String) -> Unit,
    onSignIn      : () -> Unit,
    onSignOut     : () -> Unit,
    onCancelAuth  : () -> Unit,
    onSelectAccount : (SavedAccount) -> Unit,
    overlayPermissionGranted   : Boolean = true,
    onRequestOverlayPermission : () -> Unit = {}
) {
    val authState     by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
    val scope          = rememberCoroutineScope()
    val serverHost    by ServerConfig.host.collectAsState(initial = ServerConfig.DEFAULT_HOST)
    val serverPort    by ServerConfig.port.collectAsState(initial = ServerConfig.DEFAULT_PORT)
    val recentServers by ServerConfig.recents.collectAsState(initial = emptyList())
    val savedAccounts     by AccountManager.accountsFlow.collectAsStateWithLifecycle()
    val selectedGamertag  by AccountManager.selectedGamertagFlow.collectAsStateWithLifecycle()

    val accountLoggedIn = selectedGamertag != null &&
        savedAccounts.any { it.gamertag == selectedGamertag && it.isRelayReady() }

    var showSignIn      by remember { mutableStateOf(false) }
    var showServerPanel by remember { mutableStateOf(false) }
    var showAppPicker   by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { DashTab.values().size })
    val currentTab = DashTab.values()[pagerState.currentPage]

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

    Box(modifier = Modifier.fillMaxSize().background(NexoraBackground)) {
        Box(modifier = Modifier.fillMaxWidth().height(280.dp)
            .background(Brush.verticalGradient(listOf(NexoraAccentDark.copy(0.30f), Color.Transparent))))

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(28.dp))

                HorizontalPager(
                    state    = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondBoundsPageCount = 1
                ) { page ->
                    when (DashTab.values()[page]) {
                        DashTab.RELAY -> DashboardTab(
                            relayActive          = relayActive,
                            selectedPackage      = selectedPackage,
                            onOpenAppPicker      = { showAppPicker = true },
                            onToggle             = { if (relayActive) onDisconnect() else onConnect(selectedPackage) },
                            onLaunchApp          = { onLaunchApp(selectedPackage) },
                            showServerPanel      = showServerPanel,
                            onToggleServerPanel  = { showServerPanel = !showServerPanel },
                            serverHost           = serverHost,
                            serverPort           = serverPort,
                            recentServers        = recentServers,
                            onSaveServer         = { h, p -> scope.launch { ServerConfig.save(h, p) }; showServerPanel = false },
                            onResetServer        = { scope.launch { ServerConfig.reset() } },
                            onDismissServerPanel = { showServerPanel = false },
                            overlayPermissionGranted   = overlayPermissionGranted,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            accountLoggedIn            = accountLoggedIn,
                            onRequestAccountLogin       = { showSignIn = true }
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
                onSelect = { tab ->
                    scope.launch {
                        pagerState.animateScrollToPage(
                            page = tab.ordinal,
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                        )
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = showAppPicker,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            AppPickerScreen(
                apps       = allApps,
                onSelect   = { pkg -> onSelectApp(pkg); showAppPicker = false },
                onDismiss  = { showAppPicker = false }
            )
        }
    }
}


@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String? = null,
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
        color = NexoraOnBackground,
        fontFamily = FontFamily.Monospace
    )
    if (subtitle != null) {
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            fontSize = 12.sp,
            color = NexoraOnSurfaceDim,
            fontFamily = FontFamily.Monospace
        )
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun AddIconButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            .background(NexoraSurface)
            .border(1.dp, NexoraOutlineStrong, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NexoraAccentLight)
    }
}

@Composable
private fun DashboardTab(
    relayActive          : Boolean,
    selectedPackage      : String,
    onOpenAppPicker      : () -> Unit,
    onToggle             : () -> Unit,
    onLaunchApp          : () -> Unit,
    showServerPanel      : Boolean,
    onToggleServerPanel  : () -> Unit,
    serverHost           : String,
    serverPort           : Int,
    recentServers        : List<Pair<String, Int>>,
    onSaveServer         : (String, Int) -> Unit,
    onResetServer        : () -> Unit,
    onDismissServerPanel : () -> Unit,
    overlayPermissionGranted   : Boolean = true,
    onRequestOverlayPermission : () -> Unit = {},
    accountLoggedIn       : Boolean = true,
    onRequestAccountLogin : () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Nexora Client V1.0", subtitle = "Made by Oxygen8315 && KillJoy739860") {
            val context = LocalContext.current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onToggleServerPanel, modifier = Modifier.size(32.dp)) {
                    MoreVertGlyph(tint = if (showServerPanel) NexoraAccentLight else NexoraOnSurfaceDim)
                }
                IconButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/eRu3EbWdM"))
                        )
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    DiscordGlyph(tint = NexoraOnSurfaceDim)
                }
                IconButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/@NexoraClientss"))
                        )
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    YoutubeGlyph(tint = NexoraOnSurfaceDim)
                }
            }
        }

        AnimatedVisibility(
            visible = !accountLoggedIn,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
        ) {
            Column {
                DashboardWarningBanner(
                    message = "Account Login Required",
                    onClick = onRequestAccountLogin
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        AnimatedVisibility(
            visible = !overlayPermissionGranted,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
        ) {
            Column {
                DashboardWarningBanner(
                    message = "Overlay Permission Required",
                    onClick = onRequestOverlayPermission
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        SelectedApplicationCard(packageName = selectedPackage, onClick = onOpenAppPicker)
        Spacer(Modifier.height(16.dp))

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
            AnimatedVisibility(
                visible = relayActive,
                enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ConnectedBanner(onLaunchApp = onLaunchApp)
                    Spacer(Modifier.height(16.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ConnectButton(running = relayActive, onToggle = onToggle)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
        } catch (_: Exception) { null }
    }
}

private fun appLabelOf(context: Context, packageName: String): String = try {
    val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(appInfo).toString()
} catch (_: Exception) { packageName }

private fun versionNameOf(context: Context, packageName: String): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    else
        @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, 0).versionName
} catch (_: Exception) { null }

@Composable
private fun SelectedApplicationCard(packageName: String, onClick: () -> Unit) {
    val context   = LocalContext.current
    val icon      = rememberAppIcon(packageName)
    val label     = remember(packageName) { appLabelOf(context, packageName) }
    val version   = remember(packageName) { versionNameOf(context, packageName) }

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NexoraSurface)
            .border(1.dp, NexoraOutlineStrong, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Text(
            "Selected Application",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = NexoraOnSurfaceDim,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(NexoraBackground),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(32.dp))
                } else {
                    Text("📦", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = NexoraOnSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    packageName,
                    fontSize = 11.sp,
                    color = NexoraOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("Change ›", fontSize = 12.sp, color = NexoraAccentLight, fontFamily = FontFamily.Monospace)
        }
        if (version != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Current: v$version",
                fontSize = 12.sp,
                color = NexoraOnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun AppPickerScreen(
    apps      : List<InstalledAppInfo>,
    onSelect  : (String) -> Unit,
    onDismiss : () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(NexoraBackground).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Text("‹", fontSize = 24.sp, color = NexoraOnBackground)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Select an Application",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = NexoraOnBackground,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search for applications", fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NexoraAccent,
                unfocusedBorderColor = NexoraOutlineStrong,
                cursorColor = NexoraAccentLight
            ),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = NexoraOnBackground)
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.packageName }) { app ->
                AppPickerRow(app = app, onClick = { onSelect(app.packageName) })
                Spacer(Modifier.height(4.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppPickerRow(app: InstalledAppInfo, onClick: () -> Unit) {
    val icon = rememberAppIcon(app.packageName)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(NexoraSurface),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp))
            } else {
                Text("📦", fontSize = 16.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                app.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = NexoraOnBackground,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                fontSize = 12.sp,
                color = NexoraOnSurfaceDim,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DashboardWarningBanner(message: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NexoraSurface)
            .border(1.dp, NexoraOutlineStrong, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚠️", fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = NexoraOnSurface,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConnectedBanner(onLaunchApp: () -> Unit) {
    var showLaunchButton by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(5000)
        showLaunchButton = false
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(
                    color   = NexoraSurfaceRaised,
                    topLeft = Offset(-24.dp.toPx(), 0f),
                    size    = Size(size.width + 48.dp.toPx(), size.height)
                )
            }
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Connected to MITM proxy",
            fontSize = 15.sp,
            color = NexoraOnSurface,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        AnimatedVisibility(
            visible = showLaunchButton,
            enter   = fadeIn(tween(150)),
            exit    = fadeOut(tween(200))
        ) {
            Text(
                "Launch App",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = NexoraAccent,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable { onLaunchApp() }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
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
                    color = NexoraOnSurfaceDim,
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
            .background(if (selected) NexoraAccentDark else NexoraSurface)
            .border(1.dp, if (selected) NexoraAccent else NexoraOutline, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) NexoraAccent.copy(alpha = 0.25f) else NexoraSurfaceVar),
                contentAlignment = Alignment.Center
            ) {
                PersonGlyph(tint = if (selected) NexoraAccentLight else NexoraOnSurfaceDim)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    account.gamertag,
                    color = NexoraOnBackground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (account.isExpired()) "Token expired — will refresh" else "Signed in",
                    color = if (account.isExpired()) NexoraWarning else NexoraOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
        if (selected) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(NexoraSuccess))
        }
    }
}

@Composable
private fun ConfigTab() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profiles       by Config.profiles.collectAsState(initial = emptyList())
    val activeProfile  by Config.activeProfile.collectAsState(initial = null)

    var showSaveDialog by remember { mutableStateOf(false) }
    var showMapArtDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    var pendingExportName by remember { mutableStateOf<String?>(null) }
    var mapArtSize by remember { mutableStateOf(128) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val exportName = pendingExportName
        pendingExportName = null
        if (uri != null && exportName != null) {
            scope.launch {
                val json = Config.exportJson(exportName)
                if (json != null) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray())
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                    if (text != null) Config.importJson(text)
                } catch (_: Exception) {}
            }
        }
    }

    val mapArtImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    if (bitmap != null) {
                        MapArtPlan.analyze(bitmap, mapArtSize)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Configs") {
            TextButton(onClick = { showMapArtDialog = true }) {
                Text("AutoMapArt Configs", color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                Text("Import", color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            AddIconButton(onClick = { showSaveDialog = true })
        }

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No saved profiles.\nTap + to save current settings.",
                    color = NexoraOnSurfaceDim,
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
                        onDelete = { scope.launch { Config.delete(profile.name) } },
                        onExport = {
                            pendingExportName = profile.name
                            exportLauncher.launch("${profile.name}.nexoracfg.json")
                        }
                    )
                }
            }
        }
    }

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
                        focusedBorderColor = NexoraAccent,
                        unfocusedBorderColor = NexoraOutlineStrong,
                        focusedLabelColor = NexoraAccentLight,
                        cursorColor = NexoraAccentLight
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        color = NexoraOnBackground
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
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraAccent)
                ) {
                    Text("Save", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false; newProfileName = "" }) {
                    Text("Cancel", color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = NexoraSurface,
            shape = RoundedCornerShape(10.dp)
        )
    }

    if (showMapArtDialog) {
        Dialog(onDismissRequest = { showMapArtDialog = false }) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NexoraBackground)
                    .padding(16.dp)
            ) {
                AutoMapArtSection(
                    selectedSize = mapArtSize,
                    onSelectSize = { mapArtSize = it },
                    onPickImage  = { mapArtImageLauncher.launch("image/*") }
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { showMapArtDialog = false },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close", color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun AutoMapArtSection(
    selectedSize: Int,
    onSelectSize: (Int) -> Unit,
    onPickImage: () -> Unit
) {
    val gridSize     by MapArtPlan.size.collectAsState(initial = 0)
    val counts       by MapArtPlan.requiredCounts.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NexoraSurface)
            .border(1.dp, NexoraOutlineStrong, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            "Auto Map Art",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = NexoraOnSurfaceDim,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(128, 256).forEach { size ->
                val active = size == selectedSize
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) NexoraAccent else NexoraBackground)
                        .border(1.dp, if (active) NexoraAccent else NexoraOutlineStrong, RoundedCornerShape(8.dp))
                        .clickable { onSelectSize(size) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "${size}x${size}",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) Color.White else NexoraOnSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(NexoraBackground)
                .border(1.dp, NexoraOutlineStrong, RoundedCornerShape(8.dp))
                .clickable { onPickImage() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Choose Image From Gallery",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = NexoraOnSurface
            )
        }

        if (gridSize > 0 && counts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Required Blocks (${gridSize}x${gridSize})",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = NexoraOnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                counts.forEach { (blockId, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            BlockPalette.displayName(blockId),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NexoraOnSurface
                        )
                        Text(
                            "$count",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = NexoraOnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    name: String,
    active: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) NexoraAccentDark else NexoraSurface)
            .border(1.dp, if (active) NexoraAccent else NexoraOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            name,
            color = NexoraOnBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = onLoad,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = NexoraAccentLight)
            ) {
                Text("Load", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = onExport,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = NexoraOnSurfaceDim)
            ) {
                Text("Export", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = onDelete,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = NexoraError)
            ) {
                Text("Delete", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InactiveNotice() {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(NexoraSurfaceVar)
        .border(1.dp, NexoraOutline, RoundedCornerShape(8.dp))
        .padding(14.dp)
    ) {
        Text("This section is not active yet.", fontSize = 11.sp,
            color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace)
    }
}


@Composable
private fun BottomTabBar(current: DashTab, onSelect: (DashTab) -> Unit) {
    Column {
        HorizontalDivider(color = NexoraOutline)
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.Black)
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
                .background(if (selected) NexoraSurfaceVar else Color.Transparent)
                .padding(horizontal = if (selected) 18.dp else 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            icon(if (selected) NexoraAccentLight else NexoraOnSurfaceDim)
        }
        if (selected) {
            Spacer(Modifier.height(4.dp))
            Text(
                label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, color = NexoraAccentLight
            )
        }
    }
}

@Composable
private fun ConnectButton(running: Boolean, onToggle: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue   = if (running) NexoraAccent else NexoraConnectIdle,
        animationSpec = tween(300), label = "btnColor"
    )
    val contentColor = if (running) Color.White else NexoraOnBackground
    Button(
        onClick        = onToggle,
        shape          = RoundedCornerShape(50),
        colors         = ButtonDefaults.buttonColors(containerColor = bgColor),
        elevation      = ButtonDefaults.buttonElevation(0.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        modifier       = Modifier.height(52.dp)
    ) {
        RouterGlyph(tint = contentColor)
        Spacer(Modifier.width(10.dp))
        Text(
            if (running) "Disconnect" else "Connect",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, color = contentColor
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
        colors   = CardDefaults.cardColors(containerColor = NexoraSurface.copy(alpha = 0.90f)),
        border   = BorderStroke(1.dp, NexoraOutlineStrong)
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
                    color = NexoraOnBackground, fontFamily = FontFamily.Monospace)
                Text("CLOSE", fontSize = 10.sp, color = NexoraOnSurfaceDim,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onDismiss() })
            }
            HorizontalDivider(color = NexoraOutline)
            OutlinedTextField(
                value = hostInput, onValueChange = { hostInput = it },
                label = { Text("Server Address", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NexoraAccent, unfocusedBorderColor = NexoraOutlineStrong,
                    focusedLabelColor = NexoraAccentLight, cursorColor = NexoraAccentLight),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = NexoraOnBackground)
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
                    focusedBorderColor = NexoraAccent, unfocusedBorderColor = NexoraOutlineStrong,
                    focusedLabelColor = NexoraAccentLight, cursorColor = NexoraAccentLight),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = NexoraOnBackground),
                supportingText = if (portError) {
                    { Text("Valid port range is 1-65535", color = NexoraError, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                } else null
            )
            if (recentServers.isNotEmpty()) {
                Text("RECENT SERVERS", fontSize = 10.sp,
                    color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentServers.forEach { (h, p) ->
                        Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(NexoraSurfaceVar)
                            .border(1.dp, NexoraOutlineStrong, RoundedCornerShape(6.dp))
                            .clickable { hostInput = h; portInput = p.toString(); portError = false }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("$h:$p", fontSize = 9.sp,
                                color = NexoraAccentLight, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, NexoraOutlineStrong),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NexoraOnSurface)
                ) { Text("DEFAULT", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Button(
                    onClick = {
                        val p = portInput.toIntOrNull()
                        if (hostInput.isBlank() || p == null || p < 1 || p > 65535) { portError = true; return@Button }
                        onSave(hostInput.trim(), p)
                    },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NexoraAccent)
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
            colors = CardDefaults.cardColors(containerColor = NexoraSurface),
            border = BorderStroke(1.dp, NexoraOutline)
        ) {
            Column(modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("MICROSOFT ACCOUNT", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = NexoraOnBackground, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = NexoraOutline)
                when (authState) {
                    is AuthState.Success -> {
                        Text("SIGNED IN", color = NexoraSuccess, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(authState.gamertag, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = NexoraOnBackground, fontFamily = FontFamily.Monospace)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = NexoraAccent),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("SIGN IN WITH ANOTHER ACCOUNT", fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center) }
                        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = NexoraError),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("SIGN OUT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.WaitingForWebView, is AuthState.Loading -> {
                        CircularProgressIndicator(color = NexoraAccentLight, strokeWidth = 2.dp)
                        Text(
                            if (authState is AuthState.WaitingForWebView)
                                "Opening Microsoft sign-in window..."
                            else
                                "Verifying account...",
                            color = NexoraOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = onCancel) {
                            Text("CANCEL", color = NexoraError, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.Error -> {
                        Text("ERROR: ${authState.message}", color = NexoraError,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = NexoraAccent),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("RETRY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                    else -> {
                        Text("Sign in with your Xbox/Microsoft account.\nThe sign-in page will open inside the app.",
                            color = NexoraOnSurface, fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center, fontSize = 13.sp)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = NexoraAccent),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("SIGN IN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = NexoraOnSurfaceDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}


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
            drawCircle(color = NexoraBackground, radius = dotR, center = Offset(w * fx, dotY))
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

@Composable
private fun DiscordGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.22f)
            cubicTo(w * 0.10f, h * 0.30f, w * 0.06f, h * 0.55f, w * 0.10f, h * 0.78f)
            cubicTo(w * 0.22f, h * 0.86f, w * 0.34f, h * 0.90f, w * 0.40f, h * 0.90f)
            lineTo(w * 0.46f, h * 0.80f)
            cubicTo(w * 0.36f, h * 0.76f, w * 0.30f, h * 0.71f, w * 0.30f, h * 0.71f)
            cubicTo(w * 0.42f, h * 0.78f, w * 0.58f, h * 0.78f, w * 0.70f, h * 0.71f)
            cubicTo(w * 0.70f, h * 0.71f, w * 0.64f, h * 0.76f, w * 0.54f, h * 0.80f)
            lineTo(w * 0.60f, h * 0.90f)
            cubicTo(w * 0.66f, h * 0.90f, w * 0.78f, h * 0.86f, w * 0.90f, h * 0.78f)
            cubicTo(w * 0.94f, h * 0.55f, w * 0.90f, h * 0.30f, w * 0.72f, h * 0.22f)
            cubicTo(w * 0.72f, h * 0.22f, w * 0.62f, h * 0.16f, w * 0.50f, h * 0.16f)
            cubicTo(w * 0.38f, h * 0.16f, w * 0.28f, h * 0.22f, w * 0.28f, h * 0.22f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = h * 0.07f, cap = StrokeCap.Round))
        drawCircle(color = tint, radius = h * 0.055f, center = Offset(w * 0.37f, h * 0.52f))
        drawCircle(color = tint, radius = h * 0.055f, center = Offset(w * 0.63f, h * 0.52f))
    }
}

@Composable
private fun YoutubeGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.20f),
            size = Size(w * 0.84f, h * 0.60f),
            cornerRadius = CornerRadius(w * 0.18f),
            style = Stroke(width = h * 0.09f)
        )
        val play = Path().apply {
            moveTo(w * 0.42f, h * 0.36f)
            lineTo(w * 0.42f, h * 0.64f)
            lineTo(w * 0.66f, h * 0.50f)
            close()
        }
        drawPath(path = play, color = tint)
    }
}
