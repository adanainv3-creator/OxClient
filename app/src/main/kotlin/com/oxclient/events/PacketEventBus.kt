package com.oxclient.events

import java.util.concurrent.CopyOnWriteArrayList

typealias PacketListener = (PacketEvent) -> Unit

/**
 * PacketEventBus
 *
 * Yayın/abone (publish/subscribe) deseni.
 * Modüller [subscribe] ile belirli paket ID'lerine abone olur.
 * PacketProcessor [publish] ile olayı senkron olarak dağıtır.
 */
object PacketEventBus {

    private data class Subscription(
        val packetId  : Int?,               // null = tüm paketler
        val direction : PacketDirection?,   // null = her iki yön
        val priority  : Int,
        val listener  : PacketListener
    )

    private val subscriptions = CopyOnWriteArrayList<Subscription>()

    /**
     * Belirli bir paket ID'sine abone ol.
     * @param packetId  null = tüm paketler
     * @param direction null = her iki yön
     * @param priority  Düşük sayı = önce çalışır (default 100)
     */
    fun subscribe(
        packetId  : Int?            = null,
        direction : PacketDirection? = null,
        priority  : Int             = 100,
        listener  : PacketListener
    ) {
        subscriptions.add(Subscription(packetId, direction, priority, listener))
        subscriptions.sortBy { it.priority }
    }

    fun unsubscribe(listener: PacketListener) {
        subscriptions.removeIf { it.listener === listener }
    }

    fun unsubscribeAll(owner: Any) {
        // Modüller kendi listener'larını bir Set'te tutup toplu iptal edebilir
    }

    fun publish(event: PacketEvent) {
        for (sub in subscriptions) {
            if (event.cancelled) break
            if (sub.packetId  != null && sub.packetId  != event.id)        continue
            if (sub.direction != null && sub.direction != event.direction)  continue
            try {
                sub.listener(event)
            } catch (_: Exception) { /* izole et */ }
        }
    }

    fun clear() = subscriptions.clear()
}
