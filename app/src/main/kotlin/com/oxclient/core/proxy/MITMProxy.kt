package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class MITMProxy(
    private val targetHost: String,
    private val targetPort: Int,
    private val listenPort: Int = 19132
) {
    companion object {
        private const val TAG = "MITMProxy"
        private const val BUFFER_SIZE = 65535

        // RakNet sabit magic (değiştirme)
        private val RAKNET_MAGIC = byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
            0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        private const val SERVER_GUID = 0x4F78436C69656E74L  // "OxClient"
    }

    private val running = AtomicBoolean(false)
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var listenSocket: DatagramSocket? = null
    @Volatile private var serverSocket: DatagramSocket? = null
    @Volatile private var clientAddress: InetAddress? = null
    @Volatile private var clientPort: Int = 0
    private val serverAddress by lazy { InetAddress.getByName(targetHost) }

    fun getListenSocket(): DatagramSocket? = listenSocket
    fun getServerSocket(): DatagramSocket? = serverSocket
    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.getAndSet(true)) { Log.w(TAG, "Zaten çalışıyor"); return }
        Log.i(TAG, "Başlatılıyor → :$listenPort → $targetHost:$targetPort")
        EntityTracker.register()
        scope.launch {
            if (!openSockets()) { running.set(false); EntityTracker.unregister(); return@launch }
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
        Log.d(TAG, "C→S başladı")

        while (running.get() && !sock.isClosed) {
            try {
                sock.receive(pkt)

                // İlk pakette istemciyi kaydet ve InjectionQueue'ya bağla
                if (clientAddress == null) {
                    clientAddress = pkt.address; clientPort = pkt.port
                    Log.i(TAG, "İstemci bağlandı: ${pkt.address}:${pkt.port}")
                    InjectionQueue.bind(
                        sSocket = serverSocket!!, lSocket = listenSocket!!,
                        sAddr = serverAddress, sPort = targetPort,
                        cAddr = pkt.address, cPort = pkt.port
                    )
                }

                val raw = pkt.data.copyOf(pkt.length)

                // UnconnectedPing (0x01) → RakNet pong ile cevapla, sunucuya iletme
                if (isUnconnectedPing(raw)) {
                    val pingTime = readInt64BE(raw, 1)
                    val pong = buildUnconnectedPong(pingTime)
                    forwardToClient(pong)
                    Log.d(TAG, "LAN pong gönderildi (${pong.size}B)")
                    continue
                }

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
        Log.d(TAG, "S→C başladı")

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

    // ── İletim ────────────────────────────────────────────────────────────

    private fun forwardToServer(data: ByteArray) {
        try { serverSocket?.send(DatagramPacket(data, data.size, serverAddress, targetPort)) }
        catch (e: Exception) { Log.w(TAG, "Srv iletme: ${e.message}") }
    }

    private fun forwardToClient(data: ByteArray) {
        val addr = clientAddress ?: return
        try { listenSocket?.send(DatagramPacket(data, data.size, addr, clientPort)) }
        catch (e: Exception) { Log.w(TAG, "Cli iletme: ${e.message}") }
    }

    // ── Paket tespiti ─────────────────────────────────────────────────────

    private fun isHandshake(raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false
        return (raw[0].toInt() and 0xFF) in listOf(
            0x00, 0x03, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x10, 0x13, 0x15, 0xC0, 0xA0
        )
    }

    private fun isUnconnectedPing(raw: ByteArray): Boolean {
        return raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0x01
    }

    // ── RakNet UnconnectedPong ────────────────────────────────────────────
    //
    // Gerçek binary format:
    //   [0x1C]         1B  - Packet ID
    //   [pingTime]     8B  - Int64 BE  (ping'den alınan zaman)
    //   [serverGUID]   8B  - Int64 BE
    //   [MAGIC]       16B  - RakNet sabit
    //   [strLen]       2B  - Int16 BE  (MOTD uzunluğu)
    //   [motd]         NB  - UTF-8
    //
    // MOTD: MCPE;başlık;port;versiyon;oyuncu;maxOyuncu;guid;altBaşlık;mod;modId;portv4;portv6;

    private fun buildUnconnectedPong(pingTime: Long): ByteArray {
        val motd = "MCPE;OxRelay;$listenPort;1.21.0;0;20;${SERVER_GUID};OxClient Proxy;Survival;1;$listenPort;$listenPort;"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()

        out.write(0x1C)                    // Packet ID
        writeInt64BE(out, pingTime)        // pingTime
        writeInt64BE(out, SERVER_GUID)     // serverGUID
        out.write(RAKNET_MAGIC)            // magic
        out.write((motdBytes.size shr 8) and 0xFF)  // strLen hi
        out.write(motdBytes.size and 0xFF)           // strLen lo
        out.write(motdBytes)               // motd

        return out.toByteArray()
    }

    // ── Int64 yardımcıları ────────────────────────────────────────────────

    private fun writeInt64BE(out: java.io.ByteArrayOutputStream, value: Long) {
        for (i in 7 downTo 0) out.write(((value shr (i * 8)) and 0xFF).toInt())
    }

    private fun readInt64BE(data: ByteArray, offset: Int): Long {
        if (data.size < offset + 8) return System.currentTimeMillis()
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        return result
    }

    private fun close(s: DatagramSocket?) { try { s?.close() } catch (_: Exception) {} }
}
