package com.rubidiumclient

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import com.rubidiumclient.auth.AccountManager
import com.rubidiumclient.auth.MicrosoftAuthManager
import com.rubidiumclient.config.Config
import com.rubidiumclient.config.ServerConfig
import com.rubidiumclient.core.relay.Definitions
import com.rubidiumclient.module.ModuleManager
import com.rubidiumclient.module.social.FriendManager
import com.rubidiumclient.module.combat.AutoArmor
import com.rubidiumclient.module.movement.AutoScaffold
import com.rubidiumclient.module.misc.AutoMapArt
import com.rubidiumclient.module.combat.AutoTotem
import com.rubidiumclient.module.combat.Criticals
import com.rubidiumclient.module.combat.CrystalAura
import com.rubidiumclient.module.misc.PopCounter
import com.rubidiumclient.module.movement.BypassFly
import com.rubidiumclient.module.combat.KillAura
import com.rubidiumclient.module.combat.KillAuraPro
import com.rubidiumclient.module.misc.ChatSpammer
import com.rubidiumclient.module.misc.ChatAdvertiser
import com.rubidiumclient.module.misc.ComboShortcut
import com.rubidiumclient.module.misc.CommandHelper
import com.rubidiumclient.module.misc.AutoPositionGoing
import com.rubidiumclient.module.misc.AutoBaseFinder
import com.rubidiumclient.module.misc.Disconnect
import com.rubidiumclient.module.movement.AntiKnockback
import com.rubidiumclient.module.movement.CreativeFly
import com.rubidiumclient.module.movement.Jetpack
import com.rubidiumclient.module.movement.ElytraFly
import com.rubidiumclient.module.movement.MotionFly
import com.rubidiumclient.module.combat.AimBot
import com.rubidiumclient.module.movement.Speed
import com.rubidiumclient.module.combat.TPAura
import com.rubidiumclient.module.visual.ArrayListModule
import com.rubidiumclient.module.visual.ESP
import com.rubidiumclient.module.visual.FOVChanger
import com.rubidiumclient.module.visual.FullBright
import com.rubidiumclient.module.visual.EnemyESP
import com.rubidiumclient.module.visual.Xray
import com.rubidiumclient.module.combat.HeadTrack
import com.rubidiumclient.module.combat.V2Aura
import com.rubidiumclient.module.combat.LegitAura
import com.rubidiumclient.module.movement.AirJump
import com.rubidiumclient.module.movement.FreeCamera
import com.rubidiumclient.module.movement.LifeboatFly
import com.rubidiumclient.module.movement.NoClipModule
import com.rubidiumclient.utils.WorldBlockTracker
import com.rubidiumclient.utils.OreTracker

class RubidiumClientApp : Application() {

    companion object {
        private const val TAG = "RubidiumClientApp"
        lateinit var instance: RubidiumClientApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        installCrashLogger()

        ServerConfig.init(applicationContext)
        Config.init(applicationContext)
        AccountManager.init(applicationContext)
        MicrosoftAuthManager.init(applicationContext)
        FriendManager.init(applicationContext)

        Thread({
            try {
                Definitions.init(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Definitions load error: ${e.message}", e)
            }
        }, "RubidiumDefinitionsLoader").apply {
            isDaemon = true
            start()
        }

        WorldBlockTracker.init()
        OreTracker.init()
        registerModules()
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val content = "${java.util.Date()}\n\n${Log.getStackTraceString(throwable)}"
                writeCrashToDownloads(content)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log: ${e.message}", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashToDownloads(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "baba.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloadsDir, "baba.txt").writeText(content)
        }
    }

    private fun registerModules() {
        ModuleManager.registerAll(
            KillAura(),
            KillAuraPro(),
            CrystalAura(),
            AutoTotem(),
            Criticals(),
            MotionFly(),
            CreativeFly(),
            Speed(),
            Jetpack(),
            ElytraFly(),
            TPAura(),
            AimBot(),
            AntiKnockback(),
            FullBright(),
            ESP(),
            EnemyESP(),
            Xray(),
            PopCounter(),
            HeadTrack(),
            FOVChanger(),
            ArrayListModule(),
            AutoArmor(),
            ChatSpammer(),
            ChatAdvertiser(),
            Disconnect(),
            AutoBaseFinder(),
            ComboShortcut(1),
            ComboShortcut(2),
            LegitAura(),
            AirJump(),
            FreeCamera(),
            LifeboatFly(),
            NoClipModule()
        )
    }
}