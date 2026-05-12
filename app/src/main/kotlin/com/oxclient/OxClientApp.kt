package com.oxclient

import android.app.Application
import android.util.Log
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.config.ServerConfig
import com.oxclient.definition.Definitions

class OxClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("OxClientApp", "Başlatılıyor")
        ServerConfig.init(this)
        MicrosoftAuthManager.init(this)
        Definitions.loadBlockPalette()
        Log.d("OxClientApp", "Başlatma tamamlandı")
    }
}
