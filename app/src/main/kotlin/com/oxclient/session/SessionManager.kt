package com.oxclient.session

import com.oxclient.core.entity.EntityTracker
import com.oxclient.core.proxy.MitmProxy

object SessionManager {
    @Volatile var proxy: MitmProxy? = null
    val entityTracker = EntityTracker()

    fun onSessionStart(p: MitmProxy) { proxy = p; entityTracker.clear() }
    fun onSessionStop()              { proxy = null; entityTracker.clear() }
}
