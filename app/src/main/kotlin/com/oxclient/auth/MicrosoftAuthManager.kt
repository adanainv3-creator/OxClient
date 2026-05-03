package com.oxclient.auth

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MicrosoftAuthManager
 *
 * Microsoft Device Code Flow ile Minecraft Bedrock kimlik doğrulaması.
 * Harici kütüphane YOK — tamamen OkHttp + standart JSON.
 *
 * Akış:
 *  1. [startSignIn] → /devicecode → user_code + verification_uri
 *  2. State = [AuthState.WaitingForUser] → DeviceCodeLoginActivity WebView'ı açar
 *  3. Arka planda /token poll edilir
 *  4. Xbox Live → XSTS → Minecraft Bedrock token
 *  5. State = [AuthState.Success] → AccountManager.addAccount()
 *
 * Kullanım:
 *   MicrosoftAuthManager.init(context)
 *   MicrosoftAuthManager.startSignIn()          // coroutine başlatır
 *   MicrosoftAuthManager.cancelSignIn()          // iptal
 *   MicrosoftAuthManager.authState.collect { }  // UI gözlemler
 */
object MicrosoftAuthManager {

    // ── Microsoft sabitleri ───────────────────────────────────────────────
    // Resmi Minecraft Bedrock Android client ID (public)
    private const val CLIENT_ID = "0000000048183522"
    private const val SCOPE     = "service::user.auth.xboxlive.com::MBI_SSL"

    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL       = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL         = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL        = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_BEDROCK_URL  = "https://multiplayer.minecraft.net/authentication"
    private const val PROFILE_URL     = "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag"

    private const val POLL_TIMEOUT_MS  = 5 * 60 * 1000L   // 5 dakika
    private const val MIN_INTERVAL_MS  = 5_000L

    // ── State ─────────────────────────────────────────────────────────────
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // ── HTTP ──────────────────────────────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ── İç değişkenler ────────────────────────────────────────────────────
    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private var initialized     = false

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        AccountManager.init(context)

