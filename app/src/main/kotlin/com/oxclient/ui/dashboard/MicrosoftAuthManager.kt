package com.oxclient.ui.dashboard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession.FullBedrockSession
import net.raphimc.minecraftauth.step.msa.StepMsaDeviceCode
import net.raphimc.minecraftauth.util.MicrosoftConstants
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClientBuilder
import java.io.File
import java.util.concurrent.TimeUnit

object MicrosoftAuthManager {

    private val TOKEN_REFRESH_INTERVAL_MS  = TimeUnit.MINUTES.toMillis(30)
    private val TOKEN_REFRESH_THRESHOLD_MS = TimeUnit.HOURS.toMillis(2)

    /**
     * Referans: RealmsAuthFlow.BEDROCK_DEVICE_CODE_LOGIN_WITH_REALMS ile aynı yapı.
     */
    val BEDROCK_FLOW = MinecraftAuth.builder()
        .withClientId(MicrosoftConstants.BEDROCK_ANDROID_TITLE_ID)
        .withScope(MicrosoftConstants.SCOPE_TITLE_AUTH)
        .deviceCode()
        .withDeviceToken("Android")
        .sisuTitleAuthentication(MicrosoftConstants.BEDROCK_XSTS_RELYING_PARTY)
        .buildMinecraftBedrockChainStep(true, true)

    private val gson  = GsonBuilder().setPrettyPrinting().create()
    private val scope = CoroutineScope(
        Dispatchers.IO + CoroutineName("OxAuthScope") + SupervisorJob()
    )

    // ── State ─────────────────────────────────────────────────────────────

    sealed class AuthState {
        object Idle    : AuthState()
        object Loading : AuthState()
        /** directVerificationUri WebView'a yüklenecek */
        data class WebViewReady(val loginUrl: String) : AuthState()
        data class Success(val gamertag: String, val token: String) : AuthState()
        data class Error(val msg: String) : AuthState()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _accounts = mutableStateListOf<FullBedrockSession>()
    val accounts: List<FullBedrockSession> get() = _accounts

    var selectedAccount: FullBedrockSession? by mutableStateOf(null)
        private set

    private lateinit var cacheDir: File
    private var initialized    = false
    private var activeSignInJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        cacheDir = File(context.filesDir, "ox_accounts").apply { mkdirs() }
        loadAccounts()
        startRefreshLoop()
    }

    // ── Sign In — WebView akışı (referans: AuthWebView.addAccount()) ───────
    //
    //  MinecraftAuth.createHttpClient() kullan (referans ile aynı)
    //  directVerificationUri → WebView'a yükle, kullanıcı orada giriş yapsın
    //  getFromInput() WebView login bitince session'ı döndürür.

    fun signIn() {
        if (_authState.value is AuthState.Loading ||
            _authState.value is AuthState.WebViewReady) return
        _authState.value = AuthState.Loading

        activeSignInJob = scope.launch {
            try {
                val requestConfig = RequestConfig.custom()
                    .setConnectTimeout(10_000)
                    .setSocketTimeout(30_000)
                    .build()
                val httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(requestConfig)
                    .build()

                val session: FullBedrockSession = BEDROCK_FLOW.getFromInput(
                    httpClient,
                    StepMsaDeviceCode.MsaDeviceCodeCallback { deviceCode ->
                        // directVerificationUri = kod pre-filled Microsoft login URL
                        _authState.value = AuthState.WebViewReady(deviceCode.directVerificationUri)
                    }
                )

                // WebView tamamladı → session alındı
                addAccount(session)
                selectAccount(session)
                _authState.value = AuthState.Success(
                    session.mcChain.displayName,
                    session.mcChain.xblXsts.token
                )

            } catch (e: CancellationException) {
                _authState.value = AuthState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                _authState.value = AuthState.Error(e.message ?: "Giriş başarısız")
            }
        }
    }

    fun cancelSignIn() {
        activeSignInJob?.cancel()
        activeSignInJob = null
        _authState.value = AuthState.Idle
    }

