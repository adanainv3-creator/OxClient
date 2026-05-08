package com.oxclient.core.proxy

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue

/**
 * InjectionQueue
 *
 * ModüllerdenbuildXxx() ile oluşturulan paketlerin
 * MITMProxy socket'lerine iletildiği merkezi kuyruk.
 *
 * MITMProxy başladığında bind() çağrılır.
 * MITMProxy durduğunda unbind() çağrılır.
 */
object InjectionQueue {

    private const val TAG = "InjectionQueue"

    // MITMProxy tarafından set edilir
    @Volatile private var serverSocket : DatagramSocket? = null
    @Volatile private var listenSocket : DatagramSocket? = null
    @Volatile private var serverAddr   : InetAddress?   = null
    @Volatile private var serverPort   : Int            = 0
    @Volatile private var clientAddr   : InetAddress?   = null
    @Volatile private var clientPort   : Int            = 0

    private val toServerQueue = LinkedBlockingQueue<ByteArray>(512)
    private val toClientQueue = LinkedBlockingQueue<ByteArray>(512)

    fun bind(
        sSocket: DatagramSocket,
        lSocket: DatagramSocket,
        sAddr: InetAddress, sPort: Int,
        cAddr: InetAddress, cPort: Int
    ) {
        serverSocket = sSocket
        listenSocket = lSocket
        serverAddr   = sAddr
        serverPort   = sPort
        clientAddr   = cAddr
        clientPort   = cPort
        Log.d(TAG, "Bind edildi → server=$sAddr:$sPort, client=$cAddr:$cPort")
    }

    fun unbind() {
        serverSocket = null; listenSocket = null
        serverAddr = null; clientAddr = null
        toServerQueue.clear(); toClientQueue.clear()
        Log.d(TAG, "Unbind edildi")
    }

    fun updateClientEndpoint(addr: InetAddress, port: Int) {
        clientAddr = addr; clientPort = port
    }

    /** Modüller → Sunucu */
    fun enqueueToServer(data: ByteArray) {
        val sock = serverSocket ?: return
        val addr = serverAddr   ?: return
        try {
            sock.send(DatagramPacket(data, data.size, addr, serverPort))
        } catch (e: Exception) {
            Log.w(TAG, "ToServer inject hatası: ${e.message}")
        }
    }

    /** Modüller → İstemci */
    fun enqueueToClient(data: ByteArray) {
        val sock = listenSocket ?: return
        val addr = clientAddr   ?: return
        try {
            sock.send(DatagramPacket(data, data.size, addr, clientPort))
        } catch (e: Exception) {
            Log.w(TAG, "ToClient inject hatası: ${e.message}")
        }
    }
}
