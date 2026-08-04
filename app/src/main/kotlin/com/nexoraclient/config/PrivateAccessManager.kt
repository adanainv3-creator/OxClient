package com.rubidiumclient.config

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 * Rubidium Private erişim sistemi.
 *
 * Dashboard'a girilen bir lisans anahtarını sunucuya (`/private-key/verify`)
 * doğrulatır; başarılıysa anahtarı, bitiş tarihini ve o anda admin panelde
 * "private" işaretlenmiş modül listesini yerelde (DataStore) önbelleğe alır.
 *
 * Hangi modüllerin private olduğu tamamen admin panelden yönetiliyor — bu
 * obje sadece o listeyi taşıyor. Gerçek kilitleme UI tarafında
 * ([isModuleLocked] kontrol edilerek — OverlayService'in ModuleCard'ında) ve
 * keybind dispatch'inde ([KeybindManager]) uygulanıyor.
 *
 * Anahtar bir kere girildikten sonra, aktif kaldığı sürece (süresi dolana
 * kadar) modül listesi periyodik olarak [refreshModules] ile tazelenir —
 * böylece admin listeye yeni bir modül eklerse/çıkarırsa, kullanıcı anahtarı
 * tekrar girmek zorunda kalmaz.
 */
object PrivateAccessManager {
    private val KEY_STATE = stringPreferencesKey("state")

    @Volatile private var appContext: Context? = null

    data class State(
        val licenseKey: String? = null,
        val expiresAt: Long? = null,            // null = süresiz
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
            Log.e(TAG, "Private access state yüklenemedi", e)
        }
    }

    private fun safeCtx(): Context =
        appContext ?: throw IllegalStateException("PrivateAccessManager.init() çağrılmamış!")

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
            Log.e(TAG, "Private access state parse edilemedi", e)
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
            Log.e(TAG, "Private access state kaydedilemedi", e)
        }
    }

    fun isActive(): Boolean = _state.value.isActive

    /** Modül private listesindeyse VE kullanıcının aktif bir anahtarı yoksa true (kilitli). */
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

    /** Sunucuya anahtarı doğrulatır; başarılıysa yerel state'i günceller. IO thread'inde çalışır. */
    suspend fun redeem(key: String): RedeemResult = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return@withContext RedeemResult.Invalid

        try {
            val body = JSONObject().put("key", trimmed)
            val (code, text) = httpPostJson("$BASE_URL/private-key/verify", body)
                ?: return@withContext RedeemResult.NetworkError("Sunucuya bağlanılamadı")

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
            Log.e(TAG, "Redeem hatası", e)
            RedeemResult.NetworkError(e.message ?: "Bilinmeyen hata")
        }
    }

    /** Aktif bir anahtar varken private modül listesini sunucudan tazeler (anahtar tekrar girilmeden). */
    suspend fun refreshModules() = withContext(Dispatchers.IO) {
        if (!isActive()) return@withContext
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
            Log.e(TAG, "Private modül listesi tazelenemedi", e)
        }
    }

    /** Kullanıcı anahtarı kendi isteğiyle kaldırırsa (örn. "Devre dışı bırak" butonu). */
    fun deactivate() {
        val newState = State()
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
            Log.e(TAG, "HTTP POST hatası: $urlStr", e)
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
            Log.e(TAG, "HTTP GET hatası: $urlStr", e)
            null
        }
    }
}
