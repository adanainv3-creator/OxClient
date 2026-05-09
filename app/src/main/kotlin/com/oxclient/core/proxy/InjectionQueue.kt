package com.oxclient.core.proxy

import android.util.Log
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

    // ✅ FIX: RakNet sequence numarasını atomik sayaçla yönet.
    //         Orijinal kodda yoktu — her enjekte edilen paket için
    //         benzersiz ve artan bir sequence number gerekir, aksi
    //         hâlde sunucu duplicate/invalid frame olarak drop eder.
    private val seqCounter = AtomicInteger(0x7000)

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
        Log.i(TAG, "✓ Bound → srv=$sAddr:$sPort cli=$cAddr:$cPort")
    }

    fun unbind() {
        isBound = false
        serverSocket = null; listenSocket = null
        serverAddr = null; clientAddr = null
        Log.d(TAG, "Unbound")
    }

    // ✅ FIX: Orijinal kod ham 0xFE UDP paketi gönderiyordu.
    //         Sunucu (2b2tpe dahil tüm Bedrock sunucuları) UDP katmanında
    //         yalnızca RakNet FrameSet (0x80–0x8F) paketlerini kabul eder.
    //         Ham 0xFE paketi RakNet seviyesinde tanınmadığı için DROP edilir
    //         ve KillAura / CrystalAura / Criticals gibi modüllerin gönderdiği
    //         hiçbir saldırı paketi sunucuya ulaşmaz.
    //         Çözüm: her enjeksiyonu wrapInFrameSet() ile sarıyoruz.
    fun enqueueToServer(data: ByteArray) {
        if (!isBound) { Log.w(TAG, "ToServer: UNBOUND — paket atlandı"); return }
        val sock = serverSocket ?: return
        val addr = serverAddr   ?: return
        try {
            val wrapped = wrapInFrameSet(data)
            sock.send(DatagramPacket(wrapped, wrapped.size, addr, serverPort))
            Log.d(TAG, "ToServer: ${wrapped.size}B gönderildi (FrameSet)")
        } catch (e: Exception) { Log.w(TAG, "ToServer hata: ${e.message}") }
    }

    fun enqueueToClient(data: ByteArray) {
        if (!isBound) { Log.w(TAG, "ToClient: UNBOUND — paket atlandı"); return }
        val sock = listenSocket ?: return
        val addr = clientAddr   ?: return
        try {
            val wrapped = wrapInFrameSet(data)
            sock.send(DatagramPacket(wrapped, wrapped.size, addr, clientPort))
            Log.d(TAG, "ToClient: ${wrapped.size}B gönderildi (FrameSet)")
        } catch (e: Exception) { Log.w(TAG, "ToClient hata: ${e.message}") }
    }

    /**
     * ✅ FIX — RakNet FrameSet wrapper
     *
     * Ham Bedrock paket verisini (0xFE + batch body) geçerli bir
     * RakNet FrameSet datagramına sarar.
     *
     * Datagram yapısı:
     *
     *   ┌─────────────────────────────────────────────┐
     *   │ [1B]  FrameSet ID = 0x84 (Reliable Ordered) │
     *   │ [3B]  Sequence Number (Little Endian)        │
     *   ├─────────────────────────────────────────────┤
     *   │ Frame header (tek frame):                   │
     *   │ [1B]  Flags = 0x60                          │
     *   │        bit7-5 = 011 → Reliability = 3       │
     *   │        (RELIABLE_ORDERED)                   │
     *   │        bit4   = 0   → no split              │
     *   │ [2B]  Bit length of payload (Big Endian)    │
     *   │ [3B]  Reliable message index (LE)           │
     *   │ [3B]  Order index (LE)                      │
     *   │ [1B]  Order channel = 0                     │
     *   ├─────────────────────────────────────────────┤
     *   │ [payload] — 0xFE + varint(len) + packetData │
     *   └─────────────────────────────────────────────┘
     *
     * Reliability 3 (RELIABLE_ORDERED) sunucuların beklediği standarttır.
     * Reliability 2 (RELIABLE) bazı sunucularda ordered olmadığı için
     * oyun paketlerini reddeder.
     */
    private fun wrapInFrameSet(payload: ByteArray): ByteArray {
        val seq    = seqCounter.getAndIncrement() and 0xFFFFFF
        val bitLen = payload.size * 8

        val out = java.io.ByteArrayOutputStream(payload.size + 27)

        // ── FrameSet başlığı ──────────────────────────────────────────────
        out.write(0x84)                         // Reliable Ordered FrameSet ID
        out.write(seq and 0xFF)                 // sequence byte 0 (LE)
        out.write((seq shr 8) and 0xFF)         // sequence byte 1
        out.write((seq shr 16) and 0xFF)        // sequence byte 2

        // ── Frame başlığı ─────────────────────────────────────────────────
        // Flags: reliability=3 (RELIABLE_ORDERED) → bits 7-5 = 011 → 0x60
        out.write(0x60)

        // Payload bit uzunluğu (Big Endian, 2 byte)
        out.write((bitLen shr 8) and 0xFF)
        out.write(bitLen and 0xFF)

        // Reliable message index (Little Endian, 3 byte)
        out.write(seq and 0xFF)
        out.write((seq shr 8) and 0xFF)
        out.write((seq shr 16) and 0xFF)

        // Order index (Little Endian, 3 byte)
        out.write(seq and 0xFF)
        out.write((seq shr 8) and 0xFF)
        out.write((seq shr 16) and 0xFF)

        // Order channel
        out.write(0x00)

        // ── Payload ───────────────────────────────────────────────────────
        out.write(payload)

        return out.toByteArray()
    }
}
