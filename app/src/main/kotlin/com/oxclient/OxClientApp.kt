package com.oxclient

import android.app.Application
import android.util.Log
import com.oxclient.auth.AccountManager
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.config.Config
import com.oxclient.config.ServerConfig
import com.oxclient.core.relay.Definitions
import com.oxclient.module.ModuleManager
import com.oxclient.module.social.FriendManager
import com.oxclient.module.combat.AutoArmor
import com.oxclient.module.combat.AntiCrystal
import com.oxclient.module.combat.AutoTotem
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.CrystalAura
import com.oxclient.module.combat.KillAura
import com.oxclient.module.combat.KillAuraPro
import com.oxclient.module.misc.ChatSpammer
import com.oxclient.module.misc.ChatAdvertiser
import com.oxclient.module.misc.Disconnect
import com.oxclient.module.movement.AntiKnockback
import com.oxclient.module.movement.CreativeFly
import com.oxclient.module.movement.Jetpack
import com.oxclient.module.movement.MotionFly
import com.oxclient.module.movement.Speed
import com.oxclient.module.movement.TPAura
import com.oxclient.module.visual.ArrayListModule
import com.oxclient.module.visual.ESP
import com.oxclient.module.visual.FOVChanger
import com.oxclient.module.visual.FullBright
import com.oxclient.module.visual.EnemyESP
import com.oxclient.module.combat.AirFight
import com.oxclient.module.combat.HeadTrack
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
        Config.init(applicationContext)
        AccountManager.init(applicationContext)
        MicrosoftAuthManager.init(applicationContext)
        FriendManager.init(applicationContext)

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
            KillAuraPro(),
            CrystalAura(),
            AntiCrystal(),
            AutoTotem(),
            Criticals(),
            MotionFly(),
            CreativeFly(),
            Speed(),
            Jetpack(),
            TPAura(),
            AntiKnockback(),
            FullBright(),
            ESP(),
            EnemyESP(),
            AirFight(),
            HeadTrack(),
            FOVChanger(),
            ArrayListModule(),
            AutoArmor(),
            ChatSpammer(),
            ChatAdvertiser(),
            Disconnect()
        )
    }
}
