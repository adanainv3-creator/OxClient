package com.oxclient.ui.dashboard

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Microsoft OAuth girişi için WebView Activity.
 *
 * DashboardActivity tarafından şu şekilde başlatılır:
 *   Intent(this, MicrosoftAuthWebViewActivity::class.java)
 *       .putExtra("login_url", url)
 *
 * WebView, Microsoft login sayfasını gösterir. Kullanıcı giriş yapınca
 * MinecraftAuth kütüphanesi arka planda kodu yakalar ve session'ı tamamlar.
 * Bu Activity yalnızca tarayıcı penceresini sağlar — tüm token işlemleri
 * MicrosoftAuthManager.signIn() coroutine'inde zaten yürümektedir.
 *
 * NOT: AppCompatActivity DEĞİL ComponentActivity kullanılıyor.
 * AppCompatActivity, Compose temasıyla (Theme.OxClient) çakışarak
 * "You need to use a Theme.AppCompat theme" crash'ine yol açar.
 * ComponentActivity bu kısıtlamadan muaftır.
 */
class MicrosoftAuthWebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LOGIN_URL = "login_url"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL)
        if (loginUrl.isNullOrBlank()) {
            finish()
            return
        }

        // WebView'u programatik oluştur, XML layout gerektirmez
        val webView = WebView(this).also { setContentView(it) }

        // Cookie'leri temizle — önceki oturumun kalıntısı girişi bozabilir
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            // Microsoft login sayfasının modern UA beklediği durumlar için
            userAgentString      = "Mozilla/5.0 (Linux; Android 10) " +
                                   "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                   "Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Tüm URL'leri WebView içinde aç — dış tarayıcıya yönlendirme
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Kullanıcı onay sayfasını geçince Microsoft "?code=..." ile
                // yönlendirir. MinecraftAuth bu kodu arka planda zaten dinliyor;
                // Activity'nin ekstra bir şey yapmasına gerek yok.
                //
                // Başarı/hata durumu MicrosoftAuthManager.authState flow'u üzerinden
                // DashboardActivity'ye iletilir.
                if (url.contains("code=") || url.contains("error=")) {
                    // Kısa gecikme: WebView JS'nin son işlemini bitirmesine izin ver
                    view.postDelayed({ finish() }, 500)
                }
            }
        }

        webView.loadUrl(loginUrl)

        // Geri tuşuna basılırsa sign-in iptal et
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                MicrosoftAuthManager.cancelSignIn()
                finish()
            }
        })
    }
}
