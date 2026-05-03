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
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession.FullBedrockSession
import net.raphimc.minecraftauth.step.msa.StepMsaDeviceCode
import net.raphimc.minecraftauth.util.MicrosoftConstants
import org.apache.http.impl.client.HttpClients
import java.io.File
import java.util.concurrent.TimeUnit

object MicrosoftAuthManager {

    private val TOKEN_REFRESH_INTERVAL_MS  = TimeUnit.MINUTES.toMillis(30)
    private val TOKEN_REFRESH_THRESHOLD_MS = TimeUnit.HOURS.toMillis(2)

    /** Device code akışı — Bedrock Android kimliği */
    val BEDROCK_DEVICE_CODE_LOGIN: StepFullBedrockSession =
        MinecraftAuth.builder()
            .withClientId(MicrosoftConstants.BEDROCK_ANDROID_TITLE_ID)
            .withScope(MicrosoftConstants.SCOPE_TITLE_AUTH)
            .deviceCode()
            .withDeviceToken("Android")
            .sisuTitleAuthentication(MicrosoftConstants.BEDROCK_XSTS_RELYING_PARTY)
            .buildMinecraftBedrockChainStep(true, true)

    private val gson  = GsonBuilder().setPrettyPrinting().create()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Auth State ────────────────────────────────────────────────────────

    sealed class AuthState {
        object Idle    : AuthState()
        object Loading : AuthState()
        /** Device code akışında kullanıcıya gösterilecek kod + URL */
        data class DeviceCode(val userCode: String, val verificationUrl: String) : AuthState()
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
    private var initialized = false

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        cacheDir = File(context.filesDir, "accounts").apply { mkdirs() }
        loadAccounts()
        startRefreshLoop()
        initialized = true
    }

    // ── Sign In — Device Code akışı ────────────────────────────────────

    fun signIn() {
        if (_authState.value is AuthState.Loading || _authState.value is AuthState.DeviceCode) return
        _authState.value = AuthState.Loading

        scope.launch {
            try {
                val httpClient = HttpClients.createDefault()

                // Device code adımını bul ve callback ile kodu UI'ya yansıt
                val session: FullBedrockSession = BEDROCK_DEVICE_CODE_LOGIN.getFromInput(
                    httpClient,
                    StepMsaDeviceCode.MsaDeviceCodeCallback { deviceCode ->
                        // Bu callback device code geldiğinde tetiklenir
                        _authState.value = AuthState.DeviceCode(
                            userCode        = deviceCode.userCode,
                            verificationUrl = deviceCode.verificationUri
                        )
                    }
                )

                val name  = session.mcChain.displayName
                val token = session.mcChain.xblXsts.token

                addAccount(session)
                selectAccount(session)
                _authState.value = AuthState.Success(name, token)

            } catch (e: CancellationException) {
                _authState.value = AuthState.Idle
            } catch (e: Exception) {
                _authState.value = AuthState.Error(e.message ?: "Giriş başarısız")
            }
        }
    }

    fun cancelSignIn() {
        scope.coroutineContext.cancelChildren()
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

    // ── Token Refresh ─────────────────────────────────────────────────────

    private fun startRefreshLoop() {
        scope.launch {
            while (isActive) {
                delay(TOKEN_REFRESH_INTERVAL_MS)
                refreshTokens()
            }
        }
    }

    private suspend fun refreshTokens() {
        val now = System.currentTimeMillis()
        val httpClient = HttpClients.createDefault()

        _accounts.forEachIndexed { i, acc ->
            try {
                val expire = acc.mcChain.xblXsts.expireTimeMs ?: 0L
                if (expire - now < TOKEN_REFRESH_THRESHOLD_MS) {
                    val refreshed: FullBedrockSession = BEDROCK_DEVICE_CODE_LOGIN.refresh(
                        httpClient, acc
                    )
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

    // ── Persistence ───────────────────────────────────────────────────────

    private fun loadAccounts() {
        val selectedName = runCatching {
            File(cacheDir, "selectedAccount").readText().trim()
        }.getOrNull()

        cacheDir.listFiles()?.forEach { file ->
            if (file.name == "selectedAccount") return@forEach
            runCatching {
                val json = JsonParser.parseString(file.readText()).asJsonObject
                val acc  = BEDROCK_DEVICE_CODE_LOGIN.fromJson(json)
                _accounts.add(acc)
                if (acc.mcChain.displayName == selectedName) {
                    selectedAccount = acc
                    _authState.value = AuthState.Success(
                        acc.mcChain.displayName,
                        acc.mcChain.xblXsts.token
                    )
                }
            }.onFailure { file.delete() }
        }
    }

    private fun saveAccount(acc: FullBedrockSession) {
        runCatching {
            val json = BEDROCK_DEVICE_CODE_LOGIN.toJson(acc)
            File(cacheDir, "${acc.mcChain.displayName}.json")
                .writeText(gson.toJson(json))
        }
    }
}
