package com.oxclient.ui.dashboard

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Microsoft OAuth 2.0 — PKCE + Redirect Flow (WebView içinde)
 *
 * Akış:
 * 1. code_verifier + code_challenge üret (PKCE)
 * 2. Login URL'sini WebView'a yükle
 * 3. WebView ms-xal-{clientId}://auth?code=XXX redirect'ini yakalar
 * 4. code ile access_token + refresh_token al
 * 5. Xbox Live → XSTS → Minecraft Bedrock token
 */
object MicrosoftAuthManager {

    // Bedrock Android client (public, Minecraft uygulamasının kullandığı)
    private const val CLIENT_ID    = "0000000048183522"
    private const val REDIRECT_URI = "ms-xal-0000000048183522://auth"
    private const val SCOPE        = "service::user.auth.xboxlive.com::MBI_SSL"

    private const val AUTH_URL   = "https://login.live.com/oauth20_authorize.srf"
    private const val TOKEN_URL  = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL    = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL   = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_URL     = "https://multiplayer.minecraft.net/authentication"

    // ── State ──────────────────────────────────────────────────────────────

    sealed class AuthState {
        object Idle    : AuthState()
        object Loading : AuthState()
        data class WebViewReady(val loginUrl: String) : AuthState()
        data class Success(val gamertag: String, val token: String) : AuthState()
        data class Error(val msg: String) : AuthState()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    data class SavedAccount(
        val gamertag: String,
        val refreshToken: String,
        val mcToken: String,
        val expireTime: Long
    )

    private val _accounts = mutableStateListOf<SavedAccount>()
    val accounts: List<SavedAccount> get() = _accounts

    var selectedAccount: SavedAccount? by mutableStateOf(null)
        private set

    private val gson  = GsonBuilder().setPrettyPrinting().create()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var cacheDir: File
    private var initialized = false

    // PKCE state — WebView callback gelene kadar saklanır
    private var pkceVerifier: String = ""

    // ── Init ───────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        cacheDir = File(context.filesDir, "ox_accounts").apply { mkdirs() }
        loadAccounts()
    }

    // ── Sign In ────────────────────────────────────────────────────────────

