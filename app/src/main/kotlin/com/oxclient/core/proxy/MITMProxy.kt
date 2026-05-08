package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.ModuleManager
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class MITMProxy(
    private val targetHost: String,
    private val targetPort: Int,
    private val listenPort: Int = 19133
) {
    companion object {
        private const val TAG         = "MITMProxy"
        private const val BUFFER_SIZE = 65535
    }

    private val running = AtomicBoolean(false)
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var listenSocket: DatagramSocket? = null
    @Volatile private var serverSocket: DatagramSocket? = null

    @Volatile private var clientAddress: InetAddress? = null
    @Volatile private var clientPort: Int = 0

    private val serverAddress: InetAddress by lazy { InetAddress.getByName(targetHost) }

    fun getListenSocket(): DatagramSocket? = listenSocket
    fun getServerSocket(): DatagramSocket? = serverSocket

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.getAndSet(true)) { Log.w(TAG, "Zaten çalışıyor"); return }
        Log.i(TAG, "Başlatılıyor → :$listenPort → $targetHost:$targetPort")

        scope.launch {
            if (!openSockets()) { running.set(false); return@launch }

            // EntityTracker'ı kaydet
            EntityTracker.register()
            // Modülleri başlat
            ModuleManager.init()

            Log.i(TAG, "Proxy aktif")
            val cJob = launch { clientToServerLoop() }
            val sJob = launch { serverToClientLoop() }
            joinAll(cJob, sJob)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Durduruluyor…")
        EntityTracker.unregister()
        InjectionQueue.unbind()
        scope.cancel()
        close(listenSocket); listenSocket = null
        close(serverSocket); serverSocket = null
        clientAddress = null
        Log.i(TAG, "Proxy durduruldu")
    }

    private fun openSockets(): Boolean = try {
        listenSocket = DatagramSocket(listenPort).apply { soTimeout = 0; reuseAddress = true }
        serverSocket = DatagramSocket().apply { soTimeout = 0 }
        true
    } catch (e: Exception) { Log.e(TAG, "Socket hatası", e); false }

    // ── İstemci → Sunucu ──────────────────────────────────────────────────
    private suspend fun clientToServerLoop() {
        val sock = listenSocket ?: return
        val buf  = ByteArray(BUFFER_SIZE)
        val pkt  = DatagramPacket(buf, buf.size)
        Log.d(TAG, "C→S döngüsü başladı")

        while (running.get() && !sock.isClosed) {
            try {
                sock.receive(pkt)

                // İlk pakette istemci adresini kaydet + InjectionQueue'ya bildir
                if (clientAddress == null) {
                    clientAddress = pkt.address
                    clientPort    = pkt.port
                    Log.i(TAG, "İstemci: ${pkt.address}:${pkt.port}")
                    // InjectionQueue'ya socket ve adres bilgilerini ver
                    InjectionQueue.bind(
                        sSocket = serverSocket!!,
                        lSocket = listenSocket!!,
                        sAddr   = serverAddress,
                        sPort   = targetPort,
                        cAddr   = pkt.address,
                        cPort   = pkt.port
                    )
                }

                val raw = pkt.data.copyOf(pkt.length)
                if (isHandshake(raw)) { forwardToServer(raw); continue }

                val result = withContext(Dispatchers.Default) {
                    PacketProcessor.process(raw, PacketEvent.Direction.CLIENT_TO_SERVER)
                }
                if (result != null) forwardToServer(result)
            } catch (e: CancellationException) { break }
            catch (e: Exception) { if (running.get()) delay(5) }
        }
        Log.d(TAG, "C→S bitti")
    }

    // ── Sunucu → İstemci ──────────────────────────────────────────────────
    private suspend fun serverToClientLoop() {
        val sock = serverSocket ?: return
        val buf  = ByteArray(BUFFER_SIZE)
        val pkt  = DatagramPacket(buf, buf.size)
        Log.d(TAG, "S→C döngüsü başladı")

        while (running.get() && !sock.isClosed) {
            try {
                sock.receive(pkt)
                val raw = pkt.data.copyOf(pkt.length)
                if (isHandshake(raw)) { forwardToClient(raw); continue }

                val result = withContext(Dispatchers.Default) {
                    PacketProcessor.process(raw, PacketEvent.Direction.SERVER_TO_CLIENT)
                }
                if (result != null) forwardToClient(result)
            } catch (e: CancellationException) { break }
            catch (e: Exception) { if (running.get()) delay(5) }
        }
        Log.d(TAG, "S→C bitti")
    }

    private fun forwardToServer(data: ByteArray) {
        try { serverSocket?.send(DatagramPacket(data, data.size, serverAddress, targetPort)) }
        catch (e: Exception) { Log.w(TAG, "Sunucuya iletme: ${e.message}") }
    }

    private fun forwardToClient(data: ByteArray) {
        val addr = clientAddress ?: return
        try { listenSocket?.send(DatagramPacket(data, data.size, addr, clientPort)) }
        catch (e: Exception) { Log.w(TAG, "İstemciye iletme: ${e.message}") }
    }

    private fun isHandshake(raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false
        return (raw[0].toInt() and 0xFF) in listOf(
            0x00, 0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x10, 0x13, 0x15, 0x1C, 0xC0, 0xA0
        )
    }

    private fun close(s: DatagramSocket?) { try { s?.close() } catch (_: Exception) {} }
}