    // ── Sign Out ──────────────────────────────────────────────────────────

    fun signOut() {
        selectedAccount?.let { removeAccount(it) }
        selectedAccount = null
        _authState.value = AuthState.Idle
        File(cacheDir, "selectedAccount").delete()
    }

    // ── Account Management ────────────────────────────────────────────────

    fun addAccount(session: FullBedrockSession) {
        _accounts.removeAll { it.mcChain.displayName == session.mcChain.displayName }
        _accounts.add(session)
        saveAccount(session)
    }

    fun removeAccount(session: FullBedrockSession) {
        _accounts.remove(session)
        File(cacheDir, "${session.mcChain.displayName}.json").delete()
    }

    fun selectAccount(session: FullBedrockSession?) {
        selectedAccount = session
        if (session != null) {
            _authState.value = AuthState.Success(
                session.mcChain.displayName,
                session.mcChain.xblXsts.token
            )
            File(cacheDir, "selectedAccount").writeText(session.mcChain.displayName)
        } else {
            _authState.value = AuthState.Idle
        }
    }

    // ── Token Refresh — referans: AccountManager.refreshExpiredTokens() ──

    private fun startRefreshLoop() {
        scope.launch {
            while (isActive) {
                delay(TOKEN_REFRESH_INTERVAL_MS)
                refreshTokens()
            }
        }
    }

    private fun refreshTokens() {
        if (_accounts.isEmpty()) return
        val now = System.currentTimeMillis()

        val refreshConfig = RequestConfig.custom()
            .setConnectTimeout(10_000)
            .setSocketTimeout(10_000)
            .build()
        val httpClient = HttpClientBuilder.create()
            .setDefaultRequestConfig(refreshConfig)
            .build()

        _accounts.toList().forEachIndexed { i, acc ->
            try {
                val msaExpire = acc.mcChain.xblXsts
                    .initialXblSession?.msaToken?.expireTimeMs ?: 0L
                val xblExpire = acc.mcChain.xblXsts.expireTimeMs ?: 0L
                val needsRefresh = (msaExpire - now < TOKEN_REFRESH_THRESHOLD_MS) ||
                                   (xblExpire  - now < TOKEN_REFRESH_THRESHOLD_MS)

                if (needsRefresh) {
                    val refreshed = BEDROCK_FLOW.refresh(httpClient, acc)
                    _accounts[i] = refreshed
                    if (selectedAccount?.mcChain?.displayName == acc.mcChain.displayName) {
                        selectAccount(refreshed)
                    }
                    saveAccount(refreshed)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ── Persistence — referans: AccountManager.fetchAccounts() ───────────

    private fun loadAccounts() {
        val selectedName = runCatching {
            File(cacheDir, "selectedAccount").readText().trim()
        }.getOrNull()

        cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.extension != "json") return@forEach
            runCatching {
                val json = JsonParser.parseString(file.readText()).asJsonObject
                val acc  = try {
                    BEDROCK_FLOW.fromJson(json)
                } catch (e: Exception) {
                    MinecraftAuth.BEDROCK_DEVICE_CODE_LOGIN.fromJson(json)
                }
                _accounts.add(acc)
                if (acc.mcChain.displayName == selectedName) {
                    selectedAccount = acc
                    _authState.value = AuthState.Success(
                        acc.mcChain.displayName,
                        acc.mcChain.xblXsts.token
                    )
                }
            }.onFailure {
                println("OxAuth: hesap yüklenemedi ${file.name}: ${it.message}")
            }
        }
    }

    private fun saveAccount(acc: FullBedrockSession) {
        runCatching {
            val json = try {
                BEDROCK_FLOW.toJson(acc)
            } catch (e: Exception) {
                MinecraftAuth.BEDROCK_DEVICE_CODE_LOGIN.toJson(acc)
            }
            File(cacheDir, "${acc.mcChain.displayName}.json")
                .writeText(gson.toJson(json))
        }.onFailure {
            println("OxAuth: kayıt başarısız ${acc.mcChain.displayName}: ${it.message}")
        }
    }
}
