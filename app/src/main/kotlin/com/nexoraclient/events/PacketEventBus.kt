package com.rubidiumclient.events

import com.rubidiumclient.core.relay.RubidiumRelaySession
import java.util.concurrent.CopyOnWriteArrayList

object PacketEventBus {

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
        val snapshot = listeners.toArray()
        for (raw in snapshot) {
            val l = raw as PacketListener
            try { l.onPacket(event) } catch (_: Exception) {}
            // Only break if packet is CANCELLED (rejected completely)
            // If replacementPacket is set, continue to next listener
            // so they can read the modified packet and apply their changes
            if (event.isCancelled) break
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
