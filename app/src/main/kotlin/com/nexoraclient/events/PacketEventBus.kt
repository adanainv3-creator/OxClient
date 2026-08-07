package com.rubidiumclient.events

import com.rubidiumclient.core.relay.RubidiumRelaySession
import java.util.concurrent.CopyOnWriteArrayList

object PacketEventBus {

    private const val TAG = "PacketEventBus"

    private val listeners = CopyOnWriteArrayList<PacketListener>()

    @Volatile var currentSession: RubidiumRelaySession? = null
        private set

    fun setSession(session: RubidiumRelaySession?) {
        currentSession = session
    }

    fun register(l: PacketListener) {
        if (listeners.contains(l)) return
        listeners.add(l)
        listeners.sortBy { it.priority }
    }

    fun unregister(l: PacketListener) {
        listeners.remove(l)
    }

    fun clear() {
        listeners.clear()
        currentSession = null
    }

    fun publish(event: PacketEvent) {
        for (l in listeners) {
            try {
                l.onPacket(event)
            } catch (_: Exception) {
            }
            if (event.isCancelled) {
                break
            }
        }
    }

    fun post(event: PacketEvent) = publish(event)

    val listenerCount: Int get() = listeners.size

    fun getListeners(): List<PacketListener> = listeners.toList()

    interface PacketListener {
        val priority: Int get() = 100
        fun onPacket(event: PacketEvent)
    }
}