package com.oxclient

import android.app.Application
import android.util.Log
import com.oxclient.auth.AccountManager
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.config.ServerConfig
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
