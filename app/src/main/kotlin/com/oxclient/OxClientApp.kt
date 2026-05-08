package com.oxclient

import android.app.Application
import android.util.Log
import com.oxclient.config.ServerConfig
import com.oxclient.module.ModuleManager

class OxClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("OxClientApp", "Uygulama başlatılıyor")

        // Sunucu konfigürasyonunu başlat
        ServerConfig.init(this)

        // Modül yöneticisini başlat
        ModuleManager.init()

        Log.d("OxClientApp", "Başlatma tamamlandı")
    }
}
