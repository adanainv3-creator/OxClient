package com.oxclient

import android.app.Application
import com.oxclient.module.ModuleManager
import timber.log.Timber

class OxClientApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        ModuleManager.init(this)
        Timber.i("OxClient ${BuildConfig.VERSION_NAME} started — target: ${BuildConfig.SERVER_HOST}")
    }
}
