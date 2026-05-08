package com.oxclient.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.oxclient.ui.theme.OxTheme
import com.oxclient.ui.theme.*
import kotlinx.coroutines.launch

class DeviceCodeLoginActivity : ComponentActivity() {

    private val authManager = MicrosoftAuthManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountManager = AccountManager(this)

        setContent {
            OxTheme {
                var step       by remember { mutableStateOf("loading") }
                var userCode   by remember { mutableStateOf("") }
                var verifyUrl  by remember { mutableStateOf("") }
                var statusText by remember { mutableStateOf("Cihaz kodu alınıyor...") }

                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    scope.launch {
                        val device = authManager.getDeviceCode()
                        if (device == null) {
                            statusText = "Hata! Tekrar deneyin."; step = "error"; return@launch
                        }
                        userCode  = device.userCode
                        verifyUrl = device.verificationUri
                        step      = "code"

                        statusText = "Oturum bekleniyor..."
                        val ms = authManager.pollToken(device.deviceCode, device.interval)
                        if (ms == null) { statusText = "Zaman aşımı."; step = "error"; return@launch }

                        step = "loading"; statusText = "Xbox hesabı doğrulanıyor..."
                        val xbl = authManager.getXblToken(ms.accessToken) ?: run {
                            statusText = "XBL hatası"; step = "error"; return@launch }
                        val xsts = authManager.getXstsToken(xbl) ?: run {
                            statusText = "XSTS hatası"; step = "error"; return@launch }
                        val mcToken = authManager.getMinecraftToken(xsts) ?: run {
                            statusText = "MC token hatası"; step = "error"; return@launch }
                        val profile = authManager.getProfile(mcToken, xsts.userHash) ?: run {
                            statusText = "Profil hatası"; step = "error"; return@launch }

                        accountManager.save(profile)
                        step = "done"
                        statusText = "Giriş yapıldı: ${profile.name}"
                        kotlinx.coroutines.delay(1500)
                        finish()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = OxBackground) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Microsoft Giriş", color = OxText,
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

                        Spacer(Modifier.height(32.dp))

                        when (step) {
                            "loading" -> {
                                CircularProgressIndicator(color = OxPurple)
                                Spacer(Modifier.height(16.dp))
                                Text(statusText, color = OxTextSub, textAlign = TextAlign.Center)
                            }
                            "code" -> {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = OxSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text("Tarayıcıda şu siteye git:", color = OxTextSub)
                                        Text(verifyUrl, color = OxPurpleLight,
                                            style = MaterialTheme.typography.bodyLarge)
                                        Divider(color = OxBorder)
                                        Text("Kodu gir:", color = OxTextSub)
                                        Text(userCode, color = OxText,
                                            style = MaterialTheme.typography.displaySmall,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 4.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText("code", userCode))
                                                Toast.makeText(this@DeviceCodeLoginActivity, "Kopyalandı", Toast.LENGTH_SHORT).show()
                                            }) { Text("Kopyala", color = OxPurpleLight) }
                                            Button(onClick = {
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl)))
                                            }, colors = ButtonDefaults.buttonColors(containerColor = OxPurple)) {
                                                Text("Tarayıcıda Aç")
                                            }
                                        }
                                        CircularProgressIndicator(color = OxPurple, modifier = Modifier.size(24.dp))
                                        Text(statusText, color = OxTextSub,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            "done" -> {
                                Text("✅", fontSize = 48.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(statusText, color = OxGreen,
                                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                            }
                            "error" -> {
                                Text("❌", fontSize = 48.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(statusText, color = OxRed,
                                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { finish() },
                                    colors = ButtonDefaults.buttonColors(containerColor = OxPurple)) {
                                    Text("Geri Dön")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
