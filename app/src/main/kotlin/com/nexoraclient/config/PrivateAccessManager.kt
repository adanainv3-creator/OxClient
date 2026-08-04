package com.rubidiumclient.config

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "PrivateAccessManager"
private const val BASE_URL = "https://oxclient.com.tr"

private val Context.privateAccessDataStore: DataStore<Preferences> by preferencesDataStore(name = "ox_private_access")

/**
 * Rubidium Private access system.
 *
 * Verifies a license key entered on the Dashboard against the server
 * (`/private-key/verify`); on success, caches the key, its expiration, and
 * the current admin-configured list of "private" modules locally (DataStore).
 *
 * Which modules are private is entirely managed from the admin panel — this
 * object just carries that list around. The actual locking is enforced on
 * the UI side ([isModuleLocked], checked in OverlayService's module list)
 * and in keybind dispatch ([KeybindManager]).
 *
 * Once a key is entered, as long as it stays active (not expired) the module
 * list is periodically refreshed via [refreshModules] — so if the admin
 * adds/removes a module from the list, the user doesn't have to re-enter
 * their key.
 */
object PrivateAccessManager {
    private val KEY_STATE = stringPreferencesKey("state")

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class State(
        val licenseKey: String? = null,
        val expiresAt: Long? = null,            // null = lifetime
        val privateModules: Set<String> = emptySet()
    ) {
        val isActive: Boolean get() =
            licenseKey != null && (expiresAt == null || System.currentTimeMillis() < expiresAt)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val raw = runBlocking { safeCtx().privateAccessDataStore.data.map { it[KEY_STATE] }.first() }
            _state.value = parse(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load private access state", e)
        }
        scope.launch { refreshModules() }
    }

    private fun safeCtx(): Context =
        appContext ?: throw IllegalStateException("PrivateAccessManager.init() was not called!")

    private fun parse(raw: String?): State {
        if (raw.isNullOrBlank()) return State()
        return try {
            val json = JSONObject(raw)
            val licenseKey = if (json.has("licenseKey") && !json.isNull("licenseKey")) json.getString("licenseKey") else null
            val expiresAt  = if (json.has("expiresAt") && !json.isNull("expiresAt")) json.getLong("expiresAt") else null
            val modules = json.optJSONArray("privateModules")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } ?: emptySet()
            State(licenseKey, expiresAt, modules)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse private access state", e)
            State()
        }
    }

    private fun persist(newState: State) {
        val ctx = appContext ?: return
        try {
            runBlocking {
                ctx.privateAccessDataStore.edit { prefs ->
                    val json = JSONObject()
                    json.put("licenseKey", newState.licenseKey ?: JSONObject.NULL)
                    json.put("expiresAt", newState.expiresAt ?: JSONObject.NULL)
                    json.put("privateModules", JSONArray(newState.privateModules.toList()))
                    prefs[KEY_STATE] = json.toString()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save private access state", e)
        }
    }

    fun isActive(): Boolean = _state.value.isActive

    /** True (locked) if the module is on the private list AND the user has no active key. */
    fun isModuleLocked(moduleName: String): Boolean {
        val s = _state.value
        return s.privateModules.any { it.equals(moduleName, ignoreCase = true) } && !s.isActive
    }

    sealed class RedeemResult {
        data class Success(val expiresAt: Long?) : RedeemResult()
        object Invalid : RedeemResult()
        object Expired : RedeemResult()
        object RateLimited : RedeemResult()
        data class NetworkError(val message: String) : RedeemResult()
    }

    /** Verifies the key against the server; updates local state on success. Runs on the IO thread. */
    suspend fun redeem(key: String): RedeemResult = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return@withContext RedeemResult.Invalid

        try {
            val body = JSONObject().put("key", trimmed)
            val (code, text) = httpPostJson("$BASE_URL/private-key/verify", body)
                ?: return@withContext RedeemResult.NetworkError("Could not reach the server")

            if (code == 429) return@withContext RedeemResult.RateLimited

            val json = JSONObject(text)
            val valid = json.optBoolean("valid", false)
            if (!valid) {
                return@withContext if (json.optString("error") == "expired") RedeemResult.Expired
                else RedeemResult.Invalid
            }

            val expiresAt = if (json.isNull("expiresAt") || !json.has("expiresAt")) null else json.optLong("expiresAt")
            val modules = json.optJSONArray("modules")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } ?: emptySet()

            val newState = State(licenseKey = trimmed.uppercase(), expiresAt = expiresAt, privateModules = modules)
            _state.value = newState
            persist(newState)
            RedeemResult.Success(expiresAt)
        } catch (e: Exception) {
            Log.e(TAG, "Redeem error", e)
            RedeemResult.NetworkError(e.message ?: "Unknown error")
        }
    }

    /**
     * Refreshes the private module list from the server.
     *
     * Runs regardless of whether the user has an active key — which modules
     * are private isn't secret, it's what [isModuleLocked] needs to know
     * which toggles to lock. Only the key itself gates unlocking.
     */
    suspend fun refreshModules() = withContext(Dispatchers.IO) {
        try {
            val (code, text) = httpGetJson("$BASE_URL/private-modules") ?: return@withContext
            if (code !in 200..299) return@withContext
            val json = JSONObject(text)
            val modules = json.optJSONArray("modules")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } ?: return@withContext
            val newState = _state.value.copy(privateModules = modules)
            _state.value = newState
            persist(newState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh private module list", e)
        }
    }

    /** Called when the user removes their key themselves (e.g. the "Deactivate" button). */
    fun deactivate() {
        val newState = _state.value.copy(licenseKey = null, expiresAt = null)
        _state.value = newState
        persist(newState)
    }

    private fun httpPostJson(urlStr: String, bodyJson: JSONObject): Pair<Int, String>? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(bodyJson.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            code to text
        } catch (e: Exception) {
            Log.e(TAG, "HTTP POST error: $urlStr", e)
            null
        }
    }

    private fun httpGetJson(urlStr: String): Pair<Int, String>? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            code to text
        } catch (e: Exception) {
            Log.e(TAG, "HTTP GET error: $urlStr", e)
            null
        }
    }
}
