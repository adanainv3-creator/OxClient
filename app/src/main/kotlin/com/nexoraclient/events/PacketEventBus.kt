package com.rubidiumclient.events

import com.rubidiumclient.core.relay.RubidiumRelaySession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.LongAdder

object PacketEventBus {

    private const val TAG = "PacketEventBus"

    private val listeners = CopyOnWriteArrayList<PacketListener>()

    @Volatile var currentSession: RubidiumRelaySession? = null
        private set

    private val _stats = PublishStats()

    val stats: PublishStats get() = _stats

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
        _stats.reset()
    }

    fun publish(event: PacketEvent) {
        _stats.record(event)
        for (l in listeners) {
            try {
                l.onPacket(event)
            } catch (e: Exception) {
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

    /**
     * PERFORMANS FIX: bu sayaçlar önceden @Volatile var + `x++` idi — her
     * paket (her iki yön, potansiyel olarak birden fazla eşzamanlı relay
     * session'ı) publish() çağrısında 3-4 ayrı volatile yazma (her biri bir
     * bellek bariyeri) yapıyordu. Bu hem gereksiz per-paket overhead'i hem
     * de gerçek bir doğruluk sorunuydu: `@Volatile var` üzerinde `++` atomic
     * DEĞİL (read-modify-write), yani birden fazla relay session (örn. LAN
     * broadcast ile bağlanan birden fazla client) aynı anda publish()
     * çağırırsa sayaçlar kaybolan güncellemeler yüzünden yanlış çıkabiliyordu.
     * LongAdder tam bu senaryo için tasarlanmış — yüksek yazma sıklığında,
     * stripe'lı sayaçlarla volatile tekli değişkenden çok daha ucuz, ve
     * gerçekten atomic.
     */
    class PublishStats {
        private val _totalPublished = LongAdder()
        private val _totalCancelled = LongAdder()
        private val _clientToServer = LongAdder()
        private val _serverToClient = LongAdder()
        @Volatile private var lastPacketClass: Class<*>? = null

        val totalPublished: Long get() = _totalPublished.sum()
        val totalCancelled: Long get() = _totalCancelled.sum()
        val clientToServer: Long get() = _clientToServer.sum()
        val serverToClient: Long get() = _serverToClient.sum()
        val lastPacketName: String get() = lastPacketClass?.simpleName ?: ""

        internal fun record(event: PacketEvent) {
            _totalPublished.increment()
            lastPacketClass = event.packet.javaClass
            if (event.isClientToServer) _clientToServer.increment() else _serverToClient.increment()
        }

        internal fun recordCancelled() { _totalCancelled.increment() }

        internal fun reset() {
            _totalPublished.reset(); _totalCancelled.reset()
            _clientToServer.reset(); _serverToClient.reset()
            lastPacketClass = null
        }
    }
}
