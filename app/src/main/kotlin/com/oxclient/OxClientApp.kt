package com.oxclient

import android.app.Application
import android.util.Log
import com.oxclient.auth.AccountManager
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.config.ServerConfig
import com.oxclient.core.relay.Definitions
import com.oxclient.module.ModuleManager
import com.oxclient.module.combat.AutoArmor
import com.oxclient.module.combat.AutoTotem
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.CrystalAura
import com.oxclient.module.combat.KillAura
import com.oxclient.module.misc.ChatSpammer
import com.oxclient.module.movement.AntiKnockback
import com.oxclient.module.movement.Jetpack
import com.oxclient.module.movement.MotionFly
import com.oxclient.module.movement.TPAura
import com.oxclient.module.visual.ArrayListModule
import com.oxclient.module.visual.ESP
import com.oxclient.module.visual.FOVChanger
import com.oxclient.module.visual.FullBright
import com.oxclient.module.visual.EnemyESP
import com.oxclient.module.combat.AirFight
import com.oxclient.utils.WorldBlockTracker

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

        WorldBlockTracker.init()
        registerModules()
    }

    private fun registerModules() {
        ModuleManager.registerAll(
            KillAura(),
            CrystalAura(),
            AutoTotem(),
            Criticals(),
            MotionFly(),
            Jetpack(),
            TPAura(),
            AntiKnockback(),
            FullBright(),
            ESP(),
            EnemyESP(),
            AirFight()
            FOVChanger(),
            ArrayListModule(),
            AutoArmor(),
            ChatSpammer()
        )
    }
}