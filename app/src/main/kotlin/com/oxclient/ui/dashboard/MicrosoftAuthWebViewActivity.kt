package com.oxclient.ui.dashboard

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Microsoft OAuth girişi için WebView Activity.
 *
 * DashboardActivity tarafından şu şekilde başlatılır:
 *   Intent(this, MicrosoftAuthWebViewActivity::class.java)
 *       .putExtra(EXTRA_LOGIN_URL, url)
 *
 * WebView login sayfasını gösterir. Kullanıcı giriş yapınca Microsoft,
 * ms-xal-0000000048183522://auth?code=XXX adresine yönlendirir.
 * shouldOverrideUrlLoading bu redirect'i yakalar → MicrosoftAuthManager.onAuthCodeReceived()
 * çağrılır → Activity kapanır → token akışı arka planda tamamlanır.
 */
class MicrosoftAuthWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOGIN_URL = "login_url"
        // Redirect URI şeması — CLIENT_ID ile eşleşmeli
        private const val REDIRECT_SCHEME = "ms-xal-0000000048183522"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL)
        if (loginUrl.isNullOrBlank()) {
            finish()
            return
        }

        val webView = WebView(this).also { setContentView(it) }

        // Önceki oturum cookie'lerini temizle
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString   =
                "Mozilla/5.0 (Linux; Android 10) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url

                // Microsoft, başarılı girişten sonra ms-xal-...://auth?code=XXX adresine yönlendirir
                if (uri.scheme == REDIRECT_SCHEME) {
                    handleRedirect(uri)
                    return true   // WebView'ın bu URL'yi yüklemesini engelle
                }

                // Diğer tüm URL'leri WebView içinde aç
                return false
            }
        }

        webView.loadUrl(loginUrl)
    }

    private fun handleRedirect(uri: Uri) {
        val code  = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        when {
            code != null -> {
                // Authorization code'u Manager'a ilet — token akışı orada tamamlanır
                MicrosoftAuthManager.onAuthCodeReceived(code)
            }
            error != null -> {
                val desc = uri.getQueryParameter("error_description") ?: error
                MicrosoftAuthManager.cancelSignIn()
                // İsteğe bağlı: hata mesajını authState üzerinden iletmek için
                // MicrosoftAuthManager.onAuthError(desc) metodunu ekleyebilirsin
            }
            else -> {
                MicrosoftAuthManager.cancelSignIn()
            }
        }

        finish()   // Activity'yi kapat, kullanıcı Dashboard'a döner
    }

    override fun onBackPressed() {
        MicrosoftAuthManager.cancelSignIn()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
