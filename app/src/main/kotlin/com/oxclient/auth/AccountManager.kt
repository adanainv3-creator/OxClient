package com.oxclient.auth

import android.content.Context
import com.oxclient.ui.overlay.OverlayLogger
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.accountDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "ox_accounts")

/**
 * AccountManager — kayıtlı Xbox/Bedrock hesaplarını DataStore'da saklar.
 *
 * Relay entegrasyonu:
 *   - [selectedAccount]?.mcToken → LoginPacketListener chain enjeksiyonu
 *   - [selectedAccount]?.isRelayReady() → SessionManager.start() koşul kontrolü
 *
 * Thread-safety: tüm DataStore operasyonları coroutine scope'da çalışır;
 * blocking getter'lar (UI/relay başlangıcı için) runBlocking ile senkronize edilir.
 */
object AccountManager {

    private const val TAG = "AccountManager"

    // ── DataStore Keys ─────────────────────────────────────────────────

    private val KEY_ACCOUNTS         = stringPreferencesKey("accounts_json")
    private val KEY_SELECTED_ACCOUNT = stringPreferencesKey("selected_account_gamertag")

    // ── Durum ──────────────────────────────────────────────────────────

    private var dataStore: DataStore<Preferences>? = null
    private val gson  = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var _accounts: MutableList<SavedAccount> = mutableListOf()
    @Volatile private var _selectedGamertag: String? = null

    val accounts: List<SavedAccount> get() = _accounts.toList()

    val selectedAccount: SavedAccount?
        get() = _selectedGamertag?.let { tag -> _accounts.find { it.gamertag == tag } }

    // ── Init ──────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (dataStore != null) return
        dataStore = context.accountDataStore

        // ÖNEMLİ: Bu okuma senkron (bloklayarak) yapılmalı.
        // Önceki halinde `scope.launch { ... }` fire-and-forget çalışıyordu;
        // init() hemen geri dönüyor ve çağıran taraf (MicrosoftAuthManager.init)
        // _selectedGamertag henüz doldurulmadan `selectedAccount`'u okuyordu.
        // Bu yüzden kayıtlı hesap olsa bile her açılışta null görünüp
        // kullanıcı yeniden login ekranına düşüyordu.
        // runBlocking burada güvenli: DataStore okuması küçük bir local
        // dosyadan yapılır, sadece uygulama açılışında bir kez çalışır ve
        // birkaç ms sürer.
        runBlocking {
            try {
                val prefs = withContext(Dispatchers.IO) { dataStore!!.data.first() }

                val accountsJson = prefs[KEY_ACCOUNTS]
                if (!accountsJson.isNullOrBlank()) {
                    val type = object : TypeToken<List<SavedAccount>>() {}.type
                    val loaded = gson.fromJson<List<SavedAccount>>(accountsJson, type)
                    _accounts = loaded.toMutableList()
                    OverlayLogger.d(TAG, "Yüklendi: ${_accounts.size} hesap")
                }

                _selectedGamertag = prefs[KEY_SELECTED_ACCOUNT]
                OverlayLogger.d(TAG, "Seçili hesap: $_selectedGamertag")

            } catch (e: Exception) {
                OverlayLogger.e(TAG, "AccountManager init hatası", e)
            }
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────

    fun addAccount(account: SavedAccount) {
        // Varsa güncelle, yoksa ekle
        val idx = _accounts.indexOfFirst { it.gamertag == account.gamertag }
        if (idx >= 0) _accounts[idx] = account else _accounts.add(account)
        persist()
        OverlayLogger.i(TAG, "Hesap eklendi/güncellendi: ${account.gamertag}")
        OverlayLogger.d(TAG, "  pubKey(ilk32)=${account.publicKeyB64.take(32)}… privKey.boş=${account.privateKeyB64.isBlank()}")
    }

    fun removeAccount(account: SavedAccount) {
        _accounts.removeAll { it.gamertag == account.gamertag }
        if (_selectedGamertag == account.gamertag) clearSelectedAccount()
        persist()
        OverlayLogger.i(TAG, "Hesap silindi: ${account.gamertag}")
    }

    fun selectAccount(account: SavedAccount) {
        _selectedGamertag = account.gamertag
        scope.launch {
            dataStore?.edit { it[KEY_SELECTED_ACCOUNT] = account.gamertag }
        }
        OverlayLogger.d(TAG, "Hesap seçildi: ${account.gamertag}")
    }

    fun clearSelectedAccount() {
        _selectedGamertag = null
        scope.launch {
            dataStore?.edit { it.remove(KEY_SELECTED_ACCOUNT) }
        }
    }

    /** Token yenilendiğinde hesabı güncelle ve persist et. */
    fun refreshAccount(gamertag: String, newMcToken: String, newExpireMs: Long, newPrivateKeyB64: String? = null, newPublicKeyB64: String? = null) {
        val idx = _accounts.indexOfFirst { it.gamertag == gamertag }
        if (idx < 0) {
            OverlayLogger.w(TAG, "refreshAccount: hesap bulunamadı → $gamertag")
            return
        }
        _accounts[idx] = _accounts[idx].copy(
            mcToken      = newMcToken,
            expireTimeMs = newExpireMs,
            privateKeyB64 = newPrivateKeyB64 ?: _accounts[idx].privateKeyB64,
            publicKeyB64  = newPublicKeyB64 ?: _accounts[idx].publicKeyB64
        )
        persist()
        OverlayLogger.i(TAG, "Token yenilendi: $gamertag")
        OverlayLogger.d(TAG, "  yeni pubKey(ilk32)=${_accounts[idx].publicKeyB64.take(32)}…")
    }

    // ── Relay Yardımcıları ────────────────────────────────────────────

    /**
     * Relay başlatmadan önce hesabın geçerli olduğunu doğrular.
     * Token süresi dolduysa null döndürür.
     */
    fun getRelayReadyAccount(): SavedAccount? =
        selectedAccount?.takeIf { it.isRelayReady() }

    /**
     * Seçili hesabın mcToken'ını döndürür.
     * LoginPacketListener bunu chain enjeksiyonu için kullanır.
     */
    fun getActiveChain(): String? =
        selectedAccount?.mcToken?.takeIf { it.isNotBlank() }

    // ── Persist ───────────────────────────────────────────────────────

    private fun persist() {
        scope.launch {
            try {
                val json = gson.toJson(_accounts)
                dataStore?.edit { prefs ->
                    prefs[KEY_ACCOUNTS] = json
                    _selectedGamertag?.let { prefs[KEY_SELECTED_ACCOUNT] = it }
                        ?: prefs.remove(KEY_SELECTED_ACCOUNT)
                }
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "Persist hatası", e)
            }
        }
    }
}