    fun signIn() {
        if (_authState.value is AuthState.Loading ||
            _authState.value is AuthState.WebViewReady) return
        _authState.value = AuthState.Loading

        scope.launch {
            try {
                val (verifier, challenge) = generatePkce()
                pkceVerifier = verifier

                val loginUrl = Uri.parse(AUTH_URL).buildUpon()
                    .appendQueryParameter("client_id",             CLIENT_ID)
                    .appendQueryParameter("response_type",         "code")
                    .appendQueryParameter("redirect_uri",          REDIRECT_URI)
                    .appendQueryParameter("scope",                 SCOPE)
                    .appendQueryParameter("code_challenge",        challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .build()
                    .toString()

                // WebView'ı aç — callback onAuthCodeReceived() üzerinden gelir
                _authState.value = AuthState.WebViewReady(loginUrl)

            } catch (e: Exception) {
                e.printStackTrace()
                _authState.value = AuthState.Error(e.message ?: "Başlatma hatası")
            }
        }
    }

    /**
     * WebView'dan gelen authorization code ile auth akışını tamamlar.
     * MicrosoftAuthWebViewActivity tarafından çağrılır.
     */
    fun onAuthCodeReceived(code: String) {
        _authState.value = AuthState.Loading

        scope.launch {
            try {
                // 1. Authorization code → tokens
                val tokenResp   = exchangeCodeForToken(code)
                val accessToken  = tokenResp.getString("access_token")
                val refreshToken = runCatching { tokenResp.getString("refresh_token") }.getOrDefault("")

                // 2. Xbox Live
                val xblToken = getXblToken(accessToken)

                // 3. XSTS
                val (xstsToken, userHash) = getXstsToken(xblToken)

                // 4. Minecraft Bedrock
                val (mcToken, gamertag) = getMinecraftToken(xstsToken, userHash)

                val account = SavedAccount(
                    gamertag     = gamertag,
                    refreshToken = refreshToken,
                    mcToken      = mcToken,
                    expireTime   = System.currentTimeMillis() + 6 * 3600_000L
                )
                addAccount(account)
                selectedAccount = account
                _authState.value = AuthState.Success(gamertag, mcToken)

            } catch (e: Exception) {
                e.printStackTrace()
                _authState.value = AuthState.Error(e.message ?: "Giriş başarısız")
            }
        }
    }

    fun cancelSignIn() {
        _authState.value = AuthState.Idle
    }

    fun signOut() {
        selectedAccount?.let { removeAccount(it) }
        selectedAccount = null
        _authState.value = AuthState.Idle
        File(cacheDir, "selected").delete()
    }

    // ── PKCE ──────────────────────────────────────────────────────────────

    private fun generatePkce(): Pair<String, String> {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val verifier  = Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val digest    = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return verifier to challenge
    }

    // ── Auth flow adımları ─────────────────────────────────────────────────

    private fun exchangeCodeForToken(code: String): JSONObject {
        val body = FormBody.Builder()
            .add("client_id",     CLIENT_ID)
            .add("code",          code)
            .add("grant_type",    "authorization_code")
            .add("redirect_uri",  REDIRECT_URI)
            .add("code_verifier", pkceVerifier)
            .build()

        val resp = http.newCall(
            Request.Builder().url(TOKEN_URL).post(body).build()
        ).execute()

        val text = resp.body?.string() ?: error("Token yanıtı boş")
        val json = JSONObject(text)
        if (json.has("error")) {
            error("Token hatası: ${json.optString("error_description", json.getString("error"))}")
        }
        return json
    }

    private fun getXblToken(msAccessToken: String): String {
        val body = """
            {
              "Properties": {
                "AuthMethod": "RPS",
                "SiteName":   "user.auth.xboxlive.com",
                "RpsTicket":  "d=$msAccessToken"
              },
              "RelyingParty": "http://auth.xboxlive.com",
              "TokenType":    "JWT"
            }
        """.trimIndent()

        val json = postJsonRaw(XBL_URL, body)
        return json.optString("Token").ifBlank { error("XBL token alınamadı: $json") }
    }

    private fun getXstsToken(xblToken: String): Pair<String, String> {
        val body = """
            {
              "Properties": {
                "SandboxId":  "RETAIL",
                "UserTokens": ["$xblToken"]
              },
              "RelyingParty": "https://multiplayer.minecraft.net/",
              "TokenType":    "JWT"
            }
        """.trimIndent()

        val json = postJsonRaw(XSTS_URL, body)

        if (json.has("XErr")) {
            val xerr = json.getLong("XErr")
            val msg  = when (xerr) {
                2148916233L -> "Microsoft hesabınızın Xbox hesabı yok. Xbox.com'dan oluşturun."
                2148916238L -> "Çocuk hesabı — ebeveyn onayı gerekli."
                else        -> "XSTS hatası: $xerr"
            }
            error(msg)
        }

        val token    = json.optString("Token").ifBlank { error("XSTS token alınamadı") }
        val userHash = json
            .getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
            .getString("uhs")

        return token to userHash
    }

    private fun getMinecraftToken(xstsToken: String, userHash: String): Pair<String, String> {
        val body      = """{"identityPublicKey":""}"""
        val authHeader = "XBL3.0 x=$userHash;$xstsToken"

        val req = Request.Builder()
            .url(MC_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization",   authHeader)
            .header("Content-Type",    "application/json")
            .header("Accept",          "application/json")
            .header("User-Agent",      "MCPE/UWP")
            .header("client-version",  "1.21.0")
            .build()

        val text  = http.newCall(req).execute().body?.string() ?: error("MC token yanıtı boş")
        val json  = JSONObject(text)
        val token = json.optString("token").ifBlank { error("MC token alınamadı: $text") }

        val gamertag = fetchGamertag(xstsToken, userHash)
        return token to gamertag
    }

    private fun fetchGamertag(xstsToken: String, userHash: String): String {
        return try {
            val req  = Request.Builder()
                .url("https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag")
                .header("Authorization",          "XBL3.0 x=$userHash;$xstsToken")
                .header("x-xbl-contract-version", "2")
                .header("Accept-Language",        "en-US")
                .build()
            val text = http.newCall(req).execute().body?.string() ?: return "Oyuncu"
            JSONObject(text)
                .getJSONObject("profileUsers")
                .getJSONArray("users")
                .getJSONObject(0)
                .getJSONArray("settings")
                .getJSONObject(0)
                .getString("value")
        } catch (e: Exception) { "Oyuncu" }
    }

    // ── HTTP helper ────────────────────────────────────────────────────────

    private fun postJsonRaw(url: String, jsonBody: String): JSONObject {
        val resp = http.newCall(
            Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept",       "application/json")
                .build()
        ).execute()
        val text = resp.body?.string() ?: error("Boş yanıt: $url")
        return JSONObject(text)
    }

    // ── Account Management ─────────────────────────────────────────────────

    fun addAccount(account: SavedAccount) {
        _accounts.removeAll { it.gamertag == account.gamertag }
        _accounts.add(account)
        saveAccount(account)
        File(cacheDir, "selected").writeText(account.gamertag)
    }

    fun removeAccount(account: SavedAccount) {
        _accounts.remove(account)
        File(cacheDir, "${account.gamertag}.json").delete()
    }

    private fun saveAccount(acc: SavedAccount) {
        runCatching {
            File(cacheDir, "${acc.gamertag}.json").writeText(gson.toJson(acc))
        }
    }

    private fun loadAccounts() {
        val selectedName = runCatching {
            File(cacheDir, "selected").readText().trim()
        }.getOrNull()

        cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.extension != "json") return@forEach
            runCatching {
                val acc = gson.fromJson(file.readText(), SavedAccount::class.java)
                _accounts.add(acc)
                if (acc.gamertag == selectedName) {
                    selectedAccount = acc
                    _authState.value = AuthState.Success(acc.gamertag, acc.mcToken)
                }
            }
        }
    }
}
