package com.oxclient.core.proxy

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object InjectionQueue {

    private const val TAG = "InjectionQueue"

    @Volatile private var serverSocket : DatagramSocket? = null
    @Volatile private var listenSocket : DatagramSocket? = null
    @Volatile private var serverAddr   : InetAddress?   = null
    @Volatile private var serverPort   : Int            = 0
    @Volatile private var clientAddr   : InetAddress?   = null
    @Volatile private var clientPort   : Int            = 0

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

    fun enqueueToServer(data: ByteArray) {
        if (!isBound) { Log.w(TAG, "ToServer: UNBOUND — paket atlandı"); return }
        val sock = serverSocket ?: return
        val addr = serverAddr ?: return
        try {
            sock.send(DatagramPacket(data, data.size, addr, serverPort))
            Log.d(TAG, "ToServer: ${data.size}B gönderildi")
        } catch (e: Exception) { Log.w(TAG, "ToServer hata: ${e.message}") }
    }

    fun enqueueToClient(data: ByteArray) {
        if (!isBound) { Log.w(TAG, "ToClient: UNBOUND — paket atlandı"); return }
        val sock = listenSocket ?: return
        val addr = clientAddr ?: return
        try {
            sock.send(DatagramPacket(data, data.size, addr, clientPort))
        } catch (e: Exception) { Log.w(TAG, "ToClient hata: ${e.message}") }
    }
}