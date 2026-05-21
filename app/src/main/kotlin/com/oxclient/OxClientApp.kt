package com.oxclient

import android.app.Application
import android.util.Log
import com.oxclient.auth.AccountManager
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.config.ServerConfig
import com.oxclient.core.relay.Definitions
import com.oxclient.module.ModuleManager
import com.oxclient.module.combat.AutoTotem
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.CrystalAura
import com.oxclient.module.combat.KillAura
import com.oxclient.module.movement.Jetpack
import com.oxclient.module.movement.TPAura
import com.oxclient.module.visual.ESP
import com.oxclient.module.visual.FullBright

class OxClientApp : Application() {

    companion object {
        private const val TAG = "OxClientApp"
        lateinit var instance: OxClientApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServerConfig.init(applicationContext)
        AccountManager.init(applicationContext)
        MicrosoftAuthManager.init(applicationContext)

        // Block/item/camera definitions — background thread'de yükle
        // AutoCodecListener bunları RequestNetworkSettings gelince hemen kullanır
        Thread({
            try {
                Definitions.init(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Definitions yükleme hatası: ${e.message}", e)
            }
        }, "OxDefinitionsLoader").apply {
            isDaemon = true
            start()
        }

        registerModules()
    }

    private fun registerModules() {
        ModuleManager.registerAll(
            KillAura(),
            CrystalAura(),
            AutoTotem(),
            Criticals(),
            Jetpack(),
            TPAura(),
            FullBright(),
            ESP()
        )
    }
}
