package com.oxclient.events

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PacketEventBus
 *
 * Senkron listener listesi + asenkron Flow arayüzü sağlar.
 * OxVpnService içindeki PacketProcessor tarafından publish edilir,
 * modüller onPacket() ile dinler.
 */
object PacketEventBus {
    private const val TAG = "PacketEventBus"

    private val listeners = CopyOnWriteArrayList<PacketListener>()
    private val _flow     = MutableSharedFlow<PacketEvent>(replay = 0, extraBufferCapacity = 128)
    val flow: SharedFlow<PacketEvent> = _flow.asSharedFlow()

    fun register(l: PacketListener) {
        if (listeners.contains(l)) return
        listeners.add(l)
        listeners.sortBy { it.priority }
        Log.d(TAG, "Registered: ${l.javaClass.simpleName}")
    }

    fun unregister(l: PacketListener) { listeners.remove(l) }

    fun clear() { listeners.clear() }

    @JvmStatic
    fun publish(event: PacketEvent) {
        for (l in listeners) {
            try { l.onPacket(event) } catch (e: Exception) {
                Log.e(TAG, "${l.javaClass.simpleName} threw", e)
            }
            if (event.isCancelled) break
        }
        _flow.tryEmit(event)
    }
}

interface PacketListener {
    val priority: Int get() = 100
    fun onPacket(event: PacketEvent)
}
