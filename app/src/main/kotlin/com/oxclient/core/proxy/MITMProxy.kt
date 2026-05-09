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
    private val listenPort: Int = 19133
) {
    companion object {
        private const val TAG = "MITMProxy"
        private const val BUFFER_SIZE = 65535
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
                if (clientAddress == null) {
                    clientAddress = pkt.address; clientPort = pkt.port
                    Log.i(TAG, "İstemci: ${pkt.address}:${pkt.port}")
                    InjectionQueue.bind(
                        sSocket = serverSocket!!, lSocket = listenSocket!!,
                        sAddr = serverAddress, sPort = targetPort,
                        cAddr = pkt.address, cPort = pkt.port
                    )
                }
                val raw = pkt.data.copyOf(pkt.length)

                // === OxRelay LAN Broadcast ===
                // UnconnectedPing (0x01) → sahte UnconnectedPong ile cevap ver
                if (isUnconnectedPing(raw)) {
                    val pong = buildOxRelayPong()
                    forwardToClient(pong)
                    Log.d(TAG, "OxRelay LAN pong gönderildi")
                    continue  // Sunucuya iletme
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

                // UnconnectedPong (0x1C) → OxRelay'i de ekle
                if (isUnconnectedPong(raw)) {
                    forwardToClient(injectOxRelay(raw))
                    continue
                }

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

    // ── Handshake ─────────────────────────────────────────────────────────

    private fun isHandshake(raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false
        return (raw[0].toInt() and 0xFF) in listOf(
            0x00, 0x03, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x10, 0x13, 0x15, 0xC0, 0xA0
        )
    }

    private fun isUnconnectedPing(raw: ByteArray): Boolean {
        return raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0x01
    }

    private fun isUnconnectedPong(raw: ByteArray): Boolean {
        return raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0x1C
    }

    // ── OxRelay LAN Broadcast ─────────────────────────────────────────────

    /**
     * Minecraft LAN broadcast'ine cevap olarak OxRelay sunucusunu tanıtır.
     * Format: MCPE;motd;port;version;players;maxPlayers;serverId;subMotd;gamemode
     */
    private fun buildOxRelayPong(): ByteArray {
        val pongStr = "MCPE;§5⚡ §dOxRelay §5⚡;$listenPort;1.21.0;0;1;oxclient-relay;§bProxy aktif - Bağlan;Survival;1;19132;19133"
        val pongData = pongStr.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        out.write(0x1C)  // UnconnectedPong ID
        out.write(System.currentTimeMillis().toInt() ushr 8)  // ping ID (fake)
        out.write(0x00)   // magic
        out.write(pongData)
        return out.toByteArray()
    }

    /**
     * Mevcut UnconnectedPong'a OxRelay girişini ekler.
     */
    private fun injectOxRelay(data: ByteArray): ByteArray {
        try {
            val rawStr = String(data, Charsets.UTF_8)
            if (rawStr.contains("OxRelay")) return data  // Zaten eklenmiş

            val newEntry = "MCPE;§5⚡ §dOxRelay §5⚡;$listenPort;1.21.0;0;1;oxclient-relay;§bProxy aktif;Survival;1;19132;19133"
            val combined = "$rawStr\n$newEntry"
            Log.d(TAG, "OxRelay sunucu listesine eklendi")
            return combined.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            return data
        }
    }

    // ── Yardımcı ──────────────────────────────────────────────────────────

    private fun close(s: DatagramSocket?) { try { s?.close() } catch (_: Exception) {} }
}