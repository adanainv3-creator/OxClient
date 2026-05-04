package com.oxclient.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

object MicrosoftAuthManager {

    private const val TAG = "MicrosoftAuth"

    private const val CLIENT_ID = "0000000048183522"
    private const val SCOPE     = "service::user.auth.xboxlive.com::MBI_SSL"

    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL       = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL         = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_MC_URL     = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val XSTS_XBX_URL    = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_BEDROCK_URL  = "https://multiplayer.minecraft.net/authentication"
    private const val PROFILE_URL     = "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag"

    private const val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    private const val MIN_INTERVAL_MS = 5_000L

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private var initialized = false

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        AccountManager.init(context)
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
        activeJob = coroutineScope.launch {
            runCatching { doSignInFlow() }
                .onFailure { e ->
                    if (e is CancellationException) {
                        _authState.value = AuthState.Idle
                    } else {
                        Log.e(TAG, "Sign-in failed", e)
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

    // ── Auth Flow ─────────────────────────────────────────────────────────

    private suspend fun doSignInFlow() {

        // 1. Device code
        val deviceResp = postForm(DEVICE_CODE_URL, mapOf(
            "client_id"     to CLIENT_ID,
            "scope"         to SCOPE,
            "response_type" to "device_code"
        ))
        val deviceCode      = deviceResp.str("device_code")
        val userCode        = deviceResp.str("user_code")
        val verificationUri = deviceResp.str("verification_uri")
        val intervalMs      = ((deviceResp["interval"] as? Double)?.toLong() ?: 5L) * 1000L

        // 2. UI'ya bildir
        _authState.value = AuthState.WaitingForUser(userCode, verificationUri)

        // 3. Token poll
        val tokenResp    = pollForToken(deviceCode, intervalMs)
        val accessToken  = tokenResp.str("access_token")
        val refreshToken = tokenResp["refresh_token"] as? String ?: ""
        currentCoroutineContext().ensureActive()

        // 4. Xbox Live token
        val xblResp = fetchXblToken(accessToken)
        val xblToken    = xblResp.first
        val xblGamertag = xblResp.second   // boş olabilir
        currentCoroutineContext().ensureActive()

        // 5. XSTS — Minecraft için
        val xstsMcResp = fetchXsts(xblToken, "https://multiplayer.minecraft.net/")
        val xstsMcToken  = xstsMcResp.first
        val xstsMcUhs    = xstsMcResp.second
        currentCoroutineContext().ensureActive()

        // 6. Minecraft Bedrock token (EC key pair ile)
        val mcToken = fetchMinecraftToken(xstsMcToken, xstsMcUhs)
        currentCoroutineContext().ensureActive()

        // 7. Gamertag — 3 kaynaktan sırayla dene
        val gamertag = resolveGamertag(xblToken, xblGamertag, accessToken)

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

    // ── Gamertag çözümleme ────────────────────────────────────────────────

    private fun resolveGamertag(xblToken: String, xblGamertag: String, accessToken: String): String {

        // Kaynak 1: XBL DisplayClaims → gtg
        if (xblGamertag.isNotBlank()) {
            Log.d(TAG, "Gamertag kaynağı: XBL gtg = $xblGamertag")
            return xblGamertag
        }

        // Kaynak 2: Xbox XSTS (http://xboxlive.com) DisplayClaims → gtg
        try {
            val xboxXsts = fetchXsts(xblToken, "http://xboxlive.com")
            val xboxToken = xboxXsts.first
            val xboxUhs   = xboxXsts.second

            // Profile API
            val profileReq = Request.Builder()
                .url(PROFILE_URL)
                .header("Authorization",          "XBL3.0 x=$xboxUhs;$xboxToken")
                .header("x-xbl-contract-version", "2")
                .header("Accept",                 "application/json")
                .build()
            val profileText = http.newCall(profileReq).execute().body?.string() ?: ""
            Log.d(TAG, "Profile API yanıtı: $profileText")

            if (profileText.isNotBlank()) {
                val root = JSONObject(profileText)
                // profileUsers.users[0].settings[id=Gamertag].value
                val usersArr = when {
                    root.has("profileUsers") ->
                        root.getJSONObject("profileUsers").getJSONArray("users")
                    root.has("users") -> root.getJSONArray("users")
                    else -> null
                }
                if (usersArr != null && usersArr.length() > 0) {
                    val settings = usersArr.getJSONObject(0).getJSONArray("settings")
                    for (i in 0 until settings.length()) {
                        val s = settings.getJSONObject(i)
                        if (s.optString("id") == "Gamertag") {
                            val gt = s.getString("value")
                            Log.d(TAG, "Gamertag kaynağı: Profile API = $gt")
                            return gt
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Xbox Profile API başarısız: ${e.message}")
        }

        // Kaynak 3: Microsoft access token JWT payload → unique_name
        try {
            val parts = accessToken.split(".")
            if (parts.size >= 2) {
                val padded = parts[1].let {
                    it + "=".repeat((4 - it.length % 4) % 4)
                }
                val payload = String(
                    Base64.decode(padded.replace('-', '+').replace('_', '/'), Base64.DEFAULT),
                    Charsets.UTF_8
                )
                Log.d(TAG, "JWT payload: $payload")
                val obj = JSONObject(payload)
                val raw = listOf("unique_name", "preferred_username", "email", "name")
                    .mapNotNull { obj.optString(it).takeIf { v -> v.isNotBlank() } }
                    .firstOrNull()
                if (raw != null) {
                    val gt = raw.substringBefore("@")
                    Log.d(TAG, "Gamertag kaynağı: JWT = $gt")
                    return gt
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JWT parse hatası: ${e.message}")
        }

        Log.w(TAG, "Gamertag bulunamadı, fallback: Oyuncu")
        return "Oyuncu"
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
                error == "authorization_pending" || error == "slow_down" -> continue
                else -> error("Token hatası: $error — ${resp["error_description"]}")
            }
        }
        error("Giriş zaman aşımı — lütfen tekrar deneyin")
    }

    // ── Xbox / XSTS ───────────────────────────────────────────────────────

    /** Returns Pair(xblToken, gamertag_or_empty) */
    private fun fetchXblToken(msAccessToken: String): Pair<String, String> {
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
        val token = resp.str("Token")

        @Suppress("UNCHECKED_CAST")
        val xui = ((resp["DisplayClaims"] as? Map<*, *>)?.get("xui") as? List<Map<String, Any?>>)
            ?.firstOrNull()
        val gamertag = xui?.get("gtg") as? String ?: ""
        Log.d(TAG, "XBL xui=$xui  gamertag='$gamertag'")
        return token to gamertag
    }

    /** Generic XSTS fetcher — returns Pair(token, uhs) */
    private fun fetchXsts(xblToken: String, relyingParty: String): Pair<String, String> {
        val body = """
            {
              "Properties": {
                "SandboxId" : "RETAIL",
                "UserTokens": ["$xblToken"]
              },
              "RelyingParty": "$relyingParty",
              "TokenType"   : "JWT"
            }
        """.trimIndent()
        val resp = postJson(XSTS_MC_URL, body)

        if (resp.containsKey("XErr")) {
            val xerr = (resp["XErr"] as? Double)?.toLong()
            val msg = when (xerr) {
                2148916233L -> "Xbox hesabı yok. Xbox.com'dan oluşturun."
                2148916238L -> "Çocuk hesabı — ebeveyn onayı gerekli."
                else        -> "XSTS hatası: $xerr"
            }
            error(msg)
        }

        val token = resp.str("Token")
        @Suppress("UNCHECKED_CAST")
        val xui   = ((resp["DisplayClaims"] as? Map<*, *>)?.get("xui") as? List<Map<String, Any?>>)
            ?.firstOrNull() ?: error("XSTS xui yok")
        val uhs   = xui["uhs"] as? String ?: error("XSTS uhs yok")
        return token to uhs
    }

    // ── Minecraft token ───────────────────────────────────────────────────

    private fun fetchMinecraftToken(xstsToken: String, userHash: String): String {
        // EC P-256 key pair oluştur — Bedrock auth zorunlu kılıyor
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGen.generateKeyPair()
        val publicKeyB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

        val bodyStr = """{"identityPublicKey":"$publicKeyB64"}"""
        val req = Request.Builder()
            .url(MC_BEDROCK_URL)
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("Authorization",  "XBL3.0 x=$userHash;$xstsToken")
            .header("Content-Type",   "application/json")
            .header("Accept",         "application/json")
            .header("User-Agent",     "MCPE/UWP")
            .header("client-version", "1.21.0")
            .build()

        val text = http.newCall(req).execute().body?.string() ?: error("MC token yanıtı boş")
        Log.d(TAG, "MC Bedrock yanıtı: ${text.take(200)}")
        val json = parseJson(text)

        // Bedrock → {"chain": ["jwt1", "jwt2"]}
        @Suppress("UNCHECKED_CAST")
        val chain = json["chain"] as? List<*>
        if (!chain.isNullOrEmpty()) return gson.toJson(chain)

        return json["token"] as? String ?: error("MC token alınamadı: $text")
    }

    // ── HTTP yardımcıları ─────────────────────────────────────────────────

    private fun postForm(url: String, params: Map<String, String>): Map<String, Any?> {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val text = http.newCall(Request.Builder().url(url).post(body).build())
            .execute().body?.string() ?: error("Boş yanıt: $url")
        return parseJson(text)
    }

    private fun postJson(url: String, json: String): Map<String, Any?> {
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json")
            .build()
        val text = http.newCall(req).execute().body?.string() ?: error("Boş yanıt: $url")
        return parseJson(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(text: String): Map<String, Any?> =
        try { gson.fromJson(text, Map::class.java) as Map<String, Any?> }
        catch (_: Exception) {
            text.split("&").associate { part ->
                val eq = part.indexOf('=')
                if (eq < 0) part to null else part.substring(0, eq) to part.substring(eq + 1)
            }
        }

    private fun Map<String, Any?>.str(key: String): String =
        this[key] as? String ?: error("'$key' alanı eksik")
}
