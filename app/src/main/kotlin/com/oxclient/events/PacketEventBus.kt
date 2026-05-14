package com.oxclient.events

import android.util.Log
import com.oxclient.core.relay.OxRelaySession
import java.util.concurrent.CopyOnWriteArrayList

object PacketEventBus {

    private const val TAG = "PacketEventBus"

    private val listeners = CopyOnWriteArrayList<PacketListener>()

    @Volatile var currentSession: OxRelaySession? = null
        private set

    private val _stats = PublishStats()

    val stats: PublishStats get() = _stats

    fun setSession(session: OxRelaySession?) {
        currentSession = session
        Log.d(TAG, if (session != null) "Session set: ${session.clientAddress}" else "Session cleared")
    }

    fun register(l: PacketListener) {
        if (listeners.contains(l)) return
        listeners.add(l)
        listeners.sortBy { it.priority }
        Log.d(TAG, "Registered: ${l.javaClass.simpleName} (priority=${l.priority})")
    }

    fun unregister(l: PacketListener) {
        listeners.remove(l)
        Log.d(TAG, "Unregistered: ${l.javaClass.simpleName}")
    }

    fun clear() {
        listeners.clear()
        currentSession = null
        _stats.reset()
        Log.d(TAG, "EventBus temizlendi")
    }

    fun publish(event: PacketEvent) {
        _stats.record(event)
        for (l in listeners) {
            try {
                l.onPacket(event)
            } catch (e: Exception) {
                Log.e(TAG, "${l.javaClass.simpleName} hata: ${e.message}", e)
            }
            if (event.isCancelled) {
                _stats.recordCancelled()
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

    class PublishStats {
        @Volatile var totalPublished: Long = 0L
            private set
        @Volatile var totalCancelled: Long = 0L
            private set
        @Volatile var clientToServer: Long = 0L
            private set
        @Volatile var serverToClient: Long = 0L
            private set
        @Volatile var lastPacketName: String = ""
            private set

        internal fun record(event: PacketEvent) {
            totalPublished++
            lastPacketName = event.packetName
            if (event.isClientToServer) clientToServer++ else serverToClient++
        }

        internal fun recordCancelled() { totalCancelled++ }

        internal fun reset() {
            totalPublished = 0; totalCancelled = 0
            clientToServer = 0; serverToClient = 0
            lastPacketName = ""
        }
    }
}
