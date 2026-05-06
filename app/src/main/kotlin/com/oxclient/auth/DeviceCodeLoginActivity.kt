package com.oxclient.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oxclient.ui.theme.*

class DeviceCodeLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OxClientTheme { DeviceCodeLoginScreen(onClose = ::finish) } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeviceCodeLoginScreen(onClose: () -> Unit) {
    val authState by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
    val clipboard  = LocalClipboardManager.current

    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.Success, is AuthState.Error -> onClose()
            else -> Unit
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(OxBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(OxSurface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Hesap Ekle", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color.White, fontFamily = FontFamily.Monospace)
            IconButton(onClick = { MicrosoftAuthManager.cancelSignIn(); onClose() }) {
                Icon(Icons.Default.Close, contentDescription = "Kapat", tint = Color.White)
            }
        }

        when (val state = authState) {
            is AuthState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = OxPurple)
                        Spacer(Modifier.height(16.dp))
                        Text("Microsoft bağlantısı kuruluyor…", fontSize = 14.sp,
                            color = OxOnSurface.copy(0.7f), fontFamily = FontFamily.Monospace)
                    }
                }
            }
            is AuthState.WaitingForUser -> {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = OxSurface)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Microsoft giriş kodu", fontSize = 12.sp,
                            color = OxOnSurface.copy(0.55f), fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(state.userCode, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                                color = OxPurpleLight, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
                            IconButton(onClick = { clipboard.setText(AnnotatedString(state.userCode)) },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ContentCopy, "Kopyala",
                                    tint = OxOnSurface.copy(0.6f), modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Aşağıdaki sayfada bu kodu girin", fontSize = 11.sp,
                            color = OxOnSurface.copy(0.45f), fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(),
                            color = OxPurple, trackColor = OxPurple.copy(0.15f))
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(Modifier.fillMaxSize(), factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled  = true
                            settings.userAgentString    =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
                            }
                            loadUrl(state.verificationUri)
                        }
                    })
                }
            }
            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OxPurple)
                }
            }
        }
    }
}
