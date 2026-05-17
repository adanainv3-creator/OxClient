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
 * MicrosoftAuthManager — OxClient relay için tam Microsoft/Xbox/Bedrock auth akışı.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Auth Akışı (Device Code OAuth):
 *   1. /oauth20_connect.srf       → device_code + user_code üret
 *   2. /oauth20_token.srf         → access_token + refresh_token (polling)
 *   3. XBL /user/authenticate     → Xbox Live token + uhs
 *   4. XSTS /xsts/authorize       → XSTS token (MC relying party)
 *   5. multiplayer.minecraft.net  → Bedrock JWT chain (EC keypair ile)
 *   6. Kaydedilen chain, relay'in LoginPacketListener'ı tarafından
 *      LoginPacket.chain'e enjekte edilir → online-mode sunucular dahil çalışır
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Relay entegrasyonu:
 *   - [authState] = [AuthState.Success] → [AuthState.Success.mcToken] relay'e gider
 *   - Token süresi dolunca [refreshTokenSilently] otomatik yenileme yapar
 *   - [getActiveChainForRelay] → LoginPacketListener'ın tek giriş noktası
 */
object MicrosoftAuthManager {

    private const val TAG = "MicrosoftAuth"

    // ── OAuth Endpoints ───────────────────────────────────────────────────

    private const val CLIENT_ID       = "0000000048183522"
    private const val SCOPE           = "service::user.auth.xboxlive.com::MBI_SSL"
    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL       = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL         = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL        = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_BEDROCK_URL  = "https://multiplayer.minecraft.net/authentication"
    private const val PROFILE_URL     =
        "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag"

    private const val POLL_TIMEOUT_MS  = 5 * 60 * 1000L
    private const val MIN_INTERVAL_MS  = 5_000L

