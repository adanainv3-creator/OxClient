package com.oxclient.auth

import android.content.Context
import android.util.Base64
import com.oxclient.ui.overlay.OverlayLogger
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

object MicrosoftAuthManager {

    private const val TAG = "MicrosoftAuth"

    private const val CLIENT_ID      = "0000000048183522"
    private const val REDIRECT_URI   = "https://login.live.com/oauth20_desktop.srf"
    private const val SCOPE          = "service::user.auth.xboxlive.com::MBI_SSL"
    private const val TOKEN_URL      = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL        = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL       = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_BEDROCK_URL = "https://multiplayer.minecraft.net/authentication"
    private const val PROFILE_URL    =
        "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag"

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

    @Volatile private var encryptionPrivateKey: java.security.PrivateKey? = null

    fun getPrivateKeyForEncryption(): java.security.PrivateKey? = encryptionPrivateKey

    fun setPrivateKeyForEncryption(key: java.security.PrivateKey) {
        encryptionPrivateKey = key
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        AccountManager.init(context)

        val saved = AccountManager.selectedAccount
        if (saved != null && saved.isRelayReady()) {
            _authState.value = AuthState.Success(saved.gamertag, saved.mcToken)
            OverlayLogger.i(TAG, "Kaydedilen hesap yüklendi: ${saved.gamertag}")
        } else if (saved != null && saved.isExpired()) {
            OverlayLogger.w(TAG, "Token süresi dolmuş, yenileniyor: ${saved.gamertag}")
            scope.launch { refreshTokenSilently(saved) }
        }
    }

    fun startSignIn() {
        if (_authState.value.isLoading) return
        _authState.value = AuthState.WaitingForWebView
        OverlayLogger.i(TAG, "WebView giriş bekleniyor")
    }

    fun exchangeCodeForToken(code: String) {
        if (activeJob?.isActive == true) return
        _authState.value = AuthState.Loading
        activeJob = scope.launch {
            runCatching { doTokenExchangeFlow(code) }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        OverlayLogger.e(TAG, "Token exchange başarısız", e)
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
        OverlayLogger.i(TAG, "Çıkış yapıldı")
    }

    fun getActiveChainForRelay(): String? {
        val account = AccountManager.selectedAccount ?: run {
            OverlayLogger.w(TAG, "getActiveChainForRelay: seçili hesap yok")
            return null
        }
        val remainingMs = account.expireTimeMs - System.currentTimeMillis()
        OverlayLogger.d(TAG, "getActiveChainForRelay: gamertag=${account.gamertag} " +
            "kalan=${remainingMs / 1000}s pubKey(ilk32)=${account.publicKeyB64.take(32)}…")
        if (account.isExpired()) {
            OverlayLogger.w(TAG, "Token süresi dolmuş, arka planda yenileniyor")
            scope.launch { refreshTokenSilently(account) }
        }
        return account.mcToken.takeIf { it.isNotBlank() }
    }

    /**
     * Chain'in ilk linkini (deviceJwt) imzalayan EC private key — PKCS8 Base64.
     * LoginPacketListener bunu, client'ın orijinal clientJwt'sini (skin/client
     * data) AYNI key ile yeniden imzalamak için kullanır. Bu olmadan sunucu
     * "Invalid login data (identifier)" ile bağlantıyı reddeder, çünkü chain'in
     * public key'i ile clientJwt'nin imzalayanı eşleşmez.
     */
    fun getActivePrivateKeyForRelay(): String? {
        val account = AccountManager.selectedAccount ?: return null
        return account.privateKeyB64.takeIf { it.isNotBlank() }
    }

    /** Aynı keypair'in public key'i — clientJwt yeniden imzalanırken JWT header'ı için. */
    fun getActivePublicKeyForRelay(): String? {
        val account = AccountManager.selectedAccount ?: return null
        return account.publicKeyB64.takeIf { it.isNotBlank() }
    }

    private suspend fun doTokenExchangeFlow(code: String) {
        OverlayLogger.d(TAG, "Authorization code ile token alınıyor...")

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
        OverlayLogger.d(TAG, "Access token alındı ✓")

        val xblResult = fetchXblToken(accessToken)
        currentCoroutineContext().ensureActive()
        OverlayLogger.d(TAG, "XBL token alındı ✓  uhs=${xblResult.userHash}  gtg=${xblResult.gamertag}")

        val xstsResult = fetchXsts(xblResult.token, "https://multiplayer.minecraft.net/")
        currentCoroutineContext().ensureActive()
        OverlayLogger.d(TAG, "XSTS token alındı ✓  uhs=${xstsResult.userHash}  gtg=${xstsResult.gamertag}")

        val mcResult = fetchMinecraftChain(xstsResult.token, xstsResult.userHash)
        currentCoroutineContext().ensureActive()

        val gamertag = resolveGamertag(
            chainResult  = mcResult,
            xstsGamertag = xstsResult.gamertag,
            xblGamertag  = xblResult.gamertag,
            xblToken     = xblResult.token
        )
        OverlayLogger.i(TAG, "Giriş tamamlandı: $gamertag")

        val account = SavedAccount(
            gamertag     = gamertag,
            refreshToken = refreshToken,
            mcToken      = mcResult.chainJson,
            expireTimeMs = System.currentTimeMillis() + 6 * 3_600_000L,
            privateKeyB64 = mcResult.privateKeyB64,
            publicKeyB64  = mcResult.publicKeyB64
        )
        AccountManager.addAccount(account)
        AccountManager.selectAccount(account)

        withContext(Dispatchers.Main) {
            _authState.value = AuthState.Success(gamertag, mcResult.chainJson)
        }
    }

    private suspend fun refreshTokenSilently(account: SavedAccount) {
        if (account.refreshToken.isBlank()) {
            OverlayLogger.w(TAG, "Refresh token yok, manuel giriş gerekli")
            _authState.value = AuthState.Idle
            return
        }

        try {
            OverlayLogger.d(TAG, "Token yenileniyor: ${account.gamertag}")
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
            AccountManager.refreshAccount(account.gamertag, mcResult.chainJson, newExpire, mcResult.privateKeyB64, mcResult.publicKeyB64)
            AccountManager.selectedAccount?.let { updated ->
                AccountManager.addAccount(updated.copy(refreshToken = refreshToken))
            }

            _authState.value = AuthState.Success(account.gamertag, mcResult.chainJson)
            OverlayLogger.i(TAG, "Token yenilendi: ${account.gamertag}")

        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Token yenileme başarısız: ${e.message}", e)
            _authState.value = AuthState.Error("Token yenileme başarısız: ${e.message}")
        }
    }

    private fun fetchXblToken(accessToken: String): XblAuthResult {
        val bodyJson = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName",   "user.auth.xboxlive.com")
                put("RpsTicket",  accessToken)
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType",    "JWT")
        }.toString()

        val respText = postJsonRaw(XBL_URL, bodyJson)
        OverlayLogger.d(TAG, "XBL yanıt (ilk 300): ${respText.take(300)}")

        val resp = JSONObject(respText)

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

        OverlayLogger.d(TAG, "XBL XUI: uhs=$uhs  gtg=$gamertag")
        return XblAuthResult(token, uhs, gamertag)
    }

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
        OverlayLogger.d(TAG, "XSTS yanıt (ilk 300): ${respText.take(300)}")

