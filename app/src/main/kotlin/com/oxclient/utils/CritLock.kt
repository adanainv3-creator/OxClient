package com.oxclient.utils

import kotlinx.coroutines.sync.Mutex

/**
 * Criticals, KillAura ve KillAuraPro aynı anda (birden fazla hedefe karşı)
 * kendi sahte "düşüş" paket dizilerini tetikleyebiliyordu. Her dizi birkaç
 * paket + delay içerdiğinden, 3-4 hedef aynı tick'te saldırıya girince
 * onlarca paket üst üste binip hem gerçek ağ trafiğini şişiriyor (ping'miş
 * gibi görünen lag) hem de coroutine/handler yükünden dolayı client'ın kendi
 * paket işleme döngüsünü (örn. totem pop algılama) geciktiriyordu.
 *
 * Çözüm: tüm modüller aynı kilidi paylaşsın. Kilit doluysa YENİ bir sahte
 * düşüş dizisi başlatılmaz (saldırının kendisi yine de gönderilir, sadece o
 * vuruş kritik olmayabilir) — böylece asla üst üste binme olmaz.
 */
object CritLock {
    private val mutex = Mutex()

    /** Kilidi hemen alabiliyorsak [block]'u çalıştırır, alamıyorsak sessizce atlar. */
    suspend fun tryRun(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