        // Kayıtlı hesap varsa state'i güncelle
        val saved = AccountManager.selectedAccount
        if (saved != null) {
            _authState.value = AuthState.Success(saved.gamertag, saved.mcToken)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun startSignIn() {
        if (_authState.value is AuthState.Loading ||
            _authState.value is AuthState.WaitingForUser) return

        _authState.value = AuthState.Loading

        activeJob = scope.launch {
            runCatching { doSignInFlow() }
                .onFailure { e ->
                    if (e is CancellationException) {
                        _authState.value = AuthState.Idle
                    } else {
                        android.util.Log.e("MicrosoftAuth", "Sign-in failed", e)
                        _authState.value = AuthState.Error(e.message ?: "Giriş başarısız")
                    }
                }
        }
    }

    fun cancelSignIn() {
        activeJob?.cancel()
        activeJob = null
        _authState.value = AuthState.Idle
    }

    fun signOut() {
        AccountManager.selectedAccount?.let { AccountManager.removeAccount(it) }
        AccountManager.clearSelectedAccount()
        _authState.value = AuthState.Idle
    }

    // ── Auth flow ─────────────────────────────────────────────────────────

    private suspend fun doSignInFlow() {
        // 1. Device code al
        val deviceResp   = postForm(DEVICE_CODE_URL, mapOf(
            "client_id"     to CLIENT_ID,
            "scope"         to SCOPE,
            "response_type" to "device_code"
        ))
        val deviceCode      = deviceResp.str("device_code")
        val userCode        = deviceResp.str("user_code")
        val verificationUri = deviceResp.str("verification_uri")
        val intervalMs      = ((deviceResp["interval"] as? Double)?.toLong() ?: 5L) * 1000L

        // 2. UI'ya bildir → DeviceCodeLoginActivity WebView'ı açar
        _authState.value = AuthState.WaitingForUser(userCode, verificationUri)

        // 3. Token poll et
        val tokenResp    = pollForToken(deviceCode, intervalMs)
        val accessToken  = tokenResp.str("access_token")
        val refreshToken = tokenResp["refresh_token"] as? String ?: ""
        currentCoroutineContext().ensureActive()

        // 4. Xbox Live
        val xblToken = fetchXblToken(accessToken)
        currentCoroutineContext().ensureActive()

        // 5. XSTS
        val (xstsToken, userHash) = fetchXstsToken(xblToken)
        currentCoroutineContext().ensureActive()

        // 6. Minecraft Bedrock token
        val mcToken = fetchMinecraftToken(xstsToken, userHash)
        currentCoroutineContext().ensureActive()

        // 7. Gamertag
        val gamertag = fetchGamertag(xstsToken, userHash)

        // 8. Kaydet
        val account = SavedAccount(
            gamertag     = gamertag,
            refreshToken = refreshToken,
            mcToken      = mcToken,
            expireTimeMs = System.currentTimeMillis() + 6 * 3_600_000L
        )
        AccountManager.addAccount(account)
        AccountManager.selectAccount(account)

        withContext(Dispatchers.Main) {
            _authState.value = AuthState.Success(gamertag, mcToken)
        }
    }

    // ── Poll ──────────────────────────────────────────────────────────────

    private suspend fun pollForToken(deviceCode: String, intervalMs: Long): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(intervalMs.coerceAtLeast(MIN_INTERVAL_MS))
            currentCoroutineContext().ensureActive()

            val resp  = postForm(TOKEN_URL, mapOf(
                "client_id"   to CLIENT_ID,
                "grant_type"  to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to deviceCode
            ))
            val error = resp["error"] as? String
            when {
                error == null || resp.containsKey("access_token") -> return resp
                error == "authorization_pending" || error == "slow_down"  -> continue
                else -> error("Token hatası: $error — ${resp["error_description"]}")
            }
        }
        error("Giriş zaman aşımı — lütfen tekrar deneyin")
    }

    // ── Xbox / Minecraft adımları ─────────────────────────────────────────

    private fun fetchXblToken(msAccessToken: String): String {
        val body = """
            {
              "Properties": {
                "AuthMethod": "RPS",
                "SiteName"  : "user.auth.xboxlive.com",
                "RpsTicket" : "$msAccessToken"
              },
              "RelyingParty": "http://auth.xboxlive.com",
              "TokenType"   : "JWT"
            }
        """.trimIndent()
        val resp = postJson(XBL_URL, body)
        return resp.str("Token")
    }

    private fun fetchXstsToken(xblToken: String): Pair<String, String> {
        val body = """
            {
              "Properties": {
                "SandboxId" : "RETAIL",
                "UserTokens": ["$xblToken"]
              },
              "RelyingParty": "https://multiplayer.minecraft.net/",
              "TokenType"   : "JWT"
            }
        """.trimIndent()
        val resp = postJson(XSTS_URL, body)

        if (resp.containsKey("XErr")) {
            val xerr = (resp["XErr"] as? Double)?.toLong()
            val msg  = when (xerr) {
                2148916233L -> "Microsoft hesabınızın Xbox hesabı yok. Xbox.com'dan oluşturun."
                2148916238L -> "Çocuk hesabı — ebeveyn onayı gerekli."
                else        -> "XSTS hatası: $xerr"
            }
            error(msg)
        }

        val token    = resp.str("Token")
        @Suppress("UNCHECKED_CAST")
        val claims   = resp["DisplayClaims"] as? Map<String, Any?> ?: error("DisplayClaims yok")
        @Suppress("UNCHECKED_CAST")
        val xui      = (claims["xui"] as? List<Map<String, Any?>>)?.firstOrNull() ?: error("xui yok")
        val userHash = xui["uhs"] as? String ?: error("uhs yok")
        return token to userHash
    }

    private fun fetchMinecraftToken(xstsToken: String, userHash: String): String {
        // Generate an EC key pair (prime256v1 / P-256) — required by Minecraft Bedrock auth
        val keyPairGen = java.security.KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGen.generateKeyPair()

        // DER-encoded public key → Base64 (no wrapping)
        val publicKeyB64 = android.util.Base64.encodeToString(
            keyPair.public.encoded,
            android.util.Base64.NO_WRAP
        )

        val authHeader = "XBL3.0 x=$userHash;$xstsToken"
        val bodyStr = """{"identityPublicKey":"$publicKeyB64"}"""

        val req = Request.Builder()
            .url(MC_BEDROCK_URL)
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("Authorization",   authHeader)
            .header("Content-Type",    "application/json")
            .header("Accept",          "application/json")
            .header("User-Agent",      "MCPE/UWP")
            .header("client-version",  "1.21.0")
            .build()

        val text = http.newCall(req).execute().body?.string() ?: error("MC token yanıtı boş")
        val json = parseJson(text)
        return json["token"] as? String ?: error("MC token alınamadı: $text")
    }

    private fun fetchGamertag(xstsToken: String, userHash: String): String {
        return try {
            val req = Request.Builder()
                .url(PROFILE_URL)
                .header("Authorization",          "XBL3.0 x=$userHash;$xstsToken")
                .header("x-xbl-contract-version", "2")
                .header("Accept-Language",         "en-US")
                .build()
            val text = http.newCall(req).execute().body?.string() ?: return "Oyuncu"
            JSONObject(text)
                .getJSONObject("profileUsers")
                .getJSONArray("users")
                .getJSONObject(0)
                .getJSONArray("settings")
                .getJSONObject(0)
                .getString("value")
        } catch (e: Exception) {
            android.util.Log.w("MicrosoftAuth", "Gamertag alınamadı: ${e.message}")
            "Oyuncu"
        }
    }

    // ── HTTP yardımcıları ─────────────────────────────────────────────────

    private fun postForm(url: String, params: Map<String, String>): Map<String, Any?> {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val req  = Request.Builder().url(url).post(body).build()
        val text = http.newCall(req).execute().body?.string() ?: error("Boş yanıt: $url")
        return parseJson(text)
    }

    private fun postJson(
        url          : String,
        json         : String,
        extraHeaders : Map<String, String> = emptyMap()
    ): Map<String, Any?> {
        val body    = json.toRequestBody("application/json".toMediaType())
        val builder = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val text = http.newCall(builder.build()).execute().body?.string() ?: error("Boş yanıt: $url")
        return parseJson(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(text: String): Map<String, Any?> =
        try { gson.fromJson(text, Map::class.java) as Map<String, Any?> }
        catch (_: Exception) {
            // form-urlencoded fallback
            text.split("&").associate { part ->
                val eq = part.indexOf('=')
                if (eq < 0) part to null else part.substring(0, eq) to part.substring(eq + 1)
            }
        }

    // Null-safe String erişimi
    private fun Map<String, Any?>.str(key: String): String =
        this[key] as? String ?: error("'$key' alanı bulunamadı veya String değil")
}
