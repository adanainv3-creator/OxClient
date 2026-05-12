
package com.oxclient.events

import android.util.Log
import com.oxclient.core.relay.OxRelaySession
import java.util.concurrent.CopyOnWriteArrayList

object PacketEventBus {
    private const val TAG = "PacketEventBus"

    private val listeners = CopyOnWriteArrayList<PacketListener>()

    private val _sessionLocal = ThreadLocal<OxRelaySession?>()
    val currentSession: OxRelaySession? get() = _sessionLocal.get()

    fun register(l: PacketListener) {
        if (listeners.contains(l)) return
        listeners.add(l)
        listeners.sortBy { it.priority }
        Log.d(TAG, "Registered: ${l.javaClass.simpleName}")
    }

    fun unregister(l: PacketListener) { listeners.remove(l) }

    fun clear() { listeners.clear() }

    fun publish(event: PacketEvent) {
        _sessionLocal.set(event.session)
        for (l in listeners) {
            try { l.onPacket(event) } catch (e: Exception) {
                Log.e(TAG, "${l.javaClass.simpleName} hata", e)
            }
            if (event.isCancelled) break
        }
        _sessionLocal.set(null)
    }

    interface PacketListener {
        val priority: Int get() = 100
        fun onPacket(event: PacketEvent)
    }
}
