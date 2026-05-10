package com.oxclient.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.oxclient.core.proxy.HandshakeKeyHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
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
        val xblResp     = fetchXblToken(accessToken)
        val xblToken    = xblResp.first
        val xblGamertag = xblResp.second
        currentCoroutineContext().ensureActive()

        // 5. XSTS — Minecraft için
        val xstsMcResp  = fetchXsts(xblToken, "https://multiplayer.minecraft.net/")
        val xstsMcToken = xstsMcResp.first
        val xstsMcUhs   = xstsMcResp.second
        val xstsGamertag = xstsMcResp.third
        currentCoroutineContext().ensureActive()

        // ✅ FIX: Login paketi gönderilmeden önce EC key pair üret ve HandshakeKeyHolder'a kaydet.
        // MITMProxy.handleHandshake() bu private key'i kullanarak ECDH yapar ve şifrelemeyi başlatır.
        // Bu çağrı olmadan HandshakeKeyHolder.privateKey = null → şifreleme atlanır
        // → selfRuntimeId=0, konum=0,0,0 → modüller/konum çalışmaz.
        HandshakeKeyHolder.generate()
        Log.i(TAG, "HandshakeKeyHolder EC key pair üretildi — login başlıyor")

        // 6. Minecraft Bedrock token — X509 + JWT zinciri ile
        val (mcToken, chainGamertag) = fetchMinecraftToken(xstsMcToken, xstsMcUhs)
        currentCoroutineContext().ensureActive()

        // 7. Gamertag — önce chain'den, sonra diğer kaynaklar
        val gamertag = when {
            chainGamertag.isNotBlank() -> {
                Log.d(TAG, "✓ Gamertag kaynağı: MC chain = $chainGamertag")
                chainGamertag
            }
            else -> resolveGamertag(xblToken, xblGamertag, xstsGamertag, accessToken)
        }

        Log.d(TAG, "SON GAMERTAG: $gamertag")

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

    private fun resolveGamertag(
        xblToken: String,
        xblGamertag: String,
        xstsGamertag: String,
        accessToken: String
    ): String {
        Log.d(TAG, "=== GAMERTAG ÇÖZÜMLEME BAŞLADI ===")
        Log.d(TAG, "xblGamertag: '$xblGamertag'")
        Log.d(TAG, "xstsGamertag: '$xstsGamertag'")

        if (xstsGamertag.isNotBlank()) {
            Log.d(TAG, "✓ Gamertag kaynağı: XSTS = $xstsGamertag")
            return xstsGamertag
        }

        if (xblGamertag.isNotBlank()) {
            Log.d(TAG, "✓ Gamertag kaynağı: XBL gtg = $xblGamertag")
            return xblGamertag
        }

        try {
            val xboxXsts  = fetchXsts(xblToken, "http://xboxlive.com")
            val xboxToken = xboxXsts.first
            val xboxUhs   = xboxXsts.second

            val profileReq = Request.Builder()
                .url(PROFILE_URL)
                .header("Authorization",          "XBL3.0 x=$xboxUhs;$xboxToken")
                .header("x-xbl-contract-version", "2")
                .header("Accept",                 "application/json")
                .build()

            val profileText = http.newCall(profileReq).execute().body?.string() ?: ""
            Log.d(TAG, "Profile API yanıtı: ${profileText.take(500)}")

            if (profileText.isNotBlank()) {
                val root = JSONObject(profileText)
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
                            Log.d(TAG, "✓ Gamertag kaynağı: Profile API = $gt")
                            return gt
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Xbox Profile API başarısız: ${e.message}")
        }

        try {
            val parts = accessToken.split(".")
            if (parts.size >= 2) {
                val padded  = parts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
                val payload = String(
                    Base64.decode(padded.replace('-', '+').replace('_', '/'), Base64.DEFAULT),
                    Charsets.UTF_8
                )
                Log.d(TAG, "JWT payload: ${payload.take(500)}")
                val obj = JSONObject(payload)
                val raw = listOf("unique_name", "preferred_username", "email", "name", "upn", "given_name")
                    .mapNotNull { key ->
                        val v = obj.optString(key, "")
                        if (v.isNotBlank()) { Log.d(TAG, "JWT alanı '$key': $v"); v } else null
                    }.firstOrNull()
                if (raw != null) {
                    val gt = raw.substringBefore("@").trim()
                    if (gt.isNotBlank() && gt.length >= 3) {
                        Log.d(TAG, "✓ Gamertag kaynağı: JWT = $gt")
                        return gt
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JWT parse hatası: ${e.message}")
        }

        try {
            val parts = accessToken.split(".")
            if (parts.size >= 2) {
                val padded  = parts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
                val payload = String(
                    Base64.decode(padded.replace('-', '+').replace('_', '/'), Base64.DEFAULT),
                    Charsets.UTF_8
                )
                val obj   = JSONObject(payload)
                val email = obj.optString("email", "")
                if (email.isNotBlank() && email.contains("@")) {
                    val username = email.substringBefore("@").trim()
                    if (username.length >= 3) {
                        Log.d(TAG, "✓ Gamertag kaynağı: Email = $username")
                        return username
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Email çıkarma hatası: ${e.message}")
        }

        Log.w(TAG, "✗ Hiçbir kaynaktan gamertag alınamadı!")
        return "Oyuncu${(1000..9999).random()}"
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
        val displayClaims = resp["DisplayClaims"] as? Map<*, *>
        val xui = (displayClaims?.get("xui") as? List<Map<String, Any?>>)?.firstOrNull()

        Log.d(TAG, "XBL XUI anahtarlar: ${xui?.keys}")
        Log.d(TAG, "XBL XUI değerler: $xui")

        val gamertag = (xui?.get("gtg") as? String)
            ?: (xui?.get("Gamertag") as? String)
            ?: (xui?.get("gamertag") as? String)
            ?: (xui?.get("agg") as? String)
            ?: ""

        Log.d(TAG, "XBL gamertag: '$gamertag'")
        return token to gamertag
    }

    private fun fetchXsts(xblToken: String, relyingParty: String): Triple<String, String, String> {
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
        val displayClaims = resp["DisplayClaims"] as? Map<*, *>
        val xui = (displayClaims?.get("xui") as? List<Map<String, Any?>>)?.firstOrNull()
            ?: error("XSTS xui yok")

        val uhs = xui["uhs"] as? String ?: error("XSTS uhs yok")
        val gamertag = (xui["gtg"] as? String)
            ?: (xui["Gamertag"] as? String)
            ?: (xui["gamertag"] as? String)
            ?: ""

        Log.d(TAG, "XSTS XUI anahtarlar: ${xui.keys}")
        Log.d(TAG, "XSTS XUI değerler: $xui")
        Log.d(TAG, "XSTS gamertag: '$gamertag'")

        return Triple(token, uhs, gamertag)
    }

    // ── Minecraft token — X509 + JWT Zinciri ─────────────────────────────

    /**
     * Bedrock authentication akışı:
     *
     * 1. HandshakeKeyHolder'dan daha önce üretilen EC key pair'i al
     *    (generate() doSignInFlow() içinde fetchMinecraftToken'dan ÖNCE çağrıldı)
     * 2. Public key'i DER → Base64 olarak encode et
     * 3. Cihaz JWT'sini oluştur ve kendi private key'imizle ES256 ile imzala
     *    - header : { "alg": "ES256", "x5u": "<pubKeyB64>" }
     *    - payload: { "certificateAuthority": true,
     *                 "identityPublicKey": "<pubKeyB64>",
     *                 "exp": ..., "nbf": ..., "iat": ...,
     *                 "iss": "Minecraft" }
     * 4. [giden zincir = [cihaz JWT'si]] ile POST /authentication
     * 5. Mojang'dan dönen chain + kendi cihaz JWT'mizi birleştir →
     *    tüm diziyi JSON array string olarak mcToken'a kaydet
     */
    private fun fetchMinecraftToken(xstsToken: String, userHash: String): Pair<String, String> {

        // ── 1. HandshakeKeyHolder'dan key pair al ─────────────────────────
        // generate() doSignInFlow() içinde bu fonksiyondan ÖNCE çağrıldı.
        // Böylece MITMProxy.handleHandshake() aynı private key'i kullanarak
        // sunucuyla ECDH yapabilir ve şifrelemeyi başlatabilir.
        val ecPrivate = HandshakeKeyHolder.privateKey as? ECPrivateKey
            ?: run {
                // Fallback: eğer herhangi bir sebepten key yoksa üret ve kaydet
                Log.w(TAG, "HandshakeKeyHolder boş, generate() yeniden çağrılıyor")
                HandshakeKeyHolder.generate()
                HandshakeKeyHolder.privateKey as ECPrivateKey
            }
        val ecPublic = HandshakeKeyHolder.publicKey as ECPublicKey

        // SubjectPublicKeyInfo (DER) → Base64 — x5u ve identityPublicKey için kullanılır
        val pubKeyB64 = Base64.encodeToString(ecPublic.encoded, Base64.NO_WRAP)

        Log.d(TAG, "EC public key (B64, ${ecPublic.encoded.size} byte): ${pubKeyB64.take(40)}…")

        // ── 2. Cihaz JWT'si oluştur ───────────────────────────────────────
        val deviceJwt = buildDeviceJwt(ecPrivate, pubKeyB64)
        Log.d(TAG, "Cihaz JWT oluşturuldu: ${deviceJwt.take(60)}…")

        // ── 3. İstek gövdesi ──────────────────────────────────────────────
        // chain dizisi → gönderilecek zincir (sadece cihaz JWT'si)
        val outgoingChainJson = """["$deviceJwt"]"""

        val bodyStr = """{"identityPublicKey":"$pubKeyB64","chain":$outgoingChainJson}"""

        Log.d(TAG, "MC /authentication isteği gönderiliyor…")

        val req = Request.Builder()
            .url(MC_BEDROCK_URL)
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("Authorization",  "XBL3.0 x=$userHash;$xstsToken")
            .header("Content-Type",   "application/json")
            .header("Accept",         "application/json")
            .header("User-Agent",     "MCPE/UWP")
            .header("client-version", "1.21.0")
            .build()

        val text = http.newCall(req).execute().body?.string()
            ?: error("MC /authentication yanıtı boş")

        Log.d(TAG, "MC /authentication yanıtı: ${text.take(300)}")

        val json = parseJson(text)

        // ── 4. Yanıt işle — chain + token ────────────────────────────────
        @Suppress("UNCHECKED_CAST")
        val serverChain = json["chain"] as? List<*>

        if (!serverChain.isNullOrEmpty()) {
            // Mojang'dan dönen chain'e kendi cihaz JWT'mizi başa ekle
            // Böylece tam zincir: [cihaz JWT'si, mojang JWT'si, ...]
            val fullChain: List<String> = buildList {
                add(deviceJwt)                           // cihaz (bizim imzalı JWT)
                addAll(serverChain.filterIsInstance<String>()) // Mojang'dan gelenler
            }

            val mcToken  = gson.toJson(fullChain)        // JSON array string olarak sakla
            val gamertag = extractGamertagFromChain(serverChain)
            Log.d(TAG, "MC chain alındı (${fullChain.size} JWT). Gamertag: '$gamertag'")
            return mcToken to gamertag
        }

        // Chain yoksa token alanına bak (eski format / fallback)
        val token = json["token"] as? String ?: error("MC token alınamadı: $text")
        Log.w(TAG, "MC yanıtında chain yok, ham token kullanılıyor")
        return token to ""
    }

    /**
     * ES256 (ECDSA + SHA-256) ile imzalanmış cihaz JWT'si oluşturur.
     */
    private fun buildDeviceJwt(privateKey: ECPrivateKey, pubKeyB64: String): String {
        val nowSec  = System.currentTimeMillis() / 1000L
        val expSec  = nowSec + 86_400L
        val nbfSec  = nowSec - 1L

        val headerJson   = """{"alg":"ES256","x5u":"$pubKeyB64"}"""
        val headerB64    = b64Url(headerJson.toByteArray(Charsets.UTF_8))

        val payloadJson  = """{"certificateAuthority":true,"identityPublicKey":"$pubKeyB64","exp":$expSec,"nbf":$nbfSec,"iat":$nowSec,"iss":"Minecraft"}"""
        val payloadB64   = b64Url(payloadJson.toByteArray(Charsets.UTF_8))

        val signingInput = "$headerB64.$payloadB64"

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(signingInput.toByteArray(Charsets.US_ASCII))
        val derSig    = signer.sign()
        val rawSig    = derToRawEcSignature(derSig)
        val sigB64    = b64Url(rawSig)

        return "$signingInput.$sigB64"
    }

    private fun derToRawEcSignature(der: ByteArray): ByteArray {
        var offset = 2
        check(der[0] == 0x30.toByte()) { "DER SEQUENCE bekleniyor" }

        check(der[offset] == 0x02.toByte()) { "r INTEGER bekleniyor" }
        offset++
        val rLen    = der[offset++].toInt() and 0xFF
        val rBytes  = der.copyOfRange(offset, offset + rLen)
        offset     += rLen

        check(der[offset] == 0x02.toByte()) { "s INTEGER bekleniyor" }
        offset++
        val sLen    = der[offset++].toInt() and 0xFF
        val sBytes  = der.copyOfRange(offset, offset + sLen)

        val r32 = BigInteger(1, rBytes).toByteArray().let { padOrTrim(it, 32) }
        val s32 = BigInteger(1, sBytes).toByteArray().let { padOrTrim(it, 32) }

        return r32 + s32
    }

    private fun padOrTrim(bytes: ByteArray, size: Int): ByteArray {
        return when {
            bytes.size == size -> bytes
            bytes.size > size  -> bytes.copyOfRange(bytes.size - size, bytes.size)
            else               -> ByteArray(size - bytes.size) + bytes
        }
    }

    private fun b64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // ── Chain'den gamertag çıkar ──────────────────────────────────────────

    private fun extractGamertagFromChain(chain: List<*>): String {
        for (jwt in chain) {
            try {
                val parts = (jwt as? String)?.split(".") ?: continue
                if (parts.size < 2) continue
                val padded  = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
                val payload = String(
                    Base64.decode(padded.replace('-', '+').replace('_', '/'), Base64.DEFAULT),
                    Charsets.UTF_8
                )
                Log.d(TAG, "Chain JWT payload: $payload")
                val obj = JSONObject(payload)

                if (obj.has("extraData")) {
                    val displayName = obj.getJSONObject("extraData").optString("displayName")
                    if (displayName.isNotBlank()) return displayName
                }

                val direct = obj.optString("displayName").takeIf { it.isNotBlank() }
                    ?: obj.optString("username").takeIf { it.isNotBlank() }
                    ?: obj.optString("name").takeIf { it.isNotBlank() }
                if (direct != null) return direct

            } catch (e: Exception) {
                Log.w(TAG, "Chain JWT parse hatası: ${e.message}")
            }
        }
        return ""
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
