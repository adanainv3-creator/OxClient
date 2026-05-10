package com.oxclient.core.relay

import android.util.Log
import com.oxclient.ui.overlay.OverlayLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * RelayInjectionBridge
 *
 * Modüllerin (BaseModule alt sınıfları) BedrockRelay'e paket enjekte etmesi
 * için köprü. Eski InjectionQueue'nun yerini alır.
 *
 * Farklar:
 *   - InjectionQueue: DatagramSocket + RakNet wrapping elle yapılıyor
 *   - RelayInjectionBridge: BedrockRelay.injectToServer/Client() kullanır
 *     → şifreleme/sıkıştırma otomatik uygulanır
 *
 * Kullanım (modüller için):
 *   val packet = RelayPacketBuilder.buildMovePlayer(runtimeId, x, y, z)
 *   RelayInjectionBridge.sendToServer(packet)
 *
 * Paket formatı: wrapBatch() çıktısı — sıkıştırma algorithm header dahil.
 */
object RelayInjectionBridge {

    private const val TAG = "RelayInjectionBridge"

    @Volatile private var relay: BedrockRelay? = null

    private val lastInjectMs = AtomicLong(0L)
    private const val MIN_INJECT_INTERVAL_MS = 5L

    val isBound: Boolean get() = relay != null

    fun bind(r: BedrockRelay) {
        relay = r
        OverlayLogger.i(TAG, "✓ RelayInjectionBridge bağlandı")
    }

    fun unbind() {
        relay = null
        Log.d(TAG, "RelayInjectionBridge unbind")
    }

    /**
     * Sunucuya batch paket gönder.
     * @param batchData wrapBatch() çıktısı: [0xFE][algorithm byte?][compressed body]
     *                  veya sıkıştırmasız: [0xFE][raw batch]
     *
     * BedrockRelay.injectToServer() şifrelemeyi otomatik uygular.
     * Bu metot 0xFE header'ını çıkarıp body'yi relay'e iletir.
     */
    fun sendToServer(batchData: ByteArray) {
        val r = relay ?: run {
            Log.w(TAG, "sendToServer: relay bağlı değil — atlandı")
            return
        }
        if (batchData.isEmpty() || batchData[0] != 0xFE.toByte()) {
            Log.w(TAG, "sendToServer: geçersiz batch format")
            return
        }

        rateLimit()
        val body = batchData.copyOfRange(1, batchData.size)
        r.injectToServer(body)
    }

    /**
     * İstemciye batch paket gönder (şifreleme yok).
     */
    fun sendToClient(batchData: ByteArray) {
        val r = relay ?: run {
            Log.w(TAG, "sendToClient: relay bağlı değil — atlandı")
            return
        }
        if (batchData.isEmpty() || batchData[0] != 0xFE.toByte()) {
            Log.w(TAG, "sendToClient: geçersiz batch format")
            return
        }
        val body = batchData.copyOfRange(1, batchData.size)
        r.injectToClient(body)
    }

    private fun rateLimit() {
        val now  = System.currentTimeMillis()
        val last = lastInjectMs.get()
        val diff = now - last
        if (diff < MIN_INJECT_INTERVAL_MS) {
            try { Thread.sleep(MIN_INJECT_INTERVAL_MS - diff) } catch (_: InterruptedException) {}
        }
        lastInjectMs.set(System.currentTimeMillis())
    }
}
