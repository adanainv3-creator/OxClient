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
import kotlinx.coroutines.delay
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

/**
 * Fail-closed default. Used only when there's no cached state at all (fresh
 * install / cleared data) AND the server hasn't been reached yet — e.g. the
 * app is opened for the very first time with no internet connection. Until
 * the real list is fetched, these are treated as locked so a user can't get
 * a window of unrestricted access just by starting offline.
 *
 * Keep this in sync with whatever is currently marked private in the admin
 * panel — it's a safety net, not a substitute for the real list.
 */
private val FALLBACK_PRIVATE_MODULES = setOf(
    "SelfTrap", "AutoTrap", "PistonAura", "BedAura", "AntiBed",
    "AutoTravel", "AutoScaffold", "ElytraFly", "AutoMapArt", "AutoMine"
)

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
 * The private module list is fetched from the server independent of whether
 * a key is active — [refreshModules] always runs, since knowing *which*
 * modules are private isn't sensitive, only unlocking them is. If there's no
 * cached list yet and the server can't be reached (e.g. first launch,
 * offline), a bundled fallback list is treated as locked until the real
 * list is fetched, and fetching is retried with backoff until it succeeds.
 */
object PrivateAccessManager {
    private val KEY_STATE = stringPreferencesKey("state")

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** True until we've gotten a real module list — either from cache or the server. */
    @Volatile private var needsSync = true

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
        var loadedFromCache = false
        try {
            val raw = runBlocking { safeCtx().privateAccessDataStore.data.map { it[KEY_STATE] }.first() }
            if (!raw.isNullOrBlank()) {
                _state.value = parse(raw)
                loadedFromCache = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load private access state", e)
        }

        if (!loadedFromCache) {
            // No cache at all (fresh install / cleared data). Assume the known
            // private modules are locked until we actually reach the server —
            // don't let "offline on first launch" mean "everything unlocked".
            _state.value = _state.value.copy(privateModules = FALLBACK_PRIVATE_MODULES)
        } else {
            needsSync = false
        }

        scope.launch { syncModules() }
    }

    /** Fetches the module list once; if it still hasn't succeeded, retries with backoff until it does. */
    private suspend fun syncModules() {
        var delayMs = 5_000L
        while (true) {
            val success = refreshModules()
            if (success || !needsSync) return
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(60_000L)
        }
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
            needsSync = false
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
     *
     * @return true if the list was successfully fetched and applied.
     */
    suspend fun refreshModules(): Boolean = withContext(Dispatchers.IO) {
        try {
            val (code, text) = httpGetJson("$BASE_URL/private-modules") ?: return@withContext false
            if (code !in 200..299) return@withContext false
            val json = JSONObject(text)
            val modules = json.optJSONArray("modules")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } ?: return@withContext false
            val newState = _state.value.copy(privateModules = modules)
            _state.value = newState
            persist(newState)
            needsSync = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh private module list", e)
            false
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
