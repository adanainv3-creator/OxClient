package com.oxclient

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import com.oxclient.auth.AccountManager
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.config.Config
import com.oxclient.config.ServerConfig
import com.oxclient.core.relay.Definitions
import com.oxclient.module.ModuleManager
import com.oxclient.module.social.FriendManager
import com.oxclient.module.combat.AutoArmor
import com.oxclient.module.movement.AutoScaffold  // Yeni eklenen import
import com.oxclient.module.combat.AntiCrystal
import com.oxclient.module.combat.AutoTotem
import com.oxclient.module.combat.Criticals
import com.oxclient.module.combat.CrystalAura
import com.oxclient.module.combat.KillAura
import com.oxclient.module.combat.KillAuraPro
import com.oxclient.module.combat.PcAura
import com.oxclient.module.misc.ChatSpammer
import com.oxclient.module.misc.ChatAdvertiser
import com.oxclient.module.misc.Disconnect
import com.oxclient.module.movement.AntiKnockback
import com.oxclient.module.movement.CreativeFly
import com.oxclient.module.movement.Jetpack
import com.oxclient.module.movement.MotionFly
import com.oxclient.module.movement.Speed
import com.oxclient.module.movement.Timer
import com.oxclient.module.movement.TPAura
import com.oxclient.module.movement.TPAuraPC
import com.oxclient.module.visual.ArrayListModule
import com.oxclient.module.visual.ESP
import com.oxclient.module.visual.FOVChanger
import com.oxclient.module.visual.FullBright
import com.oxclient.module.visual.EnemyESP
import com.oxclient.module.visual.Xray
import com.oxclient.module.combat.AirFight
import com.oxclient.module.combat.HeadTrack
import com.oxclient.utils.WorldBlockTracker
import com.oxclient.utils.OreTracker

class OxClientApp : Application() {

    companion object {
        private const val TAG = "OxClientApp"
        lateinit var instance: OxClientApp
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
                Log.e(TAG, "Definitions yükleme hatası: ${e.message}", e)
            }
        }, "OxDefinitionsLoader").apply {
            isDaemon = true
            start()
        }

        WorldBlockTracker.init()
        OreTracker.init()
        registerModules()
    }

    /** Yakalanmamış hataları Downloads/baba.txt dosyasına yazar, sonra normal çökme akışına devam eder. */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val content = "${java.util.Date()}\n\n${Log.getStackTraceString(throwable)}"
                writeCrashToDownloads(content)
            } catch (e: Exception) {
                Log.e(TAG, "Crash log yazılamadı: ${e.message}", e)
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
            PcAura(),
            CrystalAura(),
            AntiCrystal(),
            AutoTotem(),
            Criticals(),
            MotionFly(),
            CreativeFly(),
            Speed(),
            Jetpack(),
            Timer(),
            TPAura(),
            AntiKnockback(),
            FullBright(),
            ESP(),
            EnemyESP(),
            Xray(),
            AirFight(),
            HeadTrack(),
            FOVChanger(),
            ArrayListModule(),
            AutoArmor(),
            TPAuraPC(),
            AutoScaffold(),  // AutoRegear kaldırıldı, AutoScaffold eklendi
            ChatSpammer(),
            ChatAdvertiser(),
            Disconnect()
        )
    }
}