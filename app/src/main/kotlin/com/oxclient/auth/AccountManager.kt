package com.oxclient.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.GsonBuilder
import java.io.File

/**
 * AccountManager — Minecraft Bedrock hesaplarını diske kaydeder ve yükler.
 *
 * currentAccount → LoginPacketListener tarafından kullanılan aktif hesap alias'ı
 */
object AccountManager {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val _accounts: MutableList<SavedAccount> = mutableStateListOf()
    val accounts: List<SavedAccount> get() = _accounts

    var selectedAccount: SavedAccount? by mutableStateOf(null)
        private set

    /** LoginPacketListener için alias */
    val currentAccount: SavedAccount? get() = selectedAccount

    private lateinit var cacheDir: File
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        cacheDir = File(context.filesDir, "ox_accounts").apply { mkdirs() }
        loadFromDisk()
    }

    fun addAccount(account: SavedAccount) {
        _accounts.removeAll { it.gamertag == account.gamertag }
        _accounts.add(account)
        saveToDisk(account)
    }

    fun removeAccount(account: SavedAccount) {
        _accounts.remove(account)
        File(cacheDir, "${account.gamertag}.json").delete()
        if (selectedAccount?.gamertag == account.gamertag) {
            selectedAccount = null
            File(cacheDir, "selected").delete()
        }
    }

    fun selectAccount(account: SavedAccount?) {
        selectedAccount = account
        val sel = File(cacheDir, "selected")
        if (account != null) sel.writeText(account.gamertag) else sel.delete()
    }

    fun clearSelectedAccount() {
        selectedAccount = null
        File(cacheDir, "selected").delete()
    }

    private fun loadFromDisk() {
        val selectedName = runCatching {
            File(cacheDir, "selected").readText().trim()
        }.getOrNull()

        cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.extension != "json") return@forEach
            runCatching {
                val acc = gson.fromJson(file.readText(), SavedAccount::class.java)
                _accounts.add(acc)
                if (acc.gamertag == selectedName) selectedAccount = acc
            }.onFailure {
                android.util.Log.w("AccountManager", "Yüklenemedi: ${file.name} — ${it.message}")
            }
        }
    }

    private fun saveToDisk(account: SavedAccount) {
        runCatching {
            File(cacheDir, "${account.gamertag}.json").writeText(gson.toJson(account))
        }.onFailure {
            android.util.Log.e("AccountManager", "Kaydedilemedi: ${account.gamertag} — ${it.message}")
        }
    }
}