        val resp = JSONObject(respText)

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

        OverlayLogger.d(TAG, "XSTS XUI: uhs=$uhs  gtg=$gamertag")
        return XblAuthResult(token, uhs, gamertag)
    }

    /**
     * Login anında alınan chain'in exp/nbf/identityPublicKey özetini loglar.
     * Bu log, LoginPacketListener'daki "Chain diagnostiği" logu ile birebir
     * karşılaştırılabilir — eğer buradaki pubKeyB64, relay bağlantısı sırasında
     * loglanan pubKeyB64 ile FARKLIYSA, hesap arada yenilenmiş/bozulmuş demektir.
     */
    private fun logFetchedChainSummary(chain: List<String>, expectedPubKeyB64: String) {
        val nowSec = System.currentTimeMillis() / 1000L
        chain.forEachIndexed { idx, jwt ->
            val parts = jwt.split(".")
            if (parts.size != 3) return@forEachIndexed
            val payload = try {
                val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
                JSONObject(String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            } catch (_: Exception) { null } ?: return@forEachIndexed

            val exp = payload.optLong("exp", -1L)
            val nbf = payload.optLong("nbf", -1L)
            val expIn = if (exp > 0) exp - nowSec else null
            OverlayLogger.d(TAG, "  chain[$idx] exp=${expIn?.let { "${it}s kaldı" } ?: "-"} nbf=$nbf")

            if (idx == 0) {
                val idKey = payload.optString("identityPublicKey", "")
                if (idKey.isNotBlank() && idKey != expectedPubKeyB64) {
                    OverlayLogger.e(TAG, "  ★ chain[0].identityPublicKey üretilen pubKeyB64 ile eşleşmiyor — bu login akışında bir tutarsızlık var!")
                }
            }
        }
    }

    private fun fetchMinecraftChain(xstsToken: String, userHash: String): McAuthResult {
        val keyGen  = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp384r1"))
        val keyPair = keyGen.generateKeyPair()
        val pubKey  = keyPair.public  as ECPublicKey
        val privKey = keyPair.private as ECPrivateKey
        val pubKeyB64 = Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)
        // PKCS8 — Base64.DEFAULT (standart, URL-safe değil) olarak saklıyoruz,
        // LoginPacketListener bunu PKCS8EncodedKeySpec ile geri okuyacak.
        val privKeyB64 = Base64.encodeToString(privKey.encoded, Base64.NO_WRAP)
        OverlayLogger.d(TAG, "fetchMinecraftChain: yeni keypair üretildi — pubKeyB64 (ilk 32)=${pubKeyB64.take(32)}…")

        val deviceJwt = buildDeviceJwt(privKey, pubKeyB64)

        val requestBody = JSONObject().apply {
            put("identityPublicKey", pubKeyB64)
            put("chain", JSONArray().apply { put(deviceJwt) })
        }.toString()

        // NOT: client-version sabit "1.21.80" — gerçek bağlanan protokol/versiyon
        // (örn. 898 / 1.21.132) ile TUTARSIZ olabilir. Bazı sıkı sunucular login
        // sırasında GameVersion ile bu header'ı çapraz kontrol edebilir. Şu an
        // relay'in gerçek protokol versiyonuna erişimi bu aşamada olmadığı için
        // sabit bırakıldı — sorun devam ederse burası bir sonraki şüphelidir.
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

        OverlayLogger.d(TAG, "MC /authentication yanıtı: ${responseText.take(200)}…")

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
            OverlayLogger.i(TAG, "MC chain alındı: ${fullChain.size} JWT, gamertag=$gamertag")
            logFetchedChainSummary(fullChain, pubKeyB64)
            return McAuthResult(chainWrapper, gamertag, privKeyB64, pubKeyB64)
        }

        val token = json.optString("token").takeIf { it.isNotBlank() }
            ?: error("MC token alınamadı: $responseText")

        OverlayLogger.w(TAG, "MC yanıtında chain yok, ham token kullanılıyor")
        val fallbackChain = JSONObject().apply {
            put("chain", JSONArray().apply { put(deviceJwt); put(token) })
        }.toString()
        return McAuthResult(fallbackChain, "", privKeyB64, pubKeyB64)
    }

    private fun resolveGamertag(
        chainResult: McAuthResult,
        xstsGamertag: String,
        xblGamertag: String,
        xblToken: String
    ): String {
        if (chainResult.gamertag.isNotBlank()) {
            OverlayLogger.d(TAG, "Gamertag kaynağı: MC chain → ${chainResult.gamertag}")
            return chainResult.gamertag
        }
        if (xstsGamertag.isNotBlank()) {
            OverlayLogger.d(TAG, "Gamertag kaynağı: XSTS gtg → $xstsGamertag")
            return xstsGamertag
        }
        if (xblGamertag.isNotBlank()) {
            OverlayLogger.d(TAG, "Gamertag kaynağı: XBL gtg → $xblGamertag")
            return xblGamertag
        }

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
                        OverlayLogger.d(TAG, "Gamertag kaynağı: Profile API → $gt")
                        return gt
                    }
                }
            }
            OverlayLogger.w(TAG, "Profile API'den gamertag alınamadı")
            "OxPlayer"
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Profile API hatası: ${e.message}")
            "OxPlayer"
        }
    }

    private fun buildDeviceJwt(privateKey: ECPrivateKey, pubKeyB64: String): String {
        val now     = System.currentTimeMillis() / 1000L
        // Mojang Bedrock auth secp384r1 (P-384) + ES384 kullanır — secp256r1/ES256
        // İLE imzalanan zincirler sunucu tarafında "Invalid login data" ile reddedilir.
        val header  = b64Url("""{"alg":"ES384","x5u":"$pubKeyB64"}""".toByteArray())
        val payload = b64Url(
            ("""{"certificateAuthority":true,"identityPublicKey":"$pubKeyB64",""" +
             """"exp":${now + 86400},"nbf":${now - 1},"iat":$now,"iss":"Minecraft"}""")
                .toByteArray()
        )
        val sigInput = "$header.$payload"
        val signer   = Signature.getInstance("SHA384withECDSA")
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
        // P-384 imza bileşenleri 48 byte'tır (P-256'da olduğu gibi 32 değil)
        return padOrTrim(BigInteger(1, r).toByteArray(), 48) +
               padOrTrim(BigInteger(1, s).toByteArray(), 48)
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

    private fun postForm(url: String, params: Map<String, String>): Map<String, Any?> {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val text = http.newCall(Request.Builder().url(url).post(body).build())
            .execute().use { resp ->
                val b = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    OverlayLogger.e(TAG, "postForm HTTP ${resp.code} [$url]: $b")
                    error("HTTP ${resp.code}: $b")
                }
                b
            }
        return parseJsonOrForm(text)
    }

    private fun postJsonRaw(url: String, json: String): String {
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json")
            .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            OverlayLogger.d(TAG, "postJsonRaw HTTP ${resp.code} [$url]")
            val isXstsError = text.contains("XErr")
            if (!resp.isSuccessful && !isXstsError) {
                OverlayLogger.e(TAG, "postJsonRaw HTTP ${resp.code} [$url]: ${text.take(400)}")
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