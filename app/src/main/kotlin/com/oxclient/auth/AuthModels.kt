package com.oxclient.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ── Modeller ──────────────────────────────────────────────────────────────

data class DeviceCodeResponse(
    val deviceCode      : String,
    val userCode        : String,
    val verificationUri : String,
    val expiresIn       : Int,
    val interval        : Int,
    val message         : String
)

data class MsTokenResponse(
    val accessToken : String,
    val refreshToken: String,
    val expiresIn   : Int
)

data class XblToken(
    val token       : String,
    val userHash    : String
)

data class MinecraftProfile(
    val uuid        : String,
    val name        : String,
    val xuid        : String,
    val accessToken : String
)

// ── Auth State ───────────────────────────────────────────────────────────

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class WaitingForUser(val userCode: String, val verificationUri: String) : AuthState()
    data class Success(val gamertag: String, val mcToken: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

// ── Saved Account ────────────────────────────────────────────────────────

data class SavedAccount(
    val gamertag     : String,
    val refreshToken : String,
    val mcToken      : String,
    val expireTimeMs : Long
)

// ── DataStore ────────────────────────────────────────────────────────────

val Context.authDataStore by preferencesDataStore(name = "auth")

class AccountManager(private val context: Context) {

    private val gson = Gson()
    private val KEY  = stringPreferencesKey("profile")

    suspend fun save(profile: MinecraftProfile) {
        context.authDataStore.edit { it[KEY] = gson.toJson(profile) }
    }

    suspend fun load(): MinecraftProfile? {
        val json = context.authDataStore.data.first()[KEY] ?: return null
        return try { gson.fromJson(json, MinecraftProfile::class.java) } catch (_: Exception) { null }
    }

    suspend fun clear() {
        context.authDataStore.edit { it.remove(KEY) }
    }
}

// ── MicrosoftAuthManager (OBJECT - Singleton) ────────────────────────────

object MicrosoftAuthManager {

    private const val TAG = "MsAuth"

    private const val CLIENT_ID = "00000000441cc96b"
    private const val SCOPE     = "service::user.auth.xboxlive.com::MBI_SSL"
    private const val TENANT    = "consumers"

    private const val DEVICE_CODE_URL = "https://login.microsoftonline.com/$TENANT/oauth2/v2.0/devicecode"
    private const val TOKEN_URL       = "https://login.microsoftonline.com/$TENANT/oauth2/v2.0/token"
    private const val XBL_URL         = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL        = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_URL          = "https://multiplayer.minecraft.net/authentication"
    private const val PROFILE_URL     = "https://api.minecraftservices.com/minecraft/profile"

    private val JSON_MEDIA = "application/json".toMediaType()
    private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private var initialized = false

    private lateinit var appContext: Context

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
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
                        Log.e(TAG, "Sign-in failed", e)
                        _authState.value = AuthState.Error(e.message ?: "Giris basarisiz")
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
        _authState.value = AuthState.Idle
    }

    // ── Flow ──────────────────────────────────────────────────────────────

    private suspend fun doSignInFlow() {
        // 1. Device code
        val dc = getDeviceCode() ?: error("Cihaz kodu alinamadi")
        _authState.value = AuthState.WaitingForUser(dc.userCode, dc.verificationUri)

        // 2. Poll token
        val ms = pollToken(dc.deviceCode, dc.interval)
            ?: error("Token zaman asimi")

        // 3. XBL
        val xbl = getXblToken(ms.accessToken) ?: error("XBL hatasi")

        // 4. XSTS
        val xsts = getXstsToken(xbl) ?: error("XSTS hatasi")

        // 5. MC token
        val mcToken = getMinecraftToken(xsts) ?: error("MC token hatasi")

        // 6. Profile
        val profile = getProfile(mcToken) ?: error("Profil hatasi")

        _authState.value = AuthState.Success(profile.name, mcToken)
    }

    // ── API Calls ─────────────────────────────────────────────────────────

    suspend fun getDeviceCode(): DeviceCodeResponse? = runCatching {
        val body = "client_id=$CLIENT_ID&scope=$SCOPE".toRequestBody(FORM_MEDIA)
        val req = Request.Builder().url(DEVICE_CODE_URL).post(body).build()
        val json = JSONObject(http.newCall(req).await().body?.string() ?: return null)
        DeviceCodeResponse(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresIn = json.getInt("expires_in"),
            interval = json.getInt("interval"),
            message = json.optString("message", "")
        )
    }.onFailure { Log.e(TAG, "DeviceCode error", it) }.getOrNull()

    suspend fun pollToken(deviceCode: String, intervalSec: Int): MsTokenResponse? {
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSec * 1000L)
            val body = "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=$deviceCode&client_id=$CLIENT_ID"
                .toRequestBody(FORM_MEDIA)
            val req = Request.Builder().url(TOKEN_URL).post(body).build()
            try {
                val json = JSONObject(http.newCall(req).await().body?.string() ?: continue)
                if (json.has("access_token")) {
                    return MsTokenResponse(
                        json.getString("access_token"),
                        json.getString("refresh_token"),
                        json.getInt("expires_in")
                    )
                }
                val err = json.optString("error", "")
                if (err != "authorization_pending" && err != "slow_down") break
            } catch (e: Exception) { Log.e(TAG, "Poll error", e) }
        }
        return null
    }

    suspend fun getXblToken(msToken: String): XblToken? = runCatching {
        val payload = """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=$msToken"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
            .toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url(XBL_URL).post(payload)
            .header("Content-Type", "application/json").header("Accept", "application/json").build()
        val json = JSONObject(http.newCall(req).await().body?.string() ?: return null)
        XblToken(json.getString("Token"), json.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs"))
    }.onFailure { Log.e(TAG, "XBL error", it) }.getOrNull()

    suspend fun getXstsToken(xbl: XblToken): XblToken? = runCatching {
        val payload = """{"Properties":{"SandboxId":"RETAIL","UserTokens":["${xbl.token}"]},"RelyingParty":"https://multiplayer.minecraft.net/","TokenType":"JWT"}"""
            .toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url(XSTS_URL).post(payload)
            .header("Content-Type", "application/json").header("Accept", "application/json").build()
        val json = JSONObject(http.newCall(req).await().body?.string() ?: return null)
        val uhs = json.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
        XblToken(json.getString("Token"), uhs)
    }.onFailure { Log.e(TAG, "XSTS error", it) }.getOrNull()

    suspend fun getMinecraftToken(xsts: XblToken): String? = runCatching {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pubB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val payload = """{"identityPublicKey":"$pubB64"}""".toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url(MC_URL).post(payload)
            .header("Authorization", "XBL3.0 x=${xsts.userHash};${xsts.token}")
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("User-Agent", "MCPE/UWP").header("client-version", "1.21.0").build()
        JSONObject(http.newCall(req).await().body?.string() ?: return null).getString("token")
    }.onFailure { Log.e(TAG, "MC Token error", it) }.getOrNull()

    suspend fun getProfile(mcToken: String): MinecraftProfile? = runCatching {
        val req = Request.Builder().url(PROFILE_URL).get().header("Authorization", "Bearer $mcToken").build()
        val json = JSONObject(http.newCall(req).await().body?.string() ?: return null)
        MinecraftProfile(json.getString("id"), json.getString("name"), "", mcToken)
    }.onFailure { Log.e(TAG, "Profile error", it) }.getOrNull()

    // ── OkHttp await ──────────────────────────────────────────────────────

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) { cont.resume(response) {} }
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        })
        cont.invokeOnCancellation { cancel() }
    }
}