package com.nexoraclient

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import com.nexoraclient.auth.AccountManager
import com.nexoraclient.auth.MicrosoftAuthManager
import com.nexoraclient.config.Config
import com.nexoraclient.config.ServerConfig
import com.nexoraclient.core.relay.Definitions
import com.nexoraclient.module.ModuleManager
import com.nexoraclient.module.social.FriendManager
import com.nexoraclient.module.combat.AutoArmor
import com.nexoraclient.module.movement.AutoScaffold  // Yeni eklenen import
import com.nexoraclient.module.combat.AutoTotem
import com.nexoraclient.module.combat.Criticals
import com.nexoraclient.module.combat.CrystalAura
import com.nexoraclient.module.combat.KillAura
import com.nexoraclient.module.combat.KillAuraPro
import com.nexoraclient.module.combat.PcAura
import com.nexoraclient.module.misc.ChatSpammer
import com.nexoraclient.module.misc.ChatAdvertiser
import com.nexoraclient.module.misc.Disconnect
import com.nexoraclient.module.movement.AntiKnockback
import com.nexoraclient.module.movement.CreativeFly
import com.nexoraclient.module.movement.Jetpack
import com.nexoraclient.module.movement.MotionFly
import com.nexoraclient.module.movement.Speed
import com.nexoraclient.module.movement.TPAura
import com.nexoraclient.module.movement.TPAuraPC
import com.nexoraclient.module.visual.ArrayListModule
import com.nexoraclient.module.visual.ESP
import com.nexoraclient.module.visual.FOVChanger
import com.nexoraclient.module.visual.FullBright
import com.nexoraclient.module.visual.EnemyESP
import com.nexoraclient.module.visual.Xray
import com.nexoraclient.module.combat.HeadTrack
import com.nexoraclient.utils.WorldBlockTracker
import com.nexoraclient.utils.OreTracker

class NexoraClientApp : Application() {

    companion object {
        private const val TAG = "NexoraClientApp"
        lateinit var instance: NexoraClientApp
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
        }, "NexoraDefinitionsLoader").apply {
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
            Xray(),
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