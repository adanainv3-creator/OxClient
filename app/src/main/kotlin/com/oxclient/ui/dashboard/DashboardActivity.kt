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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import com.oxclient.auth.AuthState
import com.oxclient.auth.DeviceCodeLoginActivity
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.proxy.BedrockRelayService
import com.oxclient.session.ServerConfig
import com.oxclient.ui.overlay.OverlayService
import com.oxclient.ui.theme.*
import kotlinx.coroutines.*

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        setContent {
            OxTheme {
                DashboardScreen(
                    onConnect       = { server -> startRelayAndOverlay(server) },
                    onDisconnect    = { stopRelayAndOverlay() },
                    getRelayRunning  = { relayService?.isRunning ?: false },
                    getTargetServer  = { relayService?.relay?.targetServer }
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

    private fun startRelayAndOverlay(server: ServerConfig) {
        val intent = Intent(this, BedrockRelayService::class.java).apply {
            putExtra(BedrockRelayService.EXTRA_HOST, server.host)
            putExtra(BedrockRelayService.EXTRA_PORT, server.port)
            putExtra(BedrockRelayService.EXTRA_NAME, server.name)
        }
        startForegroundService(intent)
        startService(Intent(this, OverlayService::class.java))

        // FIX 1: Added `import androidx.lifecycle.lifecycleScope` — resolves "Unresolved reference 'lifecycleScope'"
        lifecycleScope.launch {
            delay(2000L)
            launchMinecraft()
        }
    }

    private fun stopRelayAndOverlay() {
        relayService?.stopRelay()
        stopService(Intent(this, BedrockRelayService::class.java))
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun launchMinecraft() {
        val packages = listOf("com.mojang.minecraftpe", "com.netease.mc")
        // FIX 2: Replaced `continue` inside inline lambda with labeled `return@let`
        // to avoid "break/continue in inline lambdas is experimental" error
        for (pkg in packages) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                try { startActivity(it); return }
                catch (_: Exception) { return@let }
            }
        }
        Toast.makeText(this, "Minecraft bulunamadi", Toast.LENGTH_SHORT).show()
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  DASHBOARD SCREEN
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    onConnect       : (ServerConfig) -> Unit,
    onDisconnect    : () -> Unit,
    getRelayRunning : () -> Boolean,
    getTargetServer : () -> ServerConfig?
) {
    val context = LocalContext.current
    var isRelayRunning by remember { mutableStateOf(false) }
    var showServerPicker by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf(ServerConfig.PRESETS[0]) }
    var showAuthDialog by remember { mutableStateOf(false) }

    // Auth state - StateFlow'u collectAsState ile oku
    val authState by MicrosoftAuthManager.authState.collectAsState()
    var gamertag by remember { mutableStateOf("") }

    // Auth state değişince
    LaunchedEffect(authState) {
        when (val state = authState) {
            is AuthState.Success -> gamertag = state.gamertag
            is AuthState.WaitingForUser -> {
                // Login ekranini ac
                context.startActivity(Intent(context, DeviceCodeLoginActivity::class.java))
            }
            else -> {}
        }
    }

    // Relay durumu
    LaunchedEffect(Unit) {
        while (true) {
            isRelayRunning = getRelayRunning()
            getTargetServer()?.let { selectedServer = it }
            delay(500)
        }
    }

    // Hesap dialog'u
    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            containerColor   = OxSurface,
            title = { Text("Hesap", color = OxText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (gamertag.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = OxGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Giris yapildi:", color = OxTextSub, fontSize = 13.sp)
                        }
                        Text(gamertag, color = OxText, fontSize = 18.sp, fontWeight = FontWeight.Bold)

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                MicrosoftAuthManager.signOut()
                                gamertag = ""
                                showAuthDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = OxRed)
                        ) {
                            Icon(Icons.Default.Logout, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cikis Yap")
                        }
                    } else {
                        Text("Microsoft hesabi ile giris yapin", color = OxTextSub)
                        Text("Minecraft Bedrock oynamak icin gerekli", color = OxTextSub.copy(alpha = 0.5f), fontSize = 11.sp)

                        Spacer(Modifier.height(4.dp))

                        Button(
                            onClick = {
                                MicrosoftAuthManager.startSignIn()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OxPurple),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            // FIX 3: Icons.Default.Microsoft does not exist in Material Icons.
                            // Replaced with Icons.Default.AccountBox as a Microsoft-style placeholder.
                            // Alternatively, use a custom SVG painter via painterResource(R.drawable.ic_microsoft).
                            Icon(
                                imageVector = Icons.Default.AccountBox,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Microsoft ile Giris")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAuthDialog = false }) {
                    Text("Kapat", color = OxPurpleLight)
                }
            }
        )
    }

    // ── Ana Ekran ────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OxBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(OxPurple, OxPurpleDark))),
            contentAlignment = Alignment.Center
        ) {
            Text("Ox", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))

        Text("OxClient", color = OxText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Minecraft Client", color = OxTextSub, fontSize = 14.sp)

        Spacer(Modifier.height(32.dp))

        // Hedef Sunucu
        Text("Hedef Sunucu", color = OxTextSub, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isRelayRunning) { showServerPicker = true },
            shape = RoundedCornerShape(12.dp),
            color = OxSurface,
            border = BorderStroke(1.dp, if (isRelayRunning) OxGreen.copy(alpha = 0.5f) else OxBorder)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedServer.icon, fontSize = 24.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(selectedServer.name, color = OxText, fontWeight = FontWeight.Medium)
                        Text("${selectedServer.host}:${selectedServer.port}", color = OxTextSub, fontSize = 12.sp)
                    }
                }
                if (!isRelayRunning) Icon(Icons.Default.KeyboardArrowDown, null, tint = OxTextSub)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Hedef Uygulama
        Text("Hedef Uygulama", color = OxTextSub, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = OxSurface,
            border = BorderStroke(1.dp, OxBorder)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.SportsEsports, null, tint = OxPurpleLight)
                Spacer(Modifier.width(10.dp))
                Text("Minecraft", color = OxText)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Durum
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isRelayRunning) OxGreen.copy(alpha = 0.15f) else OxRed.copy(alpha = 0.15f)
        ) {
            Text(
                if (isRelayRunning) "● Bagli" else "● Bagli Degil",
                color = if (isRelayRunning) OxGreen else OxRed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            if (isRelayRunning) "MC'de 127.0.0.1:19132 girin"
            else "Connect'e basarak baslat",
            color = OxTextSub,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // Connect / Disconnect
        Button(
            onClick = {
                if (isRelayRunning) onDisconnect()
                else onConnect(selectedServer)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRelayRunning) OxRed else OxPurple
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                if (isRelayRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                null, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRelayRunning) "Disconnect" else "Connect",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Overlay menusu oyun icinde gorunecek",
            color = OxTextSub.copy(alpha = 0.5f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        // Alt kısım - profil butonu
        Spacer(Modifier.weight(1f))

        IconButton(
            onClick = { showAuthDialog = true },
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = "Hesap",
                tint = if (gamertag.isNotBlank()) OxGreen else OxTextSub,
                modifier = Modifier.size(36.dp)
            )
        }

        if (gamertag.isNotBlank()) {
            Text(
                gamertag,
                color = OxTextSub,
                fontSize = 11.sp
            )
        }
    }

    // Sunucu Secme Dialog
    if (showServerPicker) {
        AlertDialog(
            onDismissRequest = { showServerPicker = false },
            containerColor   = OxSurface,
            title = { Text("Sunucu Sec", color = OxText, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    ServerConfig.PRESETS.forEach { server ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedServer = server
                                    showServerPicker = false
                                }
                                .padding(vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (server.id == selectedServer.id)
                                OxPurple.copy(alpha = 0.2f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(server.icon, fontSize = 20.sp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(server.name, color = OxText, fontWeight = FontWeight.Medium)
                                    Text("${server.host}:${server.port}",
                                        color = OxTextSub, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showServerPicker = false }) {
                    Text("Kapat", color = OxPurpleLight)
                }
            }
        )
    }
}
