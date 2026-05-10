package com.oxclient.core.relay

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.ui.overlay.OverlayLogger

/**
 * LoginRelayInterceptor
 *
 * Login paketi (0x01) C→S yönünde intercept eder ve chain'i
 * HandshakeHandler'daki key ile patch eder.
 *
 * Eski LoginPacketInterceptor ile aynı mantık — sadece HandshakeKeyHolder
 * yerine HandshakeHandler kullanıyor.
 *
 * Kayıt: BedrockRelay.start() çağrıldığında register() çağrılır.
 */
object LoginRelayInterceptor : PacketListener {

    private const val TAG = "LoginRelayInterceptor"
    override val priority: Int = 1 // En yüksek öncelik

    fun register()   { PacketEventBus.register(this) }
    fun unregister() { PacketEventBus.unregister(this) }

    override fun onPacket(event: PacketEvent) {
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packetId  != 0x01) return // LOGIN

        try {
            val patched = HandshakeHandler.patchLoginPacket(event.data)
            if (patched != null && !patched.contentEquals(event.data)) {
                event.modifiedData = patched
                OverlayLogger.i(TAG, "✅ Login chain kendi key'imizle güncellendi")
            }
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Login patch hatası — orijinal iletiliyor: ${e.message}", e)
        }
    }
}
