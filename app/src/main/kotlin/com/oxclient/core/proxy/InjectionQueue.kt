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

    // ✅ FIX: Sequence, Reliable index ve Order index AYRI sayaçlarda.
    //         Önceki kodda hepsi aynı `seq` değerini paylaşıyordu.
    //         RakNet protokolünde bu üç sayaç bağımsız olarak artar;
    //         aynı değeri kullanmak sunucunun duplicate/out-of-order
    //         tespitini bozar ve frame drop'a yol açar.
    private val seqCounter      = AtomicInteger(0x7000)  // FrameSet sequence
    private val reliableCounter = AtomicInteger(0)       // Reliable message index
    private val orderCounter    = AtomicInteger(0)       // Order index

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
        seqCounter.set(0x7000)
        reliableCounter.set(0)
        orderCounter.set(0)
        Log.d(TAG, "Unbound")
    }

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
     * RakNet FrameSet wrapper — RELIABLE_ORDERED (reliability=3)
     *
     * Datagram yapısı:
     *   [1B]  FrameSet ID = 0x84
     *   [3B]  Sequence Number (LE)          ← seqCounter
     *   [1B]  Flags = 0x60 (reliability=3, no split)
     *   [2B]  Payload bit length (BE)
     *   [3B]  Reliable message index (LE)   ← reliableCounter  ✅ AYRI SAYAÇ
     *   [3B]  Order index (LE)              ← orderCounter      ✅ AYRI SAYAÇ
     *   [1B]  Order channel = 0
     *   [N]   Payload (0xFE + batch data)
     */
    private fun wrapInFrameSet(payload: ByteArray): ByteArray {
        val seq      = seqCounter.getAndIncrement()      and 0xFFFFFF
        val reliable = reliableCounter.getAndIncrement() and 0xFFFFFF
        val order    = orderCounter.getAndIncrement()    and 0xFFFFFF
        val bitLen   = payload.size * 8

        val out = java.io.ByteArrayOutputStream(payload.size + 27)

        // ── FrameSet başlığı ──────────────────────────────────────────────
        out.write(0x84)
        out.write(seq and 0xFF)
        out.write((seq shr 8) and 0xFF)
        out.write((seq shr 16) and 0xFF)

        // ── Frame başlığı ─────────────────────────────────────────────────
        out.write(0x60)                         // RELIABLE_ORDERED flags

        // Payload bit uzunluğu (Big Endian)
        out.write((bitLen shr 8) and 0xFF)
        out.write(bitLen and 0xFF)

        // Reliable message index (Little Endian, 3 byte) ✅ reliableCounter
        out.write(reliable and 0xFF)
        out.write((reliable shr 8) and 0xFF)
        out.write((reliable shr 16) and 0xFF)

        // Order index (Little Endian, 3 byte) ✅ orderCounter
        out.write(order and 0xFF)
        out.write((order shr 8) and 0xFF)
        out.write((order shr 16) and 0xFF)

        // Order channel
        out.write(0x00)

        // ── Payload ───────────────────────────────────────────────────────
        out.write(payload)

        return out.toByteArray()
    }
}
