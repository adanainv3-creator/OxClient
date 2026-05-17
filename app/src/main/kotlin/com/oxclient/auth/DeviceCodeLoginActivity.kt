// auth/DeviceCodeLoginActivity.kt
package com.oxclient.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * DeviceCodeLoginActivity — WebView tabanlı Microsoft OAuth giriş ekranı.
 *
 * Authorization Code Flow (PKCE'siz, klasik):
 *   1. WebView'da Microsoft login sayfası açılır
 *   2. Kullanıcı giriş yapar
 *   3. Redirect URL'den "code" parametresi yakalanır
 *   4. MicrosoftAuthManager.exchangeCodeForToken() çağrılır
 *   5. Activity kapatılır — auth akışı arka planda devam eder
 *
 * Kullanım: DashboardActivity'den startActivity() ile başlatılır.
 * Sonuç: MicrosoftAuthManager.authState üzerinden dinlenir.
 */
class DeviceCodeLoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WebViewLogin"

        // Microsoft OAuth — aynı client_id, ama authorization code flow
        private const val CLIENT_ID    = "0000000048183522"
        private const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
        private const val SCOPE        = "service::user.auth.xboxlive.com::MBI_SSL"

        private val AUTH_URL = buildString {
            append("https://login.live.com/oauth20_authorize.srf")
            append("?client_id=$CLIENT_ID")
            append("&response_type=code")
            append("&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}")
            append("&scope=${android.net.Uri.encode(SCOPE)}")
            append("&display=touch")       // mobil görünüm
            append("&locale=tr")
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var codeExchanged = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Layout (kodla oluştur, xml gerektirmez) ────────────────────
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

            webViewClient = object : WebViewClient() {

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                    interceptRedirect(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    progressBar.visibility = View.GONE
                    interceptRedirect(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    interceptRedirect(url)
                    return false   // WebView yüklemesine izin ver (redirect sayfası boş olabilir)
                }
            }
        }

        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)

        // ── Cookie temizle (önceki oturumun etkisini kaldır) ──────────
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        // ── Microsoft login sayfasını aç ──────────────────────────────
        Log.d(TAG, "WebView açılıyor: $AUTH_URL")
        webView.loadUrl(AUTH_URL)
    }

    /**
     * URL'de "code=" parametresi varsa yakalar ve token exchange başlatır.
     * Redirect URI: https://login.live.com/oauth20_desktop.srf?code=XXX
     */
    private fun interceptRedirect(url: String) {
        if (codeExchanged) return
        if (!url.startsWith(REDIRECT_URI)) return

        val uri  = android.net.Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val err  = uri.getQueryParameter("error")

        when {
            !code.isNullOrBlank() -> {
                codeExchanged = true
                Log.i(TAG, "Authorization code alındı ✓")
                Toast.makeText(this, "Kod alındı, bağlanıyor…", Toast.LENGTH_SHORT).show()

                // Auth Manager'a kodu ver — arka planda token exchange yapacak
                MicrosoftAuthManager.exchangeCodeForToken(code)
                finish()
            }
            !err.isNullOrBlank() -> {
                Log.e(TAG, "OAuth hatası: $err — ${uri.getQueryParameter("error_description")}")
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
