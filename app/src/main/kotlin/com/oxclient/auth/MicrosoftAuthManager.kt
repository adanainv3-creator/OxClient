package com.oxclient.ui.dashboard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oxclient.BuildConfig
import com.oxclient.auth.AuthState
import com.oxclient.auth.DeviceCodeLoginActivity
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.core.vpn.OxVpnService
import com.oxclient.module.ModuleManager
import com.oxclient.ui.overlay.OverlayService
import com.oxclient.ui.theme.*

/** Desteklenen paketler — öncelik sırasına göre */
val SUPPORTED_PACKAGES = listOf(
    "com.mojang.minecraftpe"      to "Minecraft",
    "com.netease.mc"              to "Minecraft (Çin)",
    "com.mojang.minecrafttrialpe" to "Minecraft Trial",
)

class DashboardActivity : ComponentActivity() {

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) launchProxy(pendingPackage) }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* overlay izni sonucu */ }

    private var pendingPackage: String = SUPPORTED_PACKAGES.first().first

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        // ✅ auth klasörü
        MicrosoftAuthManager.init(this)
        ModuleManager.init(this)

        setContent {
            OxClientTheme {
                val authState by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()

                // ✅ WaitingForUser → DeviceCodeLoginActivity aç (WebView burada)
                LaunchedEffect(authState) {
                    if (authState is AuthState.WaitingForUser) {
                        startActivity(
                            Intent(this@DashboardActivity, DeviceCodeLoginActivity::class.java)
                        )
                    }
                }

                DashboardScreen(
                    installedApps = getInstalledGames(),
                    onConnect     = { pkg ->
                        pendingPackage = pkg
                        requestVpn()
                    },
                    onDisconnect  = { stopProxy() },
                    onSignIn      = { MicrosoftAuthManager.startSignIn() },
                    onSignOut     = { MicrosoftAuthManager.signOut() },
                    onCancelAuth  = { MicrosoftAuthManager.cancelSignIn() }
                )
            }
        }
    }

    private fun requestVpn() {
        val pi = VpnService.prepare(this)
        if (pi != null) vpnLauncher.launch(pi) else launchProxy(pendingPackage)
    }

    private fun launchProxy(targetPkg: String) {
        startService(Intent(this, OxVpnService::class.java).apply {
            action = OxVpnService.ACTION_START
        })
        OverlayService.start(this)
        val intent = packageManager.getLaunchIntentForPackage(targetPkg)
        if (intent != null) {
            startActivity(intent)
        } else {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$targetPkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun stopProxy() {
        startService(Intent(this, OxVpnService::class.java).apply {
            action = OxVpnService.ACTION_STOP
        })
        OverlayService.stop(this)
        ModuleManager.disableAll()
    }

    private fun getInstalledGames(): List<Pair<String, String>> {
        return SUPPORTED_PACKAGES.filter { (pkg, _) ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(pkg, 0)
                }
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    installedApps : List<Pair<String, String>>,
    onConnect     : (String) -> Unit,
    onDisconnect  : () -> Unit,
    onSignIn      : () -> Unit,
    onSignOut     : () -> Unit,
    onCancelAuth  : () -> Unit
) {
    val authState     by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
    var proxyRunning  by remember { mutableStateOf(false) }
    var showSignIn    by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var selectedApp   by remember {
        mutableStateOf(installedApps.firstOrNull() ?: SUPPORTED_PACKAGES.first())
    }

    if (showSignIn) {
        AuthDialog(
            authState = authState,
            onDismiss = { showSignIn = false },
            onSignIn  = onSignIn,
            onSignOut = {
                onSignOut()
                showSignIn = false
            },
            onCancel  = {
                onCancelAuth()
                showSignIn = false
            }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps        = installedApps,
            selectedPkg = selectedApp.first,
            onSelect    = { pkg, name ->
                selectedApp   = pkg to name
                showAppPicker = false
            },
            onDismiss   = { showAppPicker = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OxBackground)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(OxPurpleDark.copy(alpha = 0.4f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            TopBar(authState = authState, onAvatarClick = { showSignIn = true })
            Spacer(Modifier.height(48.dp))
            LogoSection()
            Spacer(Modifier.height(32.dp))
            ServerCard()
            Spacer(Modifier.height(12.dp))
            AppSelectorCard(
                selectedName = selectedApp.second,
                onClick      = { showAppPicker = true }
            )
            Spacer(Modifier.height(12.dp))
            StatusCard(running = proxyRunning)
            Spacer(Modifier.weight(1f))

            ConnectButton(
                running  = proxyRunning,
                onToggle = {
                    if (proxyRunning) {
                        onDisconnect()
                        proxyRunning = false
                    } else {
                        onConnect(selectedApp.first)
                        proxyRunning = true
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text       = "Overlay menüsü oyun içinde görünecek",
                fontSize   = 11.sp,
                color      = OxOnSurface.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace,
                textAlign  = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── App Selector Card ─────────────────────────────────────────────────────────

@Composable
private fun AppSelectorCard(selectedName: String, onClick: () -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth().clickable { onClick() },
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = OxSurface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Hedef Uygulama",
                    fontSize   = 10.sp,
                    color      = OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    selectedName,
                    fontSize   = 13.sp,
                    color      = OxOnBackground,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text("▾", fontSize = 18.sp, color = OxPurple, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── App Picker Dialog ─────────────────────────────────────────────────────────

@Composable
private fun AppPickerDialog(
    apps        : List<Pair<String, String>>,
    selectedPkg : String,
    onSelect    : (String, String) -> Unit,
    onDismiss   : () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Uygulama Seç",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color      = OxOnBackground,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = OxOutline)
                Spacer(Modifier.height(8.dp))

                if (apps.isEmpty()) {
                    Text(
                        "Cihazda Minecraft bulunamadı.\nYine de bir hedef seçebilirsiniz:",
                        color      = OxOnSurface.copy(0.6f),
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 12.sp,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SUPPORTED_PACKAGES) { (pkg, name) ->
                            AppPickerRow(
                                pkg         = pkg,
                                name        = name,
                                selectedPkg = selectedPkg,
                                onSelect    = onSelect
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(apps) { (pkg, name) ->
                            AppPickerRow(
                                pkg         = pkg,
                                name        = name,
                                selectedPkg = selectedPkg,
                                onSelect    = onSelect
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick  = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun AppPickerRow(
    pkg         : String,
    name        : String,
    selectedPkg : String,
    onSelect    : (String, String) -> Unit
) {
    val isSelected = pkg == selectedPkg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) OxPurple.copy(0.2f) else Color.Transparent)
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = if (isSelected) OxPurple else OxOutline.copy(0.3f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onSelect(pkg, name) }
            .padding(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                name,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color      = if (isSelected) OxPurpleLight else OxOnBackground,
                fontFamily = FontFamily.Monospace
            )
            Text(
                pkg,
                fontSize   = 10.sp,
                color      = OxOnSurface.copy(0.4f),
                fontFamily = FontFamily.Monospace
            )
        }
        if (isSelected) {
            Text("✓", color = OxPurple, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Auth Dialog ───────────────────────────────────────────────────────────────

@Composable
private fun AuthDialog(
    authState : AuthState,
    onDismiss : () -> Unit,
    onSignIn  : () -> Unit,
    onSignOut : () -> Unit,
    onCancel  : () -> Unit
) {
    val dismissEnabled = authState !is AuthState.Loading &&
                         authState !is AuthState.WaitingForUser

    Dialog(onDismissRequest = { if (dismissEnabled) onDismiss() }) {
        Card(
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface)
        ) {
            Column(
                modifier            = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Microsoft Hesabı",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color      = OxOnBackground,
                    fontFamily = FontFamily.Monospace
                )
                HorizontalDivider(color = OxOutline)

                when (authState) {
                    is AuthState.Success -> {
                        Text("✓ Giriş yapıldı", color = Color(0xFF1AFF6E), fontFamily = FontFamily.Monospace)
                        Text(
                            authState.gamertag,
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color      = OxOnBackground,
                            fontFamily = FontFamily.Monospace
                        )
                        Button(
                            onClick  = onSignOut,
                            colors   = ButtonDefaults.buttonColors(containerColor = OxError),
                            shape    = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Çıkış Yap", fontFamily = FontFamily.Monospace) }
                        TextButton(onClick = onDismiss) {
                            Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                        }
                    }

                    is AuthState.Loading -> {
                        CircularProgressIndicator(color = OxPurple)
                        Text(
                            "Microsoft bağlantısı kuruluyor…",
                            color      = OxOnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 13.sp
                        )
                        Text(
                            "Lütfen bekleyin, giriş sayfası açılacak",
                            color      = OxOnSurface.copy(0.5f),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                            textAlign  = TextAlign.Center
                        )
                        TextButton(onClick = onCancel) {
                            Text("İptal", color = OxError, fontFamily = FontFamily.Monospace)
                        }
                    }

                    is AuthState.WaitingForUser -> {
                        CircularProgressIndicator(color = OxPurple)
                        Text(
                            "Giriş sayfası açıldı…",
                            color      = OxOnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 13.sp
                        )
                        Text(
                            "Kodu girdikten sonra buraya dönün",
                            color      = OxOnSurface.copy(0.5f),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                            textAlign  = TextAlign.Center
                        )
                        TextButton(onClick = onCancel) {
                            Text("İptal", color = OxError, fontFamily = FontFamily.Monospace)
                        }
                    }

                    is AuthState.Error -> {
                        Text(
                            "Hata: ${authState.message}",
                            color      = OxError,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 12.sp,
                            textAlign  = TextAlign.Center
                        )
                        Button(
                            onClick  = onSignIn,
                            colors   = ButtonDefaults.buttonColors(containerColor = OxPurple),
                            shape    = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Tekrar Dene", fontFamily = FontFamily.Monospace) }
                        TextButton(onClick = onDismiss) {
                            Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                        }
                    }

                    else -> {
                        Text(
                            "Xbox/Microsoft hesabınla giriş yap.\nKod ekranda gösterilecek.",
                            color      = OxOnSurface.copy(0.7f),
                            fontFamily = FontFamily.Monospace,
                            textAlign  = TextAlign.Center,
                            fontSize   = 13.sp
                        )
                        Button(
                            onClick  = onSignIn,
                            colors   = ButtonDefaults.buttonColors(containerColor = OxPurple),
                            shape    = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("▶  Giriş Yap", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

// ── TopBar ────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    authState     : AuthState,
    onAvatarClick : () -> Unit
) {
    val isLoggedIn = authState is AuthState.Success

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        // Sol: uygulama adı
        Text(
            "OxClient",
            fontSize   = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color      = OxOnBackground,
            fontFamily = FontFamily.Monospace
        )

        // Sağ: gamertag + avatar dairesi
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Gamertag — sadece giriş yapıldığında göster
            if (isLoggedIn) {
                Text(
                    text     = (authState as AuthState.Success).gamertag,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color    = OxPurpleLight,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Avatar dairesi
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isLoggedIn) OxPurple.copy(0.3f) else OxSurface)
                    .border(1.dp, if (isLoggedIn) OxPurple else OxOutline, CircleShape)
                    .clickable { onAvatarClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLoggedIn) (authState as AuthState.Success)
                        .gamertag.firstOrNull()?.uppercase() ?: "?" else "?",
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color      = if (isLoggedIn) OxPurpleLight else OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── LogoSection ───────────────────────────────────────────────────────────────

@Composable
private fun LogoSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.radialGradient(listOf(OxPurple, OxPurpleDark))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "0x",
                fontSize   = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = Color.White,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "2b2t.pe Client",
            fontSize   = 13.sp,
            color      = OxOnSurface.copy(0.5f),
            fontFamily = FontFamily.Monospace
        )
    }
}

// ── ServerCard ────────────────────────────────────────────────────────────────

@Composable
private fun ServerCard() {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = OxSurface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Hedef Sunucu",
                    fontSize   = 10.sp,
                    color      = OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "${BuildConfig.SERVER_HOST}:${BuildConfig.SERVER_PORT}",
                    fontSize   = 13.sp,
                    color      = OxOnBackground,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(OxPurple)
            )
        }
    }
}

// ── StatusCard ────────────────────────────────────────────────────────────────

@Composable
private fun StatusCard(running: Boolean) {
    val statusColor by animateColorAsState(
        targetValue   = if (running) Color(0xFF1AFF6E) else OxOnSurface.copy(0.3f),
        animationSpec = tween(500),
        label         = "status"
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue  = 1f,
        targetValue   = if (running) 1.15f else 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label         = "pulseAnim"
    )

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = OxSurface)
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Column {
                Text(
                    text       = if (running) "VPN Aktif" else "VPN Kapalı",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (running) Color(0xFF1AFF6E) else OxOnSurface,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text       = if (running) "Trafik yönlendiriliyor → proxy" else "Connect'e basarak başlat",
                    fontSize   = 11.sp,
                    color      = OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── ConnectButton ─────────────────────────────────────────────────────────────

@Composable
private fun ConnectButton(running: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue   = if (running) 1f else 0.97f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label         = "btn"
    )
    val bgColor by animateColorAsState(
        targetValue   = if (running) OxError else OxPurple,
        animationSpec = tween(400),
        label         = "btnColor"
    )

    Button(
        onClick   = onToggle,
        modifier  = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(scale),
        shape     = RoundedCornerShape(14.dp),
        colors    = ButtonDefaults.buttonColors(containerColor = bgColor),
        elevation = ButtonDefaults.buttonElevation(8.dp)
    ) {
        Text(
            text       = if (running) "⏹  Disconnect" else "▶  Connect",
            fontSize   = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
