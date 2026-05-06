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

    private const val CLIENT_ID      = "0000000048183522"
    private const val SCOPE          = "service::user.auth.xboxlive.com::MBI_SSL"
    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL       = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL         = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_MC_URL     = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_BEDROCK_URL  = "https://multiplayer.minecraft.net/authentication"
    private const val PROFILE_URL     = "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag"

    private const val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    private const val MIN_INTERVAL_MS = 5_000L

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30,  TimeUnit.SECONDS)
        .build()

    private val gson         = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob    : Job? = null
    private var initialized  = false

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        AccountManager.init(context)
        val saved = AccountManager.selectedAccount
        if (saved != null) _authState.value = AuthState.Success(saved.gamertag, saved.mcToken)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startSignIn() {
        if (_authState.value is AuthState.Loading ||
            _authState.value is AuthState.WaitingForUser) return

        _authState.value = AuthState.Loading
        activeJob = coroutineScope.launch {
            runCatching { doSignInFlow() }
                .onFailure { e ->
                    if (e is CancellationException) _authState.value = AuthState.Idle
                    else {
                        Log.e(TAG, "Sign-in failed", e)
                        _authState.value = AuthState.Error(e.message ?: "Giriş başarısız")
                    }
                }
        }
    }

    fun cancelSignIn() {
        activeJob?.cancel(); activeJob = null
        _authState.value = AuthState.Idle
    }

    fun signOut() {
        AccountManager.selectedAccount?.let { AccountManager.removeAccount(it) }
        AccountManager.clearSelectedAccount()
        _authState.value = AuthState.Idle
    }

    /** Mevcut MC token'ını döner — relay için kullanılır */
    fun currentMcToken(): String? =
        (_authState.value as? AuthState.Success)?.mcToken
            ?: AccountManager.selectedAccount?.mcToken

    fun currentGamertag(): String? =
        (_authState.value as? AuthState.Success)?.gamertag
            ?: AccountManager.selectedAccount?.gamertag

    // ── Auth Flow ─────────────────────────────────────────────────────────────

    private suspend fun doSignInFlow() {
        val deviceResp      = postForm(DEVICE_CODE_URL, mapOf(
            "client_id"     to CLIENT_ID,
            "scope"         to SCOPE,
            "response_type" to "device_code"
        ))
        val deviceCode      = deviceResp.str("device_code")
        val userCode        = deviceResp.str("user_code")
        val verificationUri = deviceResp.str("verification_uri")
        val intervalMs      = ((deviceResp["interval"] as? Double)?.toLong() ?: 5L) * 1000L

        _authState.value = AuthState.WaitingForUser(userCode, verificationUri)

        val tokenResp   = pollForToken(deviceCode, intervalMs)
        val accessToken = tokenResp.str("access_token")
        val refreshToken = tokenResp["refresh_token"] as? String ?: ""
        currentCoroutineContext().ensureActive()

        val xblResp     = fetchXblToken(accessToken)
        val xblToken    = xblResp.first
        val xblGamertag = xblResp.second
        currentCoroutineContext().ensureActive()

        val xstsMcResp  = fetchXsts(xblToken, "https://multiplayer.minecraft.net/")
        val xstsMcToken = xstsMcResp.first
        val xstsMcUhs   = xstsMcResp.second
        val xstsGamertag = xstsMcResp.third
        currentCoroutineContext().ensureActive()

        val (mcToken, chainGamertag) = fetchMinecraftToken(xstsMcToken, xstsMcUhs)
        currentCoroutineContext().ensureActive()

        val gamertag = when {
            chainGamertag.isNotBlank() -> chainGamertag
            else -> resolveGamertag(xblToken, xblGamertag, xstsGamertag, accessToken)
        }

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

    private fun resolveGamertag(
        xblToken: String, xblGamertag: String, xstsGamertag: String, accessToken: String
    ): String {
        if (xstsGamertag.isNotBlank()) return xstsGamertag
        if (xblGamertag.isNotBlank())  return xblGamertag

        return try {
            val xboxXsts  = fetchXsts(xblToken, "http://xboxlive.com")
            val xboxToken = xboxXsts.first
            val xboxUhs   = xboxXsts.second

            val req = Request.Builder()
                .url(PROFILE_URL)
                .header("Authorization",          "XBL3.0 x=$xboxUhs;$xboxToken")
                .header("x-xbl-contract-version", "2")
                .header("Accept",                 "application/json")
                .build()

            val text = http.newCall(req).execute().body?.string() ?: return "OxPlayer"
            val obj  = JSONObject(text)
            val settings = obj.optJSONObject("profileUsers")
                ?.optJSONArray("users")
                ?.optJSONObject(0)
                ?.optJSONArray("settings")
            val gamertag = (0 until (settings?.length() ?: 0))
                .map { settings!!.getJSONObject(it) }
                .firstOrNull { it.optString("id") == "Gamertag" }
                ?.optString("value")
                ?.takeIf { it.isNotBlank() }
            gamertag ?: "OxPlayer"
        } catch (e: Exception) {
            Log.w(TAG, "Gamertag çözümleme başarısız: ${e.message}")
            "OxPlayer"
        }
    }

    private fun pollForToken(deviceCode: String, intervalMs: Long): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        val wait     = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(wait)
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
        error("Giriş zaman aşımı")
    }

    private fun fetchXblToken(msAccessToken: String): Pair<String, String> {
        val body = """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"$msAccessToken"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
        val resp = postJson(XBL_URL, body)
        val token = resp.str("Token")

        @Suppress("UNCHECKED_CAST")
        val xui = ((resp["DisplayClaims"] as? Map<*, *>)?.get("xui") as? List<Map<String, Any?>>)?.firstOrNull()
        val gamertag = (xui?.get("gtg") as? String) ?: (xui?.get("Gamertag") as? String) ?: ""
        return token to gamertag
    }

    private fun fetchXsts(xblToken: String, relyingParty: String): Triple<String, String, String> {
        val body = """{"Properties":{"SandboxId":"RETAIL","UserTokens":["$xblToken"]},"RelyingParty":"$relyingParty","TokenType":"JWT"}"""
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
        val xui = ((resp["DisplayClaims"] as? Map<*, *>)?.get("xui") as? List<Map<String, Any?>>)?.firstOrNull()
            ?: error("XSTS xui yok")

        val uhs      = xui["uhs"] as? String ?: error("XSTS uhs yok")
        val gamertag = (xui["gtg"] as? String) ?: (xui["Gamertag"] as? String) ?: ""
        return Triple(token, uhs, gamertag)
    }

    private fun fetchMinecraftToken(xstsToken: String, userHash: String): Pair<String, String> {
        val keyPair     = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pubKeyB64   = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val bodyStr     = """{"identityPublicKey":"$pubKeyB64"}"""

        val req = Request.Builder()
            .url(MC_BEDROCK_URL)
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("Authorization",  "XBL3.0 x=$userHash;$xstsToken")
            .header("Content-Type",   "application/json")
            .header("Accept",         "application/json")
            .header("User-Agent",     "MCPE/UWP")
            .header("client-version", "1.21.50")
            .build()

        val text  = http.newCall(req).execute().body?.string() ?: error("MC token yanıtı boş")
        val json  = parseJson(text)

        @Suppress("UNCHECKED_CAST")
        val chain = json["chain"] as? List<*>
        if (!chain.isNullOrEmpty()) {
            val mcToken  = gson.toJson(chain)
            val gamertag = extractGamertagFromChain(chain)
            return mcToken to gamertag
        }

        val token = json["token"] as? String ?: error("MC token alınamadı: $text")
        return token to ""
    }

    private fun extractGamertagFromChain(chain: List<*>): String {
        for (jwt in chain) {
            try {
                val parts  = (jwt as? String)?.split(".") ?: continue
                if (parts.size < 2) continue
                val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
                val payload = String(
                    Base64.decode(padded.replace('-', '+').replace('_', '/'), Base64.DEFAULT),
                    Charsets.UTF_8
                )
                val obj = JSONObject(payload)
                if (obj.has("extraData")) {
                    val dn = obj.getJSONObject("extraData").optString("displayName")
                    if (dn.isNotBlank()) return dn
                }
                val direct = obj.optString("displayName").takeIf { it.isNotBlank() }
                    ?: obj.optString("username").takeIf { it.isNotBlank() }
                if (direct != null) return direct
            } catch (_: Exception) {}
        }
        return ""
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun postForm(url: String, params: Map<String, String>): Map<String, Any?> {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val text = http.newCall(Request.Builder().url(url).post(body).build()).execute().body?.string()
            ?: error("Boş yanıt: $url")
        return parseJson(text)
    }

    private fun postJson(url: String, json: String): Map<String, Any?> {
        val body = json.toRequestBody("application/json".toMediaType())
        val text = http.newCall(Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json").build()).execute().body?.string()
            ?: error("Boş yanıt: $url")
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
