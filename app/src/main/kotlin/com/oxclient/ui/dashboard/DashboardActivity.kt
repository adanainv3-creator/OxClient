package com.oxclient.ui.dashboard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oxclient.auth.AuthState
import com.oxclient.auth.DeviceCodeLoginActivity
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.module.ModuleManager
import com.oxclient.relay.service.OxRelayService
import com.oxclient.session.ServerConfig
import com.oxclient.session.SessionManager
import com.oxclient.ui.overlay.OverlayService
import com.oxclient.ui.theme.*

val SUPPORTED_PACKAGES = listOf(
    "com.mojang.minecraftpe"      to "Minecraft",
    "com.netease.mc"              to "Minecraft (Çin)",
    "com.mojang.minecrafttrialpe" to "Minecraft Trial",
)

class DashboardActivity : ComponentActivity() {

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* overlay izni sonucu */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        MicrosoftAuthManager.init(this)

        setContent {
            OxClientTheme {
                val authState by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()

                LaunchedEffect(authState) {
                    if (authState is AuthState.WaitingForUser)
                        startActivity(Intent(this@DashboardActivity, DeviceCodeLoginActivity::class.java))
                }

                DashboardScreen(
                    installedApps = getInstalledGames(),
                    onLaunch      = { pkg -> launchGame(pkg) },
                    onStop        = { stopSession() },
                    onSignIn      = { MicrosoftAuthManager.startSignIn() },
                    onSignOut     = { MicrosoftAuthManager.signOut() },
                    onCancelAuth  = { MicrosoftAuthManager.cancelSignIn() }
                )
            }
        }
    }

    private fun launchGame(targetPkg: String) {
        Log.d("Dashboard", "Oyun başlatılıyor: $targetPkg")

        // Önce relay servisini başlat
        OxRelayService.start(this)

        // Sonra overlay'i başlat
        OverlayService.start(this)

        // Kısa gecikme ile Minecraft'ı aç
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

    private fun stopSession() {
        Log.d("Dashboard", "Oturum durduruluyor")
        OxRelayService.stop(this)
        OverlayService.stop(this)
        ModuleManager.disableAll()
        SessionManager.onSessionStop()
    }

    private fun getInstalledGames(): List<Pair<String, String>> =
        SUPPORTED_PACKAGES.filter { (pkg, _) ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                else @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
        }
}

// ── DashboardScreen ───────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    installedApps: List<Pair<String, String>>,
    onLaunch     : (String) -> Unit,
    onStop       : () -> Unit,
    onSignIn     : () -> Unit,
    onSignOut    : () -> Unit,
    onCancelAuth : () -> Unit
) {
    val authState     by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
    val serverHost    by ServerConfig.host.collectAsStateWithLifecycle()
    val serverPort    by ServerConfig.port.collectAsStateWithLifecycle()
    val sessionActive by SessionManager.isActiveFlow.collectAsStateWithLifecycle()

    var showSignIn     by remember { mutableStateOf(false) }
    var showAppPicker  by remember { mutableStateOf(false) }
    var showServerEdit by remember { mutableStateOf(false) }
    var selectedApp    by remember { mutableStateOf(installedApps.firstOrNull() ?: SUPPORTED_PACKAGES.first()) }

    if (showSignIn) {
        AuthDialog(
            authState = authState,
            onDismiss = { showSignIn = false },
            onSignIn  = onSignIn,
            onSignOut = onSignOut,
            onCancel  = onCancelAuth
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps       = installedApps.ifEmpty { SUPPORTED_PACKAGES },
            selected   = selectedApp,
            onSelect   = { pkg, name -> selectedApp = pkg to name; showAppPicker = false },
            onDismiss  = { showAppPicker = false }
        )
    }

    if (showServerEdit) {
        ServerEditDialog(
            host      = serverHost,
            port      = serverPort,
            onSave    = { h, p -> ServerConfig.save(h, p); showServerEdit = false },
            onDismiss = { showServerEdit = false }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(OxBackground, Color(0xFF12121A))))
    ) {
        Column(
            modifier  = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Bar
            TopBar(authState = authState, onAvatarClick = { showSignIn = true })

            Spacer(Modifier.height(4.dp))

            // Logo
            LogoSection()

            Spacer(Modifier.height(4.dp))

            // Durum kartı
            StatusCard(active = sessionActive)

            // Sunucu kartı
            ServerCard(
                host    = serverHost,
                port    = serverPort,
                onClick = { showServerEdit = true }
            )

            // Hedef uygulama seçimi
            AppSelectorCard(
                selected = selectedApp,
                onClick  = { showAppPicker = true }
            )

            Spacer(Modifier.weight(1f))

            // Bağlan / Kes butonu
            ConnectButton(
                active    = sessionActive,
                onToggle  = {
                    if (sessionActive) onStop()
                    else {
                        if (authState !is AuthState.Success) { showSignIn = true; return@ConnectButton }
                        onLaunch(selectedApp.first)
                    }
                }
            )
        }
    }
}

