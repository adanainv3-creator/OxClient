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
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/**
 * MicrosoftAuthManager — WebView Authorization Code flow ile Microsoft/Xbox/Bedrock auth.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Auth Akışı (WebView OAuth):
 *   1. startSignIn()           → AuthState.WaitingForWebView → DeviceCodeLoginActivity açılır
 *   2. WebView redirect kodu yakalar → exchangeCodeForToken(code) çağrılır
 *   3. /oauth20_token.srf      → access_token + refresh_token
 *   4. XBL /user/authenticate  → Xbox Live token + uhs
 *   5. XSTS /xsts/authorize    → XSTS token (MC relying party)
 *   6. multiplayer.minecraft.net → Bedrock JWT chain (EC keypair ile)
 *   7. Chain kaydedilir → LoginPacketListener enjekte eder
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Düzeltmeler (v2):
 *  - fetchXblToken / fetchXsts: Gson LinkedTreeMap cast hatası giderildi;
 *    tüm XBL/XSTS yanıtları artık JSONObject ile parse ediliyor.
 *  - postJsonRaw: HTTP hata kodu (4xx/5xx) body ile birlikte loglanıyor.
 *  - fetchXblToken: RpsTicket "d=<token>" prefix korundu (authorization code flow için doğru).
 *  - fetchXsts: XErr yanıtı JSONObject üzerinden güvenle okunuyor.
 */
object MicrosoftAuthManager {

    private const val TAG = "MicrosoftAuth"

    // ── OAuth Endpoints ───────────────────────────────────────────────────
    private const val CLIENT_ID      = "0000000048183522"
    private const val REDIRECT_URI   = "https://login.live.com/oauth20_desktop.srf"
    private const val SCOPE          = "service::user.auth.xboxlive.com::MBI_SSL"
    private const val TOKEN_URL      = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL        = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL       = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_BEDROCK_URL = "https://multiplayer.minecraft.net/authentication"
    private const val PROFILE_URL    =
        "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag"

    // ── State ─────────────────────────────────────────────────────────────
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson  = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private var initialized = false

    // ── Init ──────────────────────────────────────────────────────────────
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        AccountManager.init(context)

