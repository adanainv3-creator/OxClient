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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.config.ServerConfig
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEventBus
import com.oxclient.module.ModuleManager
import com.oxclient.session.SessionManager
import com.oxclient.ui.overlay.OverlayService
import com.oxclient.ui.theme.*
import kotlinx.coroutines.launch

val SUPPORTED_PACKAGES = listOf(
    "com.mojang.minecraftpe"      to "Minecraft",
    "com.netease.mc"              to "Minecraft (Çin)",
    "com.mojang.minecrafttrialpe" to "Minecraft Trial",
)

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

        MicrosoftAuthManager.init(this)

        setContent {
            OxClientTheme {
                val authState   by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
                val relayActive by SessionManager.isActive.collectAsStateWithLifecycle()

                LaunchedEffect(authState) {
                    if (authState is AuthState.WaitingForUser) {
                        // FIX: AuthState.WaitingForUser'da 'url' field'ı yok.
                        // Doğru field adı 'verificationUri'dir.
                        val uri = (authState as AuthState.WaitingForUser).verificationUri
                        if (uri.isNotBlank()) {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }

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
        SessionManager.start()
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
    var showAppPicker  by remember { mutableStateOf(false) }
    var showServerEdit by remember { mutableStateOf(false) }
    var selectedApp    by remember { mutableStateOf(installedApps.firstOrNull() ?: SUPPORTED_PACKAGES.first()) }

    if (showSignIn) {
        AuthDialog(
            authState = authState,
            onDismiss = { showSignIn = false },
            onSignIn  = onSignIn,
            onSignOut = { onSignOut(); showSignIn = false },
            onCancel  = { onCancelAuth(); showSignIn = false }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps        = installedApps,
            selectedPkg = selectedApp.first,
            onSelect    = { pkg, name -> selectedApp = pkg to name; showAppPicker = false },
            onDismiss   = { showAppPicker = false }
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
        Box(modifier = Modifier.fillMaxWidth().height(300.dp)
            .background(Brush.verticalGradient(listOf(OxPurpleDark.copy(0.4f), Color.Transparent))))

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            TopBar(authState = authState, onAvatarClick = { showSignIn = true })
            Spacer(Modifier.height(36.dp))
            LogoSection()
            Spacer(Modifier.height(28.dp))
            ServerCard(host = serverHost, port = serverPort, onClick = { showServerEdit = true })
            Spacer(Modifier.height(10.dp))
            AppSelectorCard(selectedName = selectedApp.second, onClick = { showAppPicker = true })
            Spacer(Modifier.height(10.dp))
            StatusCard(running = relayActive, statusMessage = statusMessage)
            Spacer(Modifier.height(10.dp))
            if (relayActive) LiveStatsCard()
            Spacer(Modifier.weight(1f))
            ConnectButton(running = relayActive, onToggle = {
                if (relayActive) onDisconnect() else onConnect(selectedApp.first)
            })
            Spacer(Modifier.height(12.dp))
            Text(
                "Overlay menüsü oyun içinde görünecek",
                fontSize = 11.sp, color = OxOnSurface.copy(0.4f),
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
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
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = OxSurface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LiveStatChip("❤", "${"%.0f".format(selfHp)}",
                if (selfHp <= 6f) Color(0xFFFF4444) else Color(0xFF1AFF6E))
            LiveStatChip("👾", "$entityCount",  OxOnSurface.copy(0.8f))
            LiveStatChip("👤", "$playerCount",  Color(0xFF4FC3F7))
            LiveStatChip("⚡", "$activeCount",  OxPurpleLight)
        }
    }
}

@Composable
private fun LiveStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 14.sp)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold,
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
            Text("OxClient", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                color = OxOnBackground, fontFamily = FontFamily.Monospace)
            Text("Bedrock Relay Client", fontSize = 10.sp,
                color = OxOnSurface.copy(0.4f), fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoggedIn) {
                Text((authState as AuthState.Success).gamertag, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = OxPurpleLight,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.radialGradient(listOf(OxPurple, OxPurpleDark))),
            contentAlignment = Alignment.Center
        ) {
            Text("0x", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                color = Color.White, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(12.dp))
        Text("Minecraft Bedrock Client", fontSize = 13.sp,
            color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ServerCard(host: String, port: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OxSurface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Hedef Sunucu", fontSize = 10.sp,
                    color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                Text("$host:$port", fontSize = 13.sp, color = OxOnBackground,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(OxPurple))
                Text("✎", fontSize = 14.sp, color = OxPurple, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun AppSelectorCard(selectedName: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OxSurface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Hedef Uygulama", fontSize = 10.sp,
                    color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                Text(selectedName, fontSize = 13.sp, color = OxOnBackground,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
            Text("▾", fontSize = 18.sp, color = OxPurple, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, statusMessage: String) {
    val statusColor by animateColorAsState(
        targetValue  = if (running) Color(0xFF1AFF6E) else OxOnSurface.copy(0.3f),
        animationSpec = tween(500), label = "status"
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue  = 1f,
        targetValue   = if (running) 1.15f else 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label         = "pulseAnim"
    )
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = OxSurface)
    ) {
        Row(modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(12.dp).scale(pulse).clip(CircleShape).background(statusColor))
            Column {
                Text(if (running) "Relay Aktif" else "Relay Kapalı", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) Color(0xFF1AFF6E) else OxOnBackground,
                    fontFamily = FontFamily.Monospace)
                Text(statusMessage.ifBlank { if (running) "Trafik yönlendiriliyor" else "Connect'e basarak başlat" },
                    fontSize = 11.sp, color = OxOnSurface.copy(0.5f),
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConnectButton(running: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue   = if (running) 1f else 0.97f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy), label = "btn"
    )
    val bgColor by animateColorAsState(
        targetValue   = if (running) OxError else OxPurple,
        animationSpec = tween(400), label = "btnColor"
    )
    Button(
        onClick   = onToggle,
        modifier  = Modifier.fillMaxWidth().height(56.dp).scale(scale),
        shape     = RoundedCornerShape(14.dp),
        colors    = ButtonDefaults.buttonColors(containerColor = bgColor),
        elevation = ButtonDefaults.buttonElevation(8.dp)
    ) {
        Text(if (running) "⏹  Disconnect" else "▶  Connect",
            fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
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
        Card(shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface)
        ) {
            Column(modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sunucu Ayarları", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = OxOutline)
                OutlinedTextField(
                    value = hostInput, onValueChange = { hostInput = it },
                    label = { Text("Sunucu Adresi", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OxPurple, unfocusedBorderColor = OxOutline,
                        focusedLabelColor = OxPurple, cursorColor = OxPurple),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = OxOnBackground)
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it; portError = it.toIntOrNull()?.let { p -> p < 1 || p > 65535 } ?: true },
                    label = { Text("Port", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    singleLine = true, isError = portError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OxPurple, unfocusedBorderColor = OxOutline,
                        focusedLabelColor = OxPurple, cursorColor = OxPurple),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = OxOnBackground),
                    supportingText = if (portError) {
                        { Text("1–65535 arası geçerli port", color = OxError, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                    } else null
                )
                if (recentServers.isNotEmpty()) {
                    Text("Son Bağlantılar", fontSize = 11.sp,
                        color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recentServers.forEach { (h, p) ->
                            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(OxSurfaceVar)
                                .border(1.dp, OxOutline.copy(0.5f), RoundedCornerShape(8.dp))
                                .clickable { hostInput = h; portInput = p.toString(); portError = false }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("$h:$p", fontSize = 9.sp,
                                    color = OxPurpleLight, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OxOnSurface)
                    ) { Text("Varsayılan", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    Button(
                        onClick = {
                            val p = portInput.toIntOrNull()
                            if (hostInput.isBlank() || p == null || p < 1 || p > 65535) { portError = true; return@Button }
                            onSave(hostInput.trim(), p)
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OxPurple)
                    ) { Text("Kaydet", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("İptal", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps        : List<Pair<String, String>>,
    selectedPkg : String,
    onSelect    : (String, String) -> Unit,
    onDismiss   : () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Uygulama Seç", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = OxOnBackground, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp)); HorizontalDivider(color = OxOutline); Spacer(Modifier.height(8.dp))
                val listToShow = apps.ifEmpty { SUPPORTED_PACKAGES }
                if (apps.isEmpty()) {
                    Text("Cihazda Minecraft bulunamadı.\nYine de bir hedef seçebilirsiniz:",
                        color = OxOnSurface.copy(0.6f), fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listToShow) { (pkg, name) ->
                        val isSelected = pkg == selectedPkg
                        Row(modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) OxPurple.copy(0.2f) else Color.Transparent)
                            .border(if (isSelected) 1.dp else 0.5.dp,
                                if (isSelected) OxPurple else OxOutline.copy(0.3f), RoundedCornerShape(10.dp))
                            .clickable { onSelect(pkg, name) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) OxPurpleLight else OxOnBackground,
                                    fontFamily = FontFamily.Monospace)
                                Text(pkg, fontSize = 10.sp,
                                    color = OxOnSurface.copy(0.4f), fontFamily = FontFamily.Monospace)
                            }
                            if (isSelected) Text("✓", color = OxPurple, fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
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
    val canDismiss = authState !is AuthState.Loading && authState !is AuthState.WaitingForUser
    Dialog(onDismissRequest = { if (canDismiss) onDismiss() }) {
        Card(shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = OxSurface)) {
            Column(modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("Çıkış Yap", fontFamily = FontFamily.Monospace) }
                        TextButton(onClick = onDismiss) {
                            Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.Loading, is AuthState.WaitingForUser -> {
                        CircularProgressIndicator(color = OxPurple)
                        Text(if (authState is AuthState.WaitingForUser) "Tarayıcıda giriş yapılıyor…"
                             else "Bağlanıyor…",
                            color = OxOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        TextButton(onClick = onCancel) {
                            Text("İptal", color = OxError, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is AuthState.Error -> {
                        Text("Hata: ${authState.message}", color = OxError,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxPurple),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("Tekrar Dene", fontFamily = FontFamily.Monospace) }
                        TextButton(onClick = onDismiss) {
                            Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                        }
                    }
                    else -> {
                        Text("Xbox/Microsoft hesabınla giriş yap.\nKod tarayıcıda gösterilecek.",
                            color = OxOnSurface.copy(0.7f), fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center, fontSize = 13.sp)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxPurple),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("▶  Giriş Yap", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onDismiss) {
                            Text("Kapat", color = OxOnSurface.copy(0.5f), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