// ── Alt Composable'lar ────────────────────────────────────────────────────────

@Composable
private fun TopBar(authState: AuthState, onAvatarClick: () -> Unit) {
    val isLoggedIn = authState is AuthState.Success
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text("OxClient", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
            color = OxOnBackground, fontFamily = FontFamily.Monospace)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoggedIn)
                Text((authState as AuthState.Success).gamertag, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = OxPurpleLight,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(if (isLoggedIn) OxPurple.copy(0.3f) else OxSurface)
                    .border(1.dp, if (isLoggedIn) OxPurple else OxOutline, CircleShape)
                    .clickable { onAvatarClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLoggedIn) (authState as AuthState.Success).gamertag.firstOrNull()?.uppercase() ?: "?" else "?",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (isLoggedIn) OxPurpleLight else OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun LogoSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.radialGradient(listOf(OxPurple, OxPurpleDark))),
            contentAlignment = Alignment.Center
        ) { Text("0x", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
            color = Color.White, fontFamily = FontFamily.Monospace) }
        Spacer(Modifier.height(8.dp))
        Text("MITM Relay Client", fontSize = 12.sp, color = OxOnSurface.copy(0.5f),
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatusCard(active: Boolean) {
    val statusColor by animateColorAsState(
        if (active) Color(0xFF1AFF6E) else OxOnSurface.copy(0.3f), tween(500), label = "sc")
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(
        1f, if (active) 1.18f else 1f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pa")

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OxSurface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(12.dp).scale(pulse).clip(CircleShape).background(statusColor))
            Column {
                Text(if (active) "Relay Aktif" else "Bağlı Değil",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (active) Color(0xFF1AFF6E) else OxOnSurface,
                    fontFamily = FontFamily.Monospace)
                Text(if (active) "MITM proxy çalışıyor — modüller aktif"
                     else "Connect'e basarak başlat",
                    fontSize = 11.sp, color = OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ServerCard(host: String, port: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OxSurface)) {
        Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("Hedef Sunucu", fontSize = 11.sp, color = OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace)
                Text("$host:$port", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = OxPurpleLight, fontFamily = FontFamily.Monospace)
            }
            Text("✎", fontSize = 18.sp, color = OxOutline, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun AppSelectorCard(selected: Pair<String, String>, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OxSurface)) {
        Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("Hedef Uygulama", fontSize = 11.sp, color = OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace)
                Text(selected.second, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                Text(selected.first, fontSize = 10.sp, color = OxOnSurface.copy(0.35f),
                    fontFamily = FontFamily.Monospace)
            }
            Text("▾", fontSize = 18.sp, color = OxOutline, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ConnectButton(active: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(if (active) 1f else 0.97f,
        spring(Spring.DampingRatioMediumBouncy), label = "bs")
    val bgColor by animateColorAsState(if (active) OxError else OxPurple, tween(400), label = "bc")

    Button(onClick = onToggle,
        modifier = Modifier.fillMaxWidth().height(56.dp).scale(scale),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bgColor),
        elevation = ButtonDefaults.buttonElevation(8.dp)) {
        Text(if (active) "⏹  Disconnect" else "▶  Connect",
            fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace)
    }
}

// ── Dialog'lar ────────────────────────────────────────────────────────────────

@Composable
private fun AuthDialog(
    authState: AuthState, onDismiss: () -> Unit, onSignIn: () -> Unit,
    onSignOut: () -> Unit, onCancel: () -> Unit
) {
    val dismissOk = authState !is AuthState.Loading && authState !is AuthState.WaitingForUser
    Dialog(onDismissRequest = { if (dismissOk) onDismiss() }) {
        Card(shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Microsoft Hesabı", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = OxOutline)
                when (authState) {
                    is AuthState.Success -> {
                        Text("✓ Giriş yapıldı", color = Color(0xFF1AFF6E), fontFamily = FontFamily.Monospace)
                        Text(authState.gamertag, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = OxOnBackground, fontFamily = FontFamily.Monospace)
                        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxError),
                            shape = RoundedCornerShape(10.dp)) {
                            Text("Çıkış Yap", fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.Loading -> {
                        CircularProgressIndicator(color = OxPurple)
                        Text("Bağlanıyor…", color = OxOnSurface, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = onCancel) {
                            Text("İptal", color = OxError, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.WaitingForUser -> {
                        CircularProgressIndicator(color = OxPurple)
                        Text("Giriş sayfası açıldı…", color = OxOnSurface, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = onCancel) {
                            Text("İptal", color = OxError, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.Error -> {
                        Text("Hata: ${authState.message}", color = OxError,
                            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxPurple),
                            shape = RoundedCornerShape(10.dp)) {
                            Text("Tekrar Dene", fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                        }
                    }
                    else -> {
                        Text("Xbox/Microsoft hesabınla giriş yap.",
                            color = OxOnSurface.copy(0.7f), fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxPurple),
                            shape = RoundedCornerShape(10.dp)) {
                            Text("▶  Giriş Yap", fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold)
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

@Composable
private fun AppPickerDialog(
    apps    : List<Pair<String, String>>,
    selected: Pair<String, String>,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Uygulama Seç", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = OxOutline)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(apps) { (pkg, name) ->
                        val isSel = pkg == selected.first
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) OxPurple.copy(0.2f) else OxSurfaceVar)
                                .border(if (isSel) 1.dp else 0.5.dp,
                                    if (isSel) OxPurple else OxOutline.copy(0.3f),
                                    RoundedCornerShape(10.dp))
                                .clickable { onSelect(pkg, name) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (isSel) OxPurpleLight else OxOnBackground,
                                    fontFamily = FontFamily.Monospace)
                                Text(pkg, fontSize = 10.sp, color = OxOnSurface.copy(0.4f),
                                    fontFamily = FontFamily.Monospace)
                            }
                            if (isSel) Text("✓", color = OxPurple, fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun ServerEditDialog(
    host: String, port: Int,
    onSave: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var h by remember { mutableStateOf(host) }
    var p by remember { mutableStateOf(port.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sunucu Düzenle", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = OxOutline)
                OutlinedTextField(value = h, onValueChange = { h = it },
                    label = { Text("Host", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OxPurple, unfocusedBorderColor = OxOutline,
                        focusedTextColor = OxOnBackground, unfocusedTextColor = OxOnBackground))
                OutlinedTextField(value = p, onValueChange = { p = it.filter { c -> c.isDigit() } },
                    label = { Text("Port", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OxPurple, unfocusedBorderColor = OxOutline,
                        focusedTextColor = OxOnBackground, unfocusedTextColor = OxOnBackground))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("İptal", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                    }
                    Button(onClick = { onSave(h.trim(), p.toIntOrNull() ?: 19132) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = OxPurple),
                        shape = RoundedCornerShape(10.dp)) {
                        Text("Kaydet", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
