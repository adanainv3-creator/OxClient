package com.oxclient.session

import com.oxclient.core.entity.EntityTracker
import com.oxclient.core.proxy.MitmProxy
import timber.log.Timber

object SessionManager {
    @Volatile
    var proxy: MitmProxy? = null
        private set

    val entityTracker = EntityTracker()

    fun onSessionStart(p: MitmProxy) {
        proxy = p
        entityTracker.clear()
        Timber.i("Session başladı")
    }

    fun onSessionStop() {
        proxy = null
        entityTracker.clear()
        Timber.i("Session sona erdi")
    }

    val isActive: Boolean get() = proxy != null
}