package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MITMProxy
 *
 * UDP tabanlı Bedrock MITM proxy.
 * - listenPort (19132) üzerinde Minecraft'ı bekler
 * - Gelen bağlantıyı targetHost:targetPort'a iletir
 * - LAN broadcast'e RakNet UnconnectedPong cevabı verir
 *   → Minecraft'ın Friends > LAN listesinde görünür
 *
 * NetherNet hatası önleme:
 * - VPN tüneli AÇILMAZ — sadece UDP proxy
 * - Minecraft'ın TCP/443 auth trafiği (Xbox/NetherNet) HİÇ ETKİLENMEZ
 * - Minecraft kendi auth'unu yapar, sonra LAN'daki proxy'ye bağlanır
 */
class MITMProxy(
    private val targetHost: String,
    private val targetPort: Int,
    private val listenPort: Int = 19132
) {
    companion object {
        private const val TAG = "MITMProxy"
        private const val BUFFER_SIZE = 65535

        // RakNet magic — sabit, değiştirme
        private val RAKNET_MAGIC = byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
            0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        private const val SERVER_GUID = 0x4F78436C69656E74L  // "OxClient"

        // LAN broadcast adresi — Minecraft'ın discovery için dinlediği adres
        private const val LAN_BROADCAST = "255.255.255.255"
        private const val LAN_LISTEN_PORT = 19132
    }

    private val running = AtomicBoolean(false)
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var listenSocket : DatagramSocket? = null
    @Volatile private var serverSocket : DatagramSocket? = null
    @Volatile private var broadcastSocket: DatagramSocket? = null
    @Volatile private var clientAddress: InetAddress? = null
    @Volatile private var clientPort   : Int = 0

    // targetHost DNS'i VPN açılmadan önce çözülmüş olmalı
    // lazy yerine suspend init — start() çağrısında çözülüyor
    @Volatile private var resolvedServerAddress: InetAddress? = null

    fun getListenSocket() : DatagramSocket? = listenSocket
    fun getServerSocket() : DatagramSocket? = serverSocket
    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.getAndSet(true)) { Log.w(TAG, "Zaten çalışıyor"); return }
        Log.i(TAG, "Başlatılıyor → :$listenPort → $targetHost:$targetPort")

        EntityTracker.register()

        scope.launch {
            // DNS çözümlemesi — bloke olabilir, IO thread'inde yap
            try {
                resolvedServerAddress = withContext(Dispatchers.IO) {
                    InetAddress.getByName(targetHost)
                }
                Log.i(TAG, "DNS çözümlendi: $targetHost → ${resolvedServerAddress?.hostAddress}")
            } catch (e: Exception) {
                Log.e(TAG, "DNS çözümlenemedi: $targetHost", e)
                running.set(false)
                EntityTracker.unregister()
                return@launch
            }

            if (!openSockets()) {
                running.set(false)
                EntityTracker.unregister()
                return@launch
            }

            Log.i(TAG, "Proxy aktif — dinleniyor :$listenPort")

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
        close(listenSocket);   listenSocket = null
        close(serverSocket);   serverSocket = null
        close(broadcastSocket); broadcastSocket = null
        clientAddress = null
        resolvedServerAddress = null
        Log.i(TAG, "Proxy durduruldu")
    }

    private fun openSockets(): Boolean {
        return try {
            // Ana proxy soketi: Minecraft'ı buraya bekle
            listenSocket = DatagramSocket(listenPort).apply {
                soTimeout   = 0
                reuseAddress = true
                broadcast   = true  // LAN broadcast alabilmek için
            }
            // Sunucu soketi: Gerçek sunucuya buradan bağlan
            serverSocket = DatagramSocket().apply {
                soTimeout = 0
            }
            Log.i(TAG, "Soketler açıldı — listen :$listenPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Soket açma hatası (port $listenPort zaten kullanımda?)", e)
            false
        }
    }

    // ── İstemci → Sunucu ──────────────────────────────────────────────────

    private suspend fun clientToServerLoop() {
        val sock = listenSocket ?: return
        val buf  = ByteArray(BUFFER_SIZE)
        val pkt  = DatagramPacket(buf, buf.size)
        Log.d(TAG, "C→S döngüsü başladı")

        while (running.get() && !sock.isClosed) {
            try {
                sock.receive(pkt)
                val senderAddr = pkt.address
                val senderPort = pkt.port
                val raw = pkt.data.copyOf(pkt.length)

                // LAN broadcast'i (255.255.255.255 veya loopback) pong ile cevapla
                if (isUnconnectedPing(raw)) {
                    val pingTime = readInt64BE(raw, 1)
                    val pong = buildUnconnectedPong(pingTime)
                    // Pong'u doğrudan ping geldiği adrese gönder
                    sock.send(DatagramPacket(pong, pong.size, senderAddr, senderPort))
                    Log.d(TAG, "Pong → ${senderAddr.hostAddress}:$senderPort (${pong.size}B)")
                    continue
                }

                // İlk gerçek bağlantı paketinde istemciyi kaydet
                if (clientAddress == null && !isBroadcastAddr(senderAddr)) {
                    clientAddress = senderAddr
                    clientPort    = senderPort
                    Log.i(TAG, "İstemci bağlandı: ${senderAddr.hostAddress}:$senderPort")
                    InjectionQueue.bind(
                        sSocket = serverSocket!!,
                        lSocket = listenSocket!!,
                        sAddr   = resolvedServerAddress!!,
                        sPort   = targetPort,
                        cAddr   = senderAddr,
                        cPort   = senderPort
                    )
                }

                if (isHandshake(raw)) { forwardToServer(raw); continue }

                val result = withContext(Dispatchers.Default) {
                    PacketProcessor.process(raw, PacketEvent.Direction.CLIENT_TO_SERVER)
                }
                if (result != null) forwardToServer(result)

            } catch (e: CancellationException) { break }
            catch (e: Exception) {
                if (running.get()) {
                    Log.v(TAG, "C→S hata (devam): ${e.message}")
                    delay(5)
                }
            }
        }
        Log.d(TAG, "C→S döngüsü bitti")
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
            catch (e: Exception) {
                if (running.get()) {
                    Log.v(TAG, "S→C hata (devam): ${e.message}")
                    delay(5)
                }
            }
        }
        Log.d(TAG, "S→C döngüsü bitti")
    }

    // ── İletim ────────────────────────────────────────────────────────────

    private fun forwardToServer(data: ByteArray) {
        val addr = resolvedServerAddress ?: return
        try {
            serverSocket?.send(DatagramPacket(data, data.size, addr, targetPort))
        } catch (e: Exception) {
            Log.w(TAG, "Sunucuya iletme hatası: ${e.message}")
        }
    }

    private fun forwardToClient(data: ByteArray) {
        val addr = clientAddress ?: return
        try {
            listenSocket?.send(DatagramPacket(data, data.size, addr, clientPort))
        } catch (e: Exception) {
            Log.w(TAG, "İstemciye iletme hatası: ${e.message}")
        }
    }

    // ── Paket Tanıma ──────────────────────────────────────────────────────

    private fun isHandshake(raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false
        return (raw[0].toInt() and 0xFF) in setOf(
            0x00, 0x03, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x10, 0x13, 0x15, 0xC0, 0xA0
        )
    }

    private fun isUnconnectedPing(raw: ByteArray): Boolean {
        if (raw.size < 2) return false
        val id = raw[0].toInt() and 0xFF
        // 0x01 = UnconnectedPing, 0x02 = UnconnectedPing (open connections)
        return id == 0x01 || id == 0x02
    }

    private fun isBroadcastAddr(addr: InetAddress): Boolean {
        val host = addr.hostAddress ?: return false
        return host.endsWith(".255") || host == "255.255.255.255"
    }

    // ── RakNet UnconnectedPong ────────────────────────────────────────────
    //
    // Binary format (tam ve doğru):
    //   [0x1C]       1B   Packet ID
    //   [pingTime]   8B   Int64 BE — ping'den gelen zaman
    //   [GUID]       8B   Int64 BE — sunucu kimliği
    //   [MAGIC]      16B  RakNet sabit
    //   [strLen]     2B   Int16 BE — MOTD string uzunluğu
    //   [motd]       NB   UTF-8
    //
    // MOTD format: MCPE;ad;portv4;version;online;max;guid;subAd;mod;modId;portv4;portv6;

    private fun buildUnconnectedPong(pingTime: Long): ByteArray {
        val motd = "MCPE;OxRelay;$listenPort;1.21.0;0;20;${SERVER_GUID};OxClient;Survival;1;$listenPort;$listenPort;"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()

        out.write(0x1C)
        writeInt64BE(out, pingTime)
        writeInt64BE(out, SERVER_GUID)
        out.write(RAKNET_MAGIC)
        out.write((motdBytes.size shr 8) and 0xFF)
        out.write(motdBytes.size and 0xFF)
        out.write(motdBytes)

        return out.toByteArray()
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────

    private fun writeInt64BE(out: java.io.ByteArrayOutputStream, value: Long) {
        for (i in 7 downTo 0) out.write(((value shr (i * 8)) and 0xFF).toInt())
    }

    private fun readInt64BE(data: ByteArray, offset: Int): Long {
        if (data.size < offset + 8) return System.currentTimeMillis()
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        return result
    }

    private fun close(s: DatagramSocket?) {
        try { s?.close() } catch (_: Exception) {}
    }
}
