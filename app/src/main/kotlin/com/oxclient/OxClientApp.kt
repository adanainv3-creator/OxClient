package com.oxclient

import android.app.Application
import com.oxclient.auth.AccountManager
import com.oxclient.module.ModuleManager
import com.oxclient.session.ServerConfig
import timber.log.Timber

class OxClientApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        ServerConfig.init(this)
        AccountManager.init(this)
        ModuleManager.init(this)

        Timber.i("OxClient ${BuildConfig.VERSION_NAME} başlatıldı")
    }
}
