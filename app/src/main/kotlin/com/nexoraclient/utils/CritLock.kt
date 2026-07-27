package com.nexoraclient.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kritik enjeksiyon kilidi - artık çok daha hızlı
 */
object CritLock {
    // AtomicBoolean daha hafif
    private val locked = AtomicBoolean(false)
    
    // Her modül için ayrı kilit (opsiyonel)
    private val moduleLocks = mutableMapOf<String, Mutex>()
    
    suspend fun tryRun(block: suspend () -> Unit) {
        // AtomicBoolean ile hızlı kontrol
        if (!locked.compareAndSet(false, true)) return
        try {
            block()
        } finally {
            locked.set(false)
        }
    }
    
    // Modül bazlı kilit (daha az çakışma)
    suspend fun tryRun(moduleName: String, block: suspend () -> Unit) {
        val lock = moduleLocks.getOrPut(moduleName) { Mutex() }
        if (!lock.tryLock()) return
        try {
            block()
        } finally {
            lock.unlock()
        }
    }
}