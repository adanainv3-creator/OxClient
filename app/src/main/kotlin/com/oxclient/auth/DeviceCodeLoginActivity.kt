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
import com.oxclient.ui.theme.*

class DeviceCodeLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OxTheme {
                val authState by MicrosoftAuthManager.authState.collectAsState()
                var userCode by remember { mutableStateOf("") }
                var verifyUrl by remember { mutableStateOf("") }
                var statusText by remember { mutableStateOf("Cihaz kodu aliniyor...") }
                var step by remember { mutableStateOf("loading") }

                LaunchedEffect(authState) {
                    when (val state = authState) {
                        is AuthState.WaitingForUser -> {
                            userCode = state.userCode
                            verifyUrl = state.verificationUri
                            step = "code"
                            statusText = "Onay bekleniyor..."
                        }
                        is AuthState.Success -> {
                            step = "done"
                            statusText = "Giris yapildi: ${state.gamertag}"
                            kotlinx.coroutines.delay(1500)
                            finish()
                        }
                        is AuthState.Error -> {
                            step = "error"
                            statusText = state.message
                        }
                        is AuthState.Idle -> {
                            if (step == "loading") {
                                // Henüz başlamadıysa başlat
                            }
                        }
                        else -> {}
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = OxBackground) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Microsoft Giris", color = OxText,
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                        Spacer(Modifier.height(24.dp))

                        when (step) {
                            "loading", "code" -> {
                                if (step == "loading") {
                                    CircularProgressIndicator(color = OxPurple)
                                    Spacer(Modifier.height(12.dp))
                                    Text(statusText, color = OxTextSub)
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = OxSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text("Kodu gir:", color = OxTextSub, fontSize = 13.sp)
                                            Text(userCode, color = OxText,
                                                style = MaterialTheme.typography.displaySmall,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 3.sp)
                                            
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = {
                                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    cm.setPrimaryClip(ClipData.newPlainText("code", userCode))
                                                    Toast.makeText(this@DeviceCodeLoginActivity, "Kopyalandi", Toast.LENGTH_SHORT).show()
                                                }) { Text("Kopyala", color = OxPurpleLight) }
                                                
                                                Button(onClick = {
                                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl)))
                                                }, colors = ButtonDefaults.buttonColors(containerColor = OxPurple)) {
                                                    Text("Tarayicida Ac")
                                                }
                                            }
                                            
                                            CircularProgressIndicator(color = OxPurple, modifier = Modifier.size(20.dp))
                                            Text(statusText, color = OxTextSub, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            "done" -> {
                                Text("✅", fontSize = 48.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(statusText, color = OxGreen, textAlign = TextAlign.Center)
                            }
                            "error" -> {
                                Text("❌", fontSize = 48.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(statusText, color = OxRed, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { finish() },
                                    colors = ButtonDefaults.buttonColors(containerColor = OxPurple)) {
                                    Text("Geri Don")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}