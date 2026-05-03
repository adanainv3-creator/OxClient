package com.oxclient.ui.dashboard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Microsoft Device Code Flow — tamamen OkHttp ile, MinecraftAuth/Apache HttpClient YOK.
 *
 * Akış:
 * 1. /devicecode endpoint'inden user_code + verification_uri al
 * 2. verification_uri'yi WebView'a yükle (kullanıcı orada giriş yapar)
 * 3. /token endpoint'ini poll et → access_token al
 * 4. Xbox Live token al
 * 5. XSTS token al
 * 6. Minecraft Bedrock token al → gamertag
 */
object MicrosoftAuthManager {

    // ── Microsoft / Xbox sabitleri ────────────────────────────────────────
    // Bedrock Android client id (public, resmi Minecraft uygulaması)
    private const val CLIENT_ID   = "0000000048183522"
    private const val SCOPE       = "service::user.auth.xboxlive.com::MBI_SSL"

    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL       = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL         = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL        = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_BEDROCK_URL  = "https://multiplayer.minecraft.net/authentication"

    private const val POLL_INTERVAL_MS = 5_000L
    private const val POLL_TIMEOUT_MS  = 5 * 60 * 1000L // 5 dakika

    // ── State ─────────────────────────────────────────────────────────────

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
    private var initialized     = false
    private var activeSignInJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        cacheDir = File(context.filesDir, "ox_accounts").apply { mkdirs() }
        loadAccounts()
    }

    // ── Sign In ───────────────────────────────────────────────────────────

    fun signIn() {
        if (_authState.value is AuthState.Loading ||
            _authState.value is AuthState.WebViewReady) return
        _authState.value = AuthState.Loading

        activeSignInJob = scope.launch {
            try {
                // 1. Device code al
                val deviceResp = postForm(
                    DEVICE_CODE_URL,
                    mapOf(
                        "client_id"  to CLIENT_ID,
                        "scope"      to SCOPE,
                        "response_type" to "device_code"
                    )
                )
                val deviceCode       = deviceResp["device_code"] as String
                val verificationUri  = deviceResp["verification_uri"] as String
                val intervalMs       = ((deviceResp["interval"] as? Double)?.toLong() ?: 5L) * 1000L

                // 2. WebView'u aç
                _authState.value = AuthState.WebViewReady(verificationUri)

                // 3. Token poll et
                val tokenResp = pollToken(deviceCode, intervalMs)
                val accessToken  = tokenResp["access_token"]  as String
                val refreshToken = tokenResp["refresh_token"] as? String ?: ""

                // 4. Xbox Live
                val xblToken = getXblToken(accessToken)

                // 5. XSTS
                val (xstsToken, userHash) = getXstsToken(xblToken)

                // 6. Minecraft Bedrock
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

            } catch (e: CancellationException) {
                _authState.value = AuthState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                _authState.value = AuthState.Error(e.message ?: "Giriş başarısız")
            }
        }
    }

    fun cancelSignIn() {
        activeSignInJob?.cancel()
        activeSignInJob = null
        _authState.value = AuthState.Idle
    }

    fun signOut() {
        selectedAccount?.let { removeAccount(it) }
        selectedAccount = null
        _authState.value = AuthState.Idle
        File(cacheDir, "selected").delete()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

    private fun postForm(url: String, params: Map<String, String>): Map<String, Any?> {
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder().url(url).post(body).build()
        val resp = http.newCall(req).execute()
        val text = resp.body?.string() ?: error("Boş yanıt: $url")
        return parseResponse(text)
    }

    private fun postJson(url: String, json: String, extraHeaders: Map<String, String> = emptyMap()): Map<String, Any?> {
        val body = json.toRequestBody("application/json".toMediaType())
        val builder = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val resp = http.newCall(builder.build()).execute()
        val text = resp.body?.string() ?: error("Boş yanıt: $url")
        return parseResponse(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseResponse(text: String): Map<String, Any?> {
        return try {
            Gson().fromJson(text, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            // form-urlencoded yanıt
            text.split("&").associate { part ->
                val eq = part.indexOf('=')
                if (eq < 0) part to null
                else part.substring(0, eq) to part.substring(eq + 1)
            }
        }
    }

    // ── Auth flow adımları ────────────────────────────────────────────────

    private suspend fun pollToken(deviceCode: String, intervalMs: Long): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(intervalMs.coerceAtLeast(POLL_INTERVAL_MS))
            currentCoroutineContext().ensureActive()

            val resp = postForm(
                TOKEN_URL,
                mapOf(
                    "client_id"   to CLIENT_ID,
                    "grant_type"  to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to deviceCode
                )
            )
            val error = resp["error"] as? String
            when {
                error == null || resp.containsKey("access_token") -> return resp
                error == "authorization_pending" || error == "slow_down" -> continue
                else -> error("Token hatası: $error — ${resp["error_description"]}")
            }
        }
        error("Giriş zaman aşımı — lütfen tekrar deneyin")
    }

    private fun getXblToken(msAccessToken: String): String {
        val body = """
            {
              "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": "$msAccessToken"
              },
              "RelyingParty": "http://auth.xboxlive.com",
              "TokenType": "JWT"
            }
        """.trimIndent()
        val resp = postJson(XBL_URL, body)
        return (resp["Token"] as? String) ?: error("XBL token alınamadı")
    }

    private fun getXstsToken(xblToken: String): Pair<String, String> {
        val body = """
            {
              "Properties": {
                "SandboxId": "RETAIL",
                "UserTokens": ["$xblToken"]
              },
              "RelyingParty": "https://multiplayer.minecraft.net/",
              "TokenType": "JWT"
            }
        """.trimIndent()
        val resp = postJson(XSTS_URL, body)
        if (resp.containsKey("XErr")) {
            val xerr = (resp["XErr"] as? Double)?.toLong()
            val msg = when (xerr) {
                2148916233L -> "Microsoft hesabınızın Xbox hesabı yok. Xbox.com'dan oluşturun."
                2148916238L -> "Çocuk hesabı — ebeveyn onayı gerekli."
                else        -> "XSTS hatası: $xerr"
            }
            error(msg)
        }
        val token    = (resp["Token"] as? String) ?: error("XSTS token alınamadı")
        @Suppress("UNCHECKED_CAST")
        val claims   = resp["DisplayClaims"] as? Map<String, Any?> ?: error("DisplayClaims yok")
        @Suppress("UNCHECKED_CAST")
        val xui      = (claims["xui"] as? List<Map<String, Any?>>)?.firstOrNull() ?: error("xui yok")
        val userHash = xui["uhs"] as? String ?: error("uhs yok")
        return token to userHash
    }

    private fun getMinecraftToken(xstsToken: String, userHash: String): Pair<String, String> {
        val body = """{"identityPublicKey":""}"""
        // Bedrock auth endpoint
        val authHeader = "XBL3.0 x=$userHash;$xstsToken"
        val req = Request.Builder()
            .url(MC_BEDROCK_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", authHeader)
            .header("User-Agent", "MCPE/UWP")
            .header("client-version", "1.21.0")
            .build()
        val resp     = http.newCall(req).execute()
        val text     = resp.body?.string() ?: error("MC token yanıtı boş")
        val json     = parseResponse(text)
        val token    = json["token"] as? String ?: error("MC token alınamadı: $text")
        // Gamertag için Xbox profil API
        val gamertag = fetchGamertag(xstsToken, userHash)
        return token to gamertag
    }

    private fun fetchGamertag(xstsToken: String, userHash: String): String {
        return try {
            val req = Request.Builder()
                .url("https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag")
                .header("Authorization", "XBL3.0 x=$userHash;$xstsToken")
                .header("x-xbl-contract-version", "2")
                .header("Accept-Language", "en-US")
                .build()
            val text = http.newCall(req).execute().body?.string() ?: return "Oyuncu"
            val json = JSONObject(text)
            json.getJSONObject("profileUsers")
                .getJSONArray("users")
                .getJSONObject(0)
                .getJSONArray("settings")
                .getJSONObject(0)
                .getString("value")
        } catch (e: Exception) {
            "Oyuncu"
        }
    }

    // ── Account Management ────────────────────────────────────────────────

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
