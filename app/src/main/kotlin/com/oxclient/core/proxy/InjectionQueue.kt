package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.ui.overlay.OverlayLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

object InjectionQueue {

    private const val TAG = "InjectionQueue"

    @Volatile private var serverSocket : DatagramSocket? = null
    @Volatile private var listenSocket : DatagramSocket? = null
    @Volatile private var serverAddr   : InetAddress?   = null
    @Volatile private var serverPort   : Int            = 0
    @Volatile private var clientAddr   : InetAddress?   = null
    @Volatile private var clientPort   : Int            = 0

    // RakNet sequence sayaçları — her bağlantıda 0'dan başlar
    private val seqCounter      = AtomicInteger(0)
    private val reliableCounter = AtomicInteger(0)
    private val orderCounter    = AtomicInteger(0)

    // Rate limit — çok sık inject flood olarak algılanıyor
    private var lastInjectMs = 0L
    private const val MIN_INJECT_INTERVAL_MS = 5L

    @Volatile var isBound: Boolean = false
        private set

    fun bind(
        sSocket: DatagramSocket, lSocket: DatagramSocket,
        sAddr: InetAddress, sPort: Int,
        cAddr: InetAddress, cPort: Int
    ) {
        serverSocket = sSocket; listenSocket = lSocket
        serverAddr = sAddr; serverPort = sPort
        clientAddr = cAddr; clientPort = cPort
        isBound = true
        OverlayLogger.i(TAG, "✓ Bound → srv=$sAddr:$sPort cli=$cAddr:$cPort")
    }

    fun unbind() {
        isBound = false
        serverSocket = null; listenSocket = null
        serverAddr = null; clientAddr = null
        seqCounter.set(0)
        reliableCounter.set(0)
        orderCounter.set(0)
        Log.d(TAG, "Unbound")
    }

    /**
     * Sunucuya enjeksiyon — ŞİFRELEME UYGULANIYOR.
     *
     * ✅ FIX (KRİTİK): Şifreleme aktifken enjekte edilen paketler şifrelenmeden
     * gönderiliyordu. Sunucu şifreli paket beklediği için bunları tamamen
     * ignore ediyordu → KillAura, TPAura, Criticals vb. hiçbir etki yaratmıyordu.
     *
     * data parametresi: wrapBatch() çıktısı — [0xFE][batch_body]
     * Şifreleme: 0xFE header'dan SONRA gelen kısma (batch_body) uygulanır.
     * Akış: data → [0xFE | encrypt(data[1..])] → wrapInFrameSet → UDP
     */
    fun enqueueToServer(data: ByteArray) {
        if (!isBound) { OverlayLogger.w(TAG, "ToServer: UNBOUND — paket atlandı"); return }
        val sock = serverSocket ?: return
        val addr = serverAddr   ?: return
        try {
            // Rate limit
            val now = System.currentTimeMillis()
            if (now - lastInjectMs < MIN_INJECT_INTERVAL_MS) {
                Thread.sleep(MIN_INJECT_INTERVAL_MS - (now - lastInjectMs))
            }
            lastInjectMs = System.currentTimeMillis()

            // ✅ FIX: Şifreleme aktifse batch_body'yi şifrele
            // data formatı: [0xFE][batch_body]
            // Şifreleme sadece 0xFE'den SONRAKİ kısma uygulanır
            val finalData = if (PacketProcessor.encryptionEnabled && data.isNotEmpty() && data[0] == 0xFE.toByte()) {
                val body    = data.copyOfRange(1, data.size)
                val encrypted = PacketProcessor.encrypt(body)
                byteArrayOf(0xFE.toByte()) + encrypted
            } else {
                data
            }

            val wrapped = wrapInFrameSet(finalData)
            sock.send(DatagramPacket(wrapped, wrapped.size, addr, serverPort))
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "ToServer hata: ${e.message}")
        }
    }

    fun enqueueToClient(data: ByteArray) {
        if (!isBound) { Log.w(TAG, "ToClient: UNBOUND — paket atlandı"); return }
        val sock = listenSocket ?: return
        val addr = clientAddr   ?: return
        try {
            val wrapped = wrapInFrameSet(data)
            sock.send(DatagramPacket(wrapped, wrapped.size, addr, clientPort))
        } catch (e: Exception) {
            Log.w(TAG, "ToClient hata: ${e.message}")
        }
    }

    /**
     * RakNet FrameSet wrapper — RELIABLE_ORDERED (reliability=3)
     *
     * Datagram yapısı:
     *   [1B]  FrameSet ID = 0x84
     *   [3B]  Sequence Number (LE)
     *   [1B]  Flags = 0x60 (reliability=3, no split)
     *   [2B]  Payload bit length (BE)
     *   [3B]  Reliable message index (LE)
     *   [3B]  Order index (LE)
     *   [1B]  Order channel = 0
     *   [N]   Payload
     */
    private fun wrapInFrameSet(payload: ByteArray): ByteArray {
        val seq      = seqCounter.getAndIncrement()      and 0xFFFFFF
        val reliable = reliableCounter.getAndIncrement() and 0xFFFFFF
        val order    = orderCounter.getAndIncrement()    and 0xFFFFFF
        val bitLen   = payload.size * 8

        val out = java.io.ByteArrayOutputStream(payload.size + 27)

        out.write(0x84)
        out.write(seq and 0xFF)
        out.write((seq shr 8) and 0xFF)
        out.write((seq shr 16) and 0xFF)

        out.write(0x60)

        out.write((bitLen shr 8) and 0xFF)
        out.write(bitLen and 0xFF)

        out.write(reliable and 0xFF)
        out.write((reliable shr 8) and 0xFF)
        out.write((reliable shr 16) and 0xFF)

        out.write(order and 0xFF)
        out.write((order shr 8) and 0xFF)
        out.write((order shr 16) and 0xFF)

        out.write(0x00)

        out.write(payload)

        return out.toByteArray()
    }
}