        val saved = AccountManager.selectedAccount
        if (saved != null && saved.isRelayReady()) {
            _authState.value = AuthState.Success(saved.gamertag, saved.mcToken)
            Log.i(TAG, "Kaydedilen hesap yüklendi: ${saved.gamertag}")
        } else if (saved != null && saved.isExpired()) {
            Log.w(TAG, "Token süresi dolmuş, yenileniyor: ${saved.gamertag}")
            scope.launch { refreshTokenSilently(saved) }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun startSignIn() {
        if (_authState.value.isLoading) return
        _authState.value = AuthState.WaitingForWebView
        Log.i(TAG, "WebView giriş bekleniyor")
    }

    fun exchangeCodeForToken(code: String) {
        if (activeJob?.isActive == true) return
        _authState.value = AuthState.Loading
        activeJob = scope.launch {
            runCatching { doTokenExchangeFlow(code) }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        Log.e(TAG, "Token exchange başarısız", e)
                        _authState.value = AuthState.Error(e.message ?: "Giriş başarısız")
                    } else {
                        _authState.value = AuthState.Idle
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
        Log.i(TAG, "Çıkış yapıldı")
    }

    fun getActiveChainForRelay(): String? {
        val account = AccountManager.selectedAccount ?: return null
        if (account.isExpired()) {
            Log.w(TAG, "Token süresi dolmuş, arka planda yenileniyor")
            scope.launch { refreshTokenSilently(account) }
        }
        return account.mcToken.takeIf { it.isNotBlank() }
    }

    // ── Token Exchange Flow ───────────────────────────────────────────────

    private suspend fun doTokenExchangeFlow(code: String) {
        Log.d(TAG, "Authorization code ile token alınıyor...")

        // ── 1. Authorization Code → Access Token ──────────────────────────
        val tokenResp = postForm(TOKEN_URL, mapOf(
            "client_id"    to CLIENT_ID,
            "grant_type"   to "authorization_code",
            "code"         to code,
            "redirect_uri" to REDIRECT_URI,
            "scope"        to SCOPE
        ))

        val accessToken  = tokenResp.str("access_token")
        val refreshToken = tokenResp["refresh_token"] as? String ?: ""
        currentCoroutineContext().ensureActive()
        Log.d(TAG, "Access token alındı ✓")

        // ── 2. XBL Token ──────────────────────────────────────────────────
        val xblResult = fetchXblToken(accessToken)
        currentCoroutineContext().ensureActive()
        Log.d(TAG, "XBL token alındı ✓  uhs=${xblResult.userHash}  gtg=${xblResult.gamertag}")

        // ── 3. XSTS Token ─────────────────────────────────────────────────
        val xstsResult = fetchXsts(xblResult.token, "https://multiplayer.minecraft.net/")
        currentCoroutineContext().ensureActive()
        Log.d(TAG, "XSTS token alındı ✓  uhs=${xstsResult.userHash}  gtg=${xstsResult.gamertag}")

        // ── 4. Minecraft Bedrock Chain ────────────────────────────────────
        val mcResult = fetchMinecraftChain(xstsResult.token, xstsResult.userHash)
        currentCoroutineContext().ensureActive()

        // ── 5. Gamertag çözümle ───────────────────────────────────────────
        val gamertag = resolveGamertag(
            chainResult  = mcResult,
            xstsGamertag = xstsResult.gamertag,
            xblGamertag  = xblResult.gamertag,
            xblToken     = xblResult.token
        )
        Log.i(TAG, "Giriş tamamlandı: $gamertag")

        // ── 6. Kaydet ─────────────────────────────────────────────────────
        val account = SavedAccount(
            gamertag     = gamertag,
            refreshToken = refreshToken,
            mcToken      = mcResult.chainJson,
            expireTimeMs = System.currentTimeMillis() + 6 * 3_600_000L
        )
        AccountManager.addAccount(account)
        AccountManager.selectAccount(account)

        withContext(Dispatchers.Main) {
            _authState.value = AuthState.Success(gamertag, mcResult.chainJson)
        }
    }

    // ── Token Yenileme ────────────────────────────────────────────────────

    private suspend fun refreshTokenSilently(account: SavedAccount) {
        if (account.refreshToken.isBlank()) {
            Log.w(TAG, "Refresh token yok, manuel giriş gerekli")
            _authState.value = AuthState.Idle
            return
        }

        try {
            Log.d(TAG, "Token yenileniyor: ${account.gamertag}")
            val tokenResp = postForm(TOKEN_URL, mapOf(
                "client_id"     to CLIENT_ID,
                "grant_type"    to "refresh_token",
                "refresh_token" to account.refreshToken,
                "redirect_uri"  to REDIRECT_URI,
                "scope"         to SCOPE
            ))
            val accessToken  = tokenResp.str("access_token")
            val refreshToken = tokenResp["refresh_token"] as? String ?: account.refreshToken

            val xblResult  = fetchXblToken(accessToken)
            val xstsResult = fetchXsts(xblResult.token, "https://multiplayer.minecraft.net/")
            val mcResult   = fetchMinecraftChain(xstsResult.token, xstsResult.userHash)

            val newExpire = System.currentTimeMillis() + 6 * 3_600_000L
            AccountManager.refreshAccount(account.gamertag, mcResult.chainJson, newExpire)
            AccountManager.selectedAccount?.let { updated ->
                AccountManager.addAccount(updated.copy(refreshToken = refreshToken))
            }

            _authState.value = AuthState.Success(account.gamertag, mcResult.chainJson)
            Log.i(TAG, "Token yenilendi: ${account.gamertag}")

        } catch (e: Exception) {
            Log.e(TAG, "Token yenileme başarısız: ${e.message}", e)
            _authState.value = AuthState.Error("Token yenileme başarısız: ${e.message}")
        }
    }

    // ── Auth Aşamaları ────────────────────────────────────────────────────

    /**
     * XBL token alır.
     *
     * DÜZELTME: Gson ile parse yerine JSONObject kullanılıyor.
     * Gson, nested objeleri LinkedTreeMap'e dönüştürür; Kotlin "as? Map<*, *>" cast'ı
     * LinkedTreeMap'i kabul etse de iç List<Map> cast'ı runtime'da ClassCastException
     * veya silent null üretiyordu.
     *
     * RpsTicket "d=<token>" prefix'i authorization code flow için zorunlu.
     * (Device code / consumer MSA tokenları için de aynı prefix gerekir.)
     */
    private fun fetchXblToken(accessToken: String): XblAuthResult {
        val bodyJson = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName",   "user.auth.xboxlive.com")
                put("RpsTicket",  accessToken)   // legacy MSA/MBI_SSL scope: prefix YOK
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType",    "JWT")
        }.toString()

        val respText = postJsonRaw(XBL_URL, bodyJson)
        Log.d(TAG, "XBL yanıt (ilk 300): ${respText.take(300)}")

        val resp = JSONObject(respText)

        // Hata kontrolü
        if (resp.has("error")) {
            val err = resp.optString("error")
            val desc = resp.optString("error_description", "")
            error("XBL hatası: $err — $desc")
        }

        val token = resp.optString("Token").takeIf { it.isNotBlank() }
            ?: error("XBL Token alanı boş — yanıt: ${respText.take(400)}")

        val xui      = resp.optJSONObject("DisplayClaims")
            ?.optJSONArray("xui")
            ?.optJSONObject(0)

        val uhs      = xui?.optString("uhs") ?: ""
        val gamertag = xui?.optString("gtg") ?: ""

        Log.d(TAG, "XBL XUI: uhs=$uhs  gtg=$gamertag")
        return XblAuthResult(token, uhs, gamertag)
    }

    /**
     * XSTS token alır.
     *
     * DÜZELTME: Gson parse → JSONObject parse.
     * XErr alanı Gson'da Double olarak geliyordu (Long overflow riski);
     * JSONObject.optLong() doğru okur.
     */
    private fun fetchXsts(xblToken: String, relyingParty: String): XblAuthResult {
        val bodyJson = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId",  "RETAIL")
                put("UserTokens", JSONArray().apply { put(xblToken) })
            })
            put("RelyingParty", relyingParty)
            put("TokenType",    "JWT")
        }.toString()

        val respText = postJsonRaw(XSTS_URL, bodyJson)
        Log.d(TAG, "XSTS yanıt (ilk 300): ${respText.take(300)}")

        val resp = JSONObject(respText)

        // XSTS hata yanıtı: { "XErr": 2148916233, "Message": "...", "Redirect": "..." }
        if (resp.has("XErr")) {
            val xerr = resp.optLong("XErr")
            val msg = when (xerr) {
                2148916233L -> "Xbox hesabı yok. Lütfen xbox.com'dan oluşturun."
                2148916238L -> "Çocuk hesabı — ebeveyn onayı gerekli."
                else        -> "XSTS hatası: $xerr — ${resp.optString("Message")}"
            }
            error(msg)
        }

        val token = resp.optString("Token").takeIf { it.isNotBlank() }
            ?: error("XSTS Token alanı boş — yanıt: ${respText.take(400)}")

        val xui      = resp.optJSONObject("DisplayClaims")
            ?.optJSONArray("xui")
            ?.optJSONObject(0)
            ?: error("XSTS DisplayClaims.xui[0] yok — yanıt: ${respText.take(400)}")

        val uhs      = xui.optString("uhs").takeIf { it.isNotBlank() }
            ?: error("XSTS uhs boş")
        val gamertag = xui.optString("gtg") ?: ""

        Log.d(TAG, "XSTS XUI: uhs=$uhs  gtg=$gamertag")
        return XblAuthResult(token, uhs, gamertag)
    }

    // ── Minecraft Chain ───────────────────────────────────────────────────

    private fun fetchMinecraftChain(xstsToken: String, userHash: String): McAuthResult {
        val keyGen  = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyGen.generateKeyPair()
        val pubKey  = keyPair.public  as ECPublicKey
        val privKey = keyPair.private as ECPrivateKey
        val pubKeyB64 = Base64.encodeToString(
            pubKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val deviceJwt = buildDeviceJwt(privKey, pubKeyB64)

        val requestBody = JSONObject().apply {
            put("identityPublicKey", pubKeyB64)
            put("chain", JSONArray().apply { put(deviceJwt) })
        }.toString()

        val req = Request.Builder()
            .url(MC_BEDROCK_URL)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Authorization",  "XBL3.0 x=$userHash;$xstsToken")
            .header("Content-Type",   "application/json")
            .header("Accept",         "application/json")
            .header("User-Agent",     "MCPE/UWP")
            .header("client-version", "1.21.80")
            .build()

        val responseText = http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                error("MC /authentication HTTP ${resp.code} — $body")
            }
            body.ifBlank { error("MC /authentication yanıtı boş") }
        }

        Log.d(TAG, "MC /authentication yanıtı: ${responseText.take(200)}…")

        val json             = JSONObject(responseText)
        val serverChainArray = json.optJSONArray("chain")

        if (serverChainArray != null && serverChainArray.length() > 0) {
            val fullChain = buildList {
                add(deviceJwt)
                for (i in 0 until serverChainArray.length()) add(serverChainArray.getString(i))
            }
            val chainWrapper = JSONObject().apply {
                put("chain", JSONArray(fullChain))
            }.toString()

            val gamertag = extractGamertagFromJwtList(fullChain)
            Log.i(TAG, "MC chain alındı: ${fullChain.size} JWT, gamertag=$gamertag")
            return McAuthResult(chainWrapper, gamertag)
        }

        val token = json.optString("token").takeIf { it.isNotBlank() }
            ?: error("MC token alınamadı: $responseText")

        Log.w(TAG, "MC yanıtında chain yok, ham token kullanılıyor")
        val fallbackChain = JSONObject().apply {
            put("chain", JSONArray().apply { put(deviceJwt); put(token) })
        }.toString()
        return McAuthResult(fallbackChain, "")
    }

    // ── Gamertag çözümleme ────────────────────────────────────────────────

    private fun resolveGamertag(
        chainResult: McAuthResult,
        xstsGamertag: String,
        xblGamertag: String,
        xblToken: String
    ): String {
        // Kaynak 1: MC chain extraData.displayName (en güvenilir)
        if (chainResult.gamertag.isNotBlank()) {
            Log.d(TAG, "Gamertag kaynağı: MC chain → ${chainResult.gamertag}")
            return chainResult.gamertag
        }
        // Kaynak 2: XSTS DisplayClaims.xui[0].gtg
        if (xstsGamertag.isNotBlank()) {
            Log.d(TAG, "Gamertag kaynağı: XSTS gtg → $xstsGamertag")
            return xstsGamertag
        }
        // Kaynak 3: XBL DisplayClaims.xui[0].gtg
        if (xblGamertag.isNotBlank()) {
            Log.d(TAG, "Gamertag kaynağı: XBL gtg → $xblGamertag")
            return xblGamertag
        }

        // Kaynak 4: Xbox Profile API
        return try {
            val xboxXsts = fetchXsts(xblToken, "http://xboxlive.com")
            val profileReq = Request.Builder()
                .url(PROFILE_URL)
                .header("Authorization",          "XBL3.0 x=${xboxXsts.userHash};${xboxXsts.token}")
                .header("x-xbl-contract-version", "2")
                .header("Accept",                 "application/json")
                .build()

            val body     = http.newCall(profileReq).execute().use { it.body?.string() } ?: ""
            val root     = JSONObject(body)
            val users    = root.optJSONArray("profileUsers")
            val settings = users?.optJSONObject(0)?.optJSONArray("settings")
            if (settings != null) {
                for (i in 0 until settings.length()) {
                    val s = settings.getJSONObject(i)
                    if (s.optString("id") == "Gamertag") {
                        val gt = s.optString("value")
                        Log.d(TAG, "Gamertag kaynağı: Profile API → $gt")
                        return gt
                    }
                }
            }
            Log.w(TAG, "Profile API'den gamertag alınamadı")
            "OxPlayer"
        } catch (e: Exception) {
            Log.w(TAG, "Profile API hatası: ${e.message}")
            "OxPlayer"
        }
    }

    // ── JWT Yardımcıları ──────────────────────────────────────────────────

    private fun buildDeviceJwt(privateKey: ECPrivateKey, pubKeyB64: String): String {
        val now     = System.currentTimeMillis() / 1000L
        val header  = b64Url("""{"alg":"ES256","x5u":"$pubKeyB64"}""".toByteArray())
        val payload = b64Url(
            ("""{"certificateAuthority":true,"identityPublicKey":"$pubKeyB64",""" +
             """"exp":${now + 86400},"nbf":${now - 1},"iat":$now,"iss":"Minecraft"}""")
                .toByteArray()
        )
        val sigInput = "$header.$payload"
        val signer   = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(sigInput.toByteArray(Charsets.US_ASCII))
        val sig = derToRaw(signer.sign())
        return "$sigInput.${b64Url(sig)}"
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        check(der[i] == 0x02.toByte()) { "DER: r tag bekleniyor" }
        i++
        val rLen = der[i++].toInt() and 0xFF
        val r    = der.copyOfRange(i, i + rLen); i += rLen
        check(der[i] == 0x02.toByte()) { "DER: s tag bekleniyor" }
        i++
        val sLen = der[i++].toInt() and 0xFF
        val s    = der.copyOfRange(i, i + sLen)
        return padOrTrim(BigInteger(1, r).toByteArray(), 32) +
               padOrTrim(BigInteger(1, s).toByteArray(), 32)
    }

    private fun padOrTrim(b: ByteArray, size: Int) = when {
        b.size == size -> b
        b.size > size  -> b.copyOfRange(b.size - size, b.size)
        else           -> ByteArray(size - b.size) + b
    }

    private fun b64Url(data: ByteArray) =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun extractGamertagFromJwtList(jwts: List<String>): String {
        for (jwt in jwts) {
            try {
                val parts  = jwt.split(".")
                if (parts.size < 2) continue
                val padded = parts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
                    .replace('-', '+').replace('_', '/')
                val payload = JSONObject(
                    String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                )
                val name = payload.optJSONObject("extraData")?.optString("displayName")
                    ?.takeIf { it.isNotBlank() }
                    ?: payload.optString("displayName").takeIf { it.isNotBlank() }
                    ?: payload.optString("username").takeIf { it.isNotBlank() }
                if (name != null) return name
            } catch (_: Exception) {}
        }
        return ""
    }

    // ── HTTP Yardımcıları ─────────────────────────────────────────────────

    /**
     * Form POST — token endpoint için.
     * Yanıt her zaman form-urlencoded veya JSON olabilir; ikisi de desteklenir.
     */
    private fun postForm(url: String, params: Map<String, String>): Map<String, Any?> {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val text = http.newCall(Request.Builder().url(url).post(body).build())
            .execute().use { resp ->
                val b = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.e(TAG, "postForm HTTP ${resp.code} [$url]: $b")
                    error("HTTP ${resp.code}: $b")
                }
                b
            }
        return parseJsonOrForm(text)
    }

    /**
     * JSON POST — XBL/XSTS için ham string döndürür.
     * fetchXblToken ve fetchXsts JSONObject ile parse eder (Gson LinkedTreeMap cast sorunu yok).
     *
     * DÜZELTME: Başarısız HTTP yanıtları artık body ile birlikte loglanıyor ve
     * anlaşılır hata mesajı fırlatılıyor; önceden body yutulup parse hatası çıkıyordu.
     */
    private fun postJsonRaw(url: String, json: String): String {
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json")
            .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            Log.d(TAG, "postJsonRaw HTTP ${resp.code} [$url]")
            // XSTS 401 body'sinde XErr olabilir — bu durumda caller parse eder
            val isXstsError = text.contains("XErr")
            if (!resp.isSuccessful && !isXstsError) {
                Log.e(TAG, "postJsonRaw HTTP ${resp.code} [$url]: ${text.take(400)}")
                error("HTTP ${resp.code}: ${text.take(300)}")
            }
            text
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonOrForm(text: String): Map<String, Any?> =
        try {
            gson.fromJson(text, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            text.split("&").associate { part ->
                val eq = part.indexOf('=')
                if (eq < 0) part to null else part.substring(0, eq) to part.substring(eq + 1)
            }
        }

    private fun Map<String, Any?>.str(key: String): String =
        this[key] as? String ?: error("'$key' alanı bulunamadı — yanıt: ${this.keys}")
}
