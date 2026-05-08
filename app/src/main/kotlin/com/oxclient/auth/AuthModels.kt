package com.oxclient.auth

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

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

// ─────────────────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────────────────

class MicrosoftAuthManager {

    private val TAG    = "MsAuth"
    private val client = OkHttpClient()
    private val gson   = Gson()
    private val JSON   = "application/json".toMediaType()

    private val CLIENT_ID  = "00000000441cc96b"   // Xbox Live public client
    private val SCOPE      = "service::user.auth.xboxlive.com::MBI_SSL"
    private val TENANT     = "consumers"

    // ── Adım 1: Cihaz kodu al ─────────────────────────────────────────────

    suspend fun getDeviceCode(): DeviceCodeResponse? = kotlin.runCatching {
        val body = "client_id=$CLIENT_ID&scope=$SCOPE"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val req = Request.Builder()
            .url("https://login.microsoftonline.com/$TENANT/oauth2/v2.0/devicecode")
            .post(body)
            .build()

        val resp = client.newCall(req).await()
        val json = JSONObject(resp.body!!.string())

        DeviceCodeResponse(
            deviceCode      = json.getString("device_code"),
            userCode        = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresIn       = json.getInt("expires_in"),
            interval        = json.getInt("interval"),
            message         = json.optString("message")
        )
    }.onFailure { Log.e(TAG, "DeviceCode hatası", it) }.getOrNull()

    // ── Adım 2: Token bekle ───────────────────────────────────────────────

    suspend fun pollToken(deviceCode: String, intervalSec: Int): MsTokenResponse? {
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(intervalSec * 1000L)

            val body = "grant_type=urn:ietf:params:oauth:grant-type:device_code" +
                "&device_code=$deviceCode&client_id=$CLIENT_ID"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())

            val req = Request.Builder()
                .url("https://login.microsoftonline.com/$TENANT/oauth2/v2.0/token")
                .post(body)
                .build()

            try {
                val resp = client.newCall(req).await()
                val json = JSONObject(resp.body!!.string())

                if (json.has("access_token")) {
                    return MsTokenResponse(
                        accessToken  = json.getString("access_token"),
                        refreshToken = json.getString("refresh_token"),
                        expiresIn    = json.getInt("expires_in")
                    )
                }

                val error = json.optString("error")
                if (error != "authorization_pending" && error != "slow_down") {
                    Log.e(TAG, "Token hatası: $error"); break
                }
            } catch (e: Exception) { Log.e(TAG, "Poll hatası", e) }
        }
        return null
    }

    // ── Adım 3: Xbox Live Token ───────────────────────────────────────────

    suspend fun getXblToken(msAccessToken: String): XblToken? = kotlin.runCatching {
        val payload = """
            {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com",
            "RpsTicket":"d=$msAccessToken"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}
        """.trimIndent().toRequestBody(JSON)

        val req = Request.Builder()
            .url("https://user.auth.xboxlive.com/user/authenticate")
            .post(payload)
            .header("Content-Type","application/json")
            .header("Accept","application/json")
            .build()

        val resp = client.newCall(req).await()
        val json = JSONObject(resp.body!!.string())
        val token    = json.getString("Token")
        val userHash = json.getJSONObject("DisplayClaims")
            .getJSONArray("xui").getJSONObject(0).getString("uhs")
        XblToken(token, userHash)
    }.onFailure { Log.e(TAG, "XBL hatası", it) }.getOrNull()

    // ── Adım 4: XSTS Token ────────────────────────────────────────────────

    suspend fun getXstsToken(xblToken: XblToken): XblToken? = kotlin.runCatching {
        val payload = """
            {"Properties":{"SandboxId":"RETAIL","UserTokens":["${xblToken.token}"]},
            "RelyingParty":"https://multiplayer.minecraft.net/","TokenType":"JWT"}
        """.trimIndent().toRequestBody(JSON)

        val req = Request.Builder()
            .url("https://xsts.auth.xboxlive.com/xsts/authorize")
            .post(payload)
            .build()

        val resp = client.newCall(req).await()
        val json = JSONObject(resp.body!!.string())
        val token    = json.getString("Token")
        val userHash = json.getJSONObject("DisplayClaims")
            .getJSONArray("xui").getJSONObject(0).getString("uhs")
        XblToken(token, userHash)
    }.onFailure { Log.e(TAG, "XSTS hatası", it) }.getOrNull()

    // ── Adım 5: Minecraft Token ───────────────────────────────────────────

    suspend fun getMinecraftToken(xstsToken: XblToken): String? = kotlin.runCatching {
        val payload = """{"identityPublicKey":"${generateDummyKey()}"}""".toRequestBody(JSON)
        val req = Request.Builder()
            .url("https://multiplayer.minecraft.net/authentication")
            .post(payload)
            .header("Authorization", "XBL3.0 x=${xstsToken.userHash};${xstsToken.token}")
            .build()
        val resp = client.newCall(req).await()
        JSONObject(resp.body!!.string()).getString("token")
    }.onFailure { Log.e(TAG, "MC Token hatası", it) }.getOrNull()

    // ── Adım 6: Profil ────────────────────────────────────────────────────

    suspend fun getProfile(mcToken: String, xuid: String): MinecraftProfile? = kotlin.runCatching {
        val req = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile")
            .get()
            .header("Authorization", "Bearer $mcToken")
            .build()
        val resp = client.newCall(req).await()
        val json = JSONObject(resp.body!!.string())
        MinecraftProfile(
            uuid        = json.getString("id"),
            name        = json.getString("name"),
            xuid        = xuid,
            accessToken = mcToken
        )
    }.onFailure { Log.e(TAG, "Profil hatası", it) }.getOrNull()

    private fun generateDummyKey(): String =
        "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE" + "A".repeat(64)

    // OkHttp suspend ext
    private suspend fun Call.await(): Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) { cont.resume(response) {} }
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        })
        cont.invokeOnCancellation { cancel() }
    }
}
