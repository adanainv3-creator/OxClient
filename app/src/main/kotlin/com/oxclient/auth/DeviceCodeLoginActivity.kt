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
import com.oxclient.ui.theme.OxBackground
import com.oxclient.ui.theme.OxClientTheme
import com.oxclient.ui.theme.OxOnSurface
import com.oxclient.ui.theme.OxPurple
import com.oxclient.ui.theme.OxPurpleLight
import com.oxclient.ui.theme.OxSurface

/**
 * DeviceCodeLoginActivity
 *
 * Microsoft Device Code Flow giriş ekranı.
 *
 * Açılış koşulları:
 *  - [MicrosoftAuthManager.startSignIn] çağrıldıktan sonra
 *  - authState = [AuthState.WaitingForUser] olunca DashboardActivity bu Activity'yi başlatır
 *
 * Bu Activity:
 *  1. authState'i gözlemler
 *  2. [AuthState.WaitingForUser] → user_code'u büyük gösterir + WebView'da verification_uri'yi açar
 *  3. Kullanıcı WebView'da kodu girip onaylar
 *  4. [AuthState.Success] veya [AuthState.Error] gelince otomatik kapanır
 *
 * Intent ile başlatmak için:
 *   startActivity(Intent(this, DeviceCodeLoginActivity::class.java))
 *   (Ekstra parametre gerekmez — authState zaten MicrosoftAuthManager'da)
 */
class DeviceCodeLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OxClientTheme {
                DeviceCodeLoginScreen(
                    onClose = ::finish
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeviceCodeLoginScreen(onClose: () -> Unit) {
    val authState by MicrosoftAuthManager.authState.collectAsStateWithLifecycle()
    val clipboard  = LocalClipboardManager.current

    // Başarı veya hata → ekranı kapat
    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.Success, is AuthState.Error -> onClose()
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OxBackground)
    ) {

        // ── Top bar ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OxSurface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text       = "Hesap Ekle",
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                fontFamily = FontFamily.Monospace
            )
            IconButton(onClick = {
                MicrosoftAuthManager.cancelSignIn()
                onClose()
            }) {
                Icon(Icons.Default.Close, contentDescription = "Kapat", tint = Color.White)
            }
        }

        // ── İçerik ────────────────────────────────────────────────────────
        when (val state = authState) {

            is AuthState.Loading -> {
                // Device code alınıyor
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = OxPurple)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text       = "Microsoft bağlantısı kuruluyor…",
                            fontSize   = 14.sp,
                            color      = OxOnSurface.copy(0.7f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            is AuthState.WaitingForUser -> {
                // Kod banner'ı
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape  = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = OxSurface)
                ) {
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text       = "Microsoft giriş kodu",
                            fontSize   = 12.sp,
                            color      = OxOnSurface.copy(0.55f),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text          = state.userCode,
                                fontSize      = 30.sp,
                                fontWeight    = FontWeight.ExtraBold,
                                color         = OxPurpleLight,
                                fontFamily    = FontFamily.Monospace,
                                letterSpacing = 4.sp
                            )
                            IconButton(
                                onClick  = { clipboard.setText(AnnotatedString(state.userCode)) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector  = Icons.Default.ContentCopy,
                                    contentDescription = "Kopyala",
                                    tint         = OxOnSurface.copy(0.6f),
                                    modifier     = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text       = "Aşağıdaki sayfada bu kodu girin",
                            fontSize   = 11.sp,
                            color      = OxOnSurface.copy(0.45f),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color    = OxPurple,
                            trackColor = OxPurple.copy(0.15f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text       = "Onay bekleniyor…",
                            fontSize   = 10.sp,
                            color      = OxOnSurface.copy(0.35f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // WebView — microsoft.com/devicelogin
                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory  = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString   =
                                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                    "Chrome/124.0.0.0 Mobile Safari/537.36"

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ) = false   // Tüm URL'leri WebView içinde aç
                                }

                                loadUrl(state.verificationUri)
                            }
                        }
                    )
                }
            }

            else -> {
                // Idle / Success / Error — LaunchedEffect zaten kapatıyor
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = OxPurple)
                }
            }
        }
    }
}