    // ── State ─────────────────────────────────────────────────────────────

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson        = Gson()
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        _authState.value = AuthState.Loading
        activeJob = scope.launch {
            runCatching { doFullSignInFlow() }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        Log.e(TAG, "Sign-in başarısız", e)
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

    /**
     * Relay'in LoginPacketListener'ı için aktif JWT chain'i döndürür.
     * Token süresi dolduysa otomatik yenileme tetiklenir.
     *
     * @return Bedrock JWT chain JSON string veya null (giriş yapılmamış)
     */
    fun getActiveChainForRelay(): String? {
        val account = AccountManager.selectedAccount ?: return null

        if (account.isExpired()) {
            Log.w(TAG, "Token süresi dolmuş, arka planda yenileniyor")
            scope.launch { refreshTokenSilently(account) }
            // Eski token'ı yine de döndür (relay akışı kesmez)
        }

        return account.mcToken.takeIf { it.isNotBlank() }
    }

    // ── Full Sign-In Flow ─────────────────────────────────────────────────

    private suspend fun doFullSignInFlow() {
        // ── 1. Device Code ────────────────────────────────────────────────
        val deviceResp  = postForm(DEVICE_CODE_URL, mapOf(
            "client_id"     to CLIENT_ID,
            "scope"         to SCOPE,
            "response_type" to "device_code"
        ))
        val deviceCode      = deviceResp.str("device_code")
        val userCode        = deviceResp.str("user_code")
        val verificationUri = deviceResp.str("verification_uri")
        val intervalMs      = ((deviceResp["interval"] as? Double)?.toLong() ?: 5L) * 1000L

        Log.i(TAG, "Device code üretildi. UserCode=$userCode, URL=$verificationUri")
        _authState.value = AuthState.WaitingForUser(userCode, verificationUri)

        // ── 2. Token Polling ──────────────────────────────────────────────
        val tokenResp    = pollForToken(deviceCode, intervalMs)
        val accessToken  = tokenResp.str("access_token")
        val refreshToken = tokenResp["refresh_token"] as? String ?: ""
        currentCoroutineContext().ensureActive()
        Log.d(TAG, "Access token alındı")

        // ── 3. XBL Token ──────────────────────────────────────────────────
        val xblResult = fetchXblToken(accessToken)
        currentCoroutineContext().ensureActive()
        Log.d(TAG, "XBL token alındı: uhs=${xblResult.userHash}")

        // ── 4. XSTS Token (Minecraft relying party) ───────────────────────
        val xstsResult = fetchXsts(xblResult.token, "https://multiplayer.minecraft.net/")
        currentCoroutineContext().ensureActive()
        Log.d(TAG, "XSTS token alındı: gamertag=${xstsResult.gamertag}")

        // ── 5. Minecraft Bedrock Chain ────────────────────────────────────
        val mcResult = fetchMinecraftChain(xstsResult.token, xstsResult.userHash)
        currentCoroutineContext().ensureActive()

        // ── 6. Gamertag çözümle ───────────────────────────────────────────
        val gamertag = resolveGamertag(
            chainResult  = mcResult,
            xstsGamertag = xstsResult.gamertag,
            xblGamertag  = xblResult.gamertag,
            xblToken     = xblResult.token
        )
        Log.i(TAG, "Giriş tamamlandı: $gamertag")

        // ── 7. Kaydet ─────────────────────────────────────────────────────
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
            val tokenResp    = postForm(TOKEN_URL, mapOf(
                "client_id"     to CLIENT_ID,
                "grant_type"    to "refresh_token",
                "refresh_token" to account.refreshToken,
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

    private suspend fun pollForToken(
        deviceCode: String,
        intervalMs: Long
    ): Map<String, Any?> {
        val effectiveInterval = maxOf(intervalMs, MIN_INTERVAL_MS)
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(effectiveInterval)

            // Ağ hatası olursa sessizce devam et — bağlantı geçici kopmuş olabilir
            val resp = try {
                postForm(TOKEN_URL, mapOf(
                    "client_id"   to CLIENT_ID,
                    "grant_type"  to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to deviceCode
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Polling ağ hatası (yeniden denenecek): ${e.message}")
                continue // ağ hatası → bir sonraki döngüde tekrar dene
            }

            when {
                resp["access_token"] != null             -> return resp
                resp["error"] == "authorization_pending" -> Log.v(TAG, "Kullanıcı henüz giriş yapmadı")
                resp["error"] == "expired_token"         -> error("Device code süresi doldu")
                resp["error"] == "access_denied"         -> error("Kullanıcı erişimi reddetti")
                else -> Log.v(TAG, "Polling yanıtı: ${resp["error"]}")
            }
        }
        error("Device code zaman aşımı (${POLL_TIMEOUT_MS / 1000}s)")
    }

    /**
     * Xbox Live (XBL) token alır.
     * @return [XblAuthResult] token + userHash + gamertag
     */
    private fun fetchXblToken(accessToken: String): XblAuthResult {
        val body = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName",   "user.auth.xboxlive.com")
                put("RpsTicket",  "d=$accessToken")
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType",    "JWT")
        }

        val resp = postJson(XBL_URL, body.toString())
        val token = resp.str("Token")

        val displayClaims = resp["DisplayClaims"] as? Map<*, *>
        val xuiList       = displayClaims?.get("xui") as? List<*>
        val xui           = xuiList?.firstOrNull() as? Map<*, *>
        val uhs           = xui?.get("uhs") as? String ?: ""
        val gamertag      = xui?.get("gtg") as? String ?: ""

        Log.d(TAG, "XBL → uhs=$uhs, gtg=$gamertag")
        return XblAuthResult(token, uhs, gamertag)
    }

    /**
     * XSTS token alır.
     * @param relyingParty  "https://multiplayer.minecraft.net/" veya "http://xboxlive.com"
     */
    private fun fetchXsts(xblToken: String, relyingParty: String): XblAuthResult {
        val body = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId",    "RETAIL")
                put("UserTokens",   JSONArray().apply { put(xblToken) })
            })
            put("RelyingParty", relyingParty)
            put("TokenType",    "JWT")
        }

        val resp  = postJson(XSTS_URL, body.toString())
        val token = resp.str("Token")

        val displayClaims = resp["DisplayClaims"] as? Map<*, *>
        val xuiList       = displayClaims?.get("xui") as? List<*>
        val xui           = xuiList?.firstOrNull() as? Map<*, *>
        val uhs           = xui?.get("uhs") as? String ?: ""
        val gamertag      = xui?.get("gtg") as? String ?: ""

        return XblAuthResult(token, uhs, gamertag)
    }

    /**
     * Minecraft Bedrock JWT chain'i alır.
     *
     * İşlem adımları:
     *   a) EC keypair üret (secp256r1 / P-256)
     *   b) Public key'den cihaz JWT'si imzala
     *   c) /authentication endpointine gönder
     *   d) Dönen chain'e cihaz JWT'sini ekle → tam zincir
     *   e) Tam zinciri JSON array string olarak döndür
     *
     * Dönen zincir formatı (relay için):
     *   {"chain": ["<cihaz_jwt>", "<mojang_jwt1>", "<mojang_jwt2>"]}
     *
     * @return [McAuthResult] chainJson + gamertag
     */
    private fun fetchMinecraftChain(xstsToken: String, userHash: String): McAuthResult {
        // a) EC keypair
        val keyGen  = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyGen.generateKeyPair()
        val pubKey  = keyPair.public  as ECPublicKey
        val privKey = keyPair.private as ECPrivateKey
        val pubKeyB64 = Base64.encodeToString(
            pubKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        // b) Cihaz JWT'si
        val deviceJwt = buildDeviceJwt(privKey, pubKeyB64)

        // c) İstek gönder
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
            if (!resp.isSuccessful) error("MC /authentication HTTP ${resp.code}")
            resp.body?.string() ?: error("MC /authentication yanıtı boş")
        }

        Log.d(TAG, "MC /authentication yanıtı: ${responseText.take(200)}…")

        // d) Chain parse et
        val json = JSONObject(responseText)

        // Yanıt formatı: {"chain": [...]} veya {"token": "..."}
        val serverChainArray = json.optJSONArray("chain")

        if (serverChainArray != null && serverChainArray.length() > 0) {
            // Mojang chain'ine cihaz JWT'sini öne ekle
            val fullChain = buildList {
                add(deviceJwt)
                for (i in 0 until serverChainArray.length()) {
                    add(serverChainArray.getString(i))
                }
            }
            val chainWrapper = JSONObject().apply {
                put("chain", JSONArray(fullChain))
            }.toString()

            val gamertag = extractGamertagFromJwtList(fullChain)
            Log.i(TAG, "MC chain alındı: ${fullChain.size} JWT, gamertag=$gamertag")
            return McAuthResult(chainWrapper, gamertag)
        }

        // Fallback: eski format "token"
        val token = json.optString("token").takeIf { it.isNotBlank() }
            ?: error("MC token alınamadı: $responseText")

        Log.w(TAG, "MC yanıtında chain yok, ham token kullanılıyor")
        val fallbackChain = JSONObject().apply {
            put("chain", JSONArray().apply {
                put(deviceJwt)
                put(token)
            })
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
        if (chainResult.gamertag.isNotBlank()) return chainResult.gamertag
        if (xstsGamertag.isNotBlank())         return xstsGamertag
        if (xblGamertag.isNotBlank())           return xblGamertag

        // Son çare: Xbox Profile API
        return try {
            val xboxXsts = fetchXsts(xblToken, "http://xboxlive.com")
            val profileReq = Request.Builder()
                .url(PROFILE_URL)
                .header("Authorization",          "XBL3.0 x=${xboxXsts.userHash};${xboxXsts.token}")
                .header("x-xbl-contract-version", "2")
                .header("Accept",                 "application/json")
                .build()

            val body  = http.newCall(profileReq).execute().use { it.body?.string() } ?: ""
            val root  = JSONObject(body)
            val users = root.optJSONArray("profileUsers")
            val settings = users?.optJSONObject(0)?.optJSONArray("settings")
            if (settings != null) {
                for (i in 0 until settings.length()) {
                    val s = settings.getJSONObject(i)
                    if (s.optString("id") == "Gamertag") return s.optString("value")
                }
            }
            "OxPlayer"
        } catch (e: Exception) {
            Log.w(TAG, "Profile API hatası: ${e.message}")
            "OxPlayer"
        }
    }

    // ── JWT Yardımcıları ──────────────────────────────────────────────────

    /**
     * secp256r1 EC private key ile ES256 imzalı cihaz JWT'si oluşturur.
     *
     * Header : {"alg":"ES256","x5u":"<pubKeyB64>"}
     * Payload: {"certificateAuthority":true,"identityPublicKey":"<pubKeyB64>",
     *            "exp":<now+1gün>,"nbf":<now-1s>,"iat":<now>,"iss":"Minecraft"}
     */
    private fun buildDeviceJwt(privateKey: ECPrivateKey, pubKeyB64: String): String {
        val now = System.currentTimeMillis() / 1000L

        val header  = b64Url("""{"alg":"ES256","x5u":"$pubKeyB64"}""".toByteArray())
        val payload = b64Url(
            """{"certificateAuthority":true,"identityPublicKey":"$pubKeyB64",""" +
            """"exp":${now + 86400},"nbf":${now - 1},"iat":$now,"iss":"Minecraft"}"""
                .toByteArray()
        )
        val sigInput = "$header.$payload"

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(sigInput.toByteArray(Charsets.US_ASCII))
        val sig = derToRaw(signer.sign())

        return "$sigInput.${b64Url(sig)}"
    }

    /** DER formatındaki ECDSA imzasını JWT raw (r‖s) formatına çevirir. */
    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2 // SEQUENCE tag + len
        assert(der[0] == 0x30.toByte())
        assert(der[i] == 0x02.toByte()); i++
        val rLen = der[i++].toInt() and 0xFF
        val r    = der.copyOfRange(i, i + rLen); i += rLen
        assert(der[i] == 0x02.toByte()); i++
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

    private fun b64Url(str: String) = b64Url(str.toByteArray(Charsets.UTF_8))

    /** JWT listesinden extraData.displayName'i çıkarır. */
    private fun extractGamertagFromJwtList(jwts: List<String>): String {
        for (jwt in jwts) {
            try {
                val parts = jwt.split(".")
                if (parts.size < 2) continue
                val padded = parts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
                    .replace('-', '+').replace('_', '/')
                val payload = JSONObject(String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8))

                val extra = payload.optJSONObject("extraData")
                val name  = extra?.optString("displayName")?.takeIf { it.isNotBlank() }
                    ?: payload.optString("displayName").takeIf { it.isNotBlank() }
                if (name != null) return name
            } catch (_: Exception) {}
        }
        return ""
    }

    // ── HTTP Yardımcıları ─────────────────────────────────────────────────

    private fun postForm(url: String, params: Map<String, String>): Map<String, Any?> {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val text = http.newCall(Request.Builder().url(url).post(body).build())
            .execute().use { it.body?.string() ?: error("Boş yanıt: $url") }
        return parseJsonOrForm(text)
    }

    private fun postJson(url: String, json: String): Map<String, Any?> {
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json")
            .build()
        val text = http.newCall(req).execute().use { it.body?.string() ?: error("Boş yanıt: $url") }
        return parseJsonOrForm(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonOrForm(text: String): Map<String, Any?> =
        try {
            gson.fromJson(text, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            text.split("&").associate { part ->
                val eq = part.indexOf('=')
                if (eq < 0) part to null
                else part.substring(0, eq) to part.substring(eq + 1)
            }
        }

    private fun Map<String, Any?>.str(key: String): String =
        this[key] as? String ?: error("'$key' alanı bulunamadı")
}
