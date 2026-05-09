package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MITMProxy — Bedrock RakNet UDP MITM Proxy
 *
 * Çalışma prensibi:
 *   Minecraft ──UDP:19132──► MITMProxy ──UDP──► Gerçek Sunucu
 *   Minecraft ◄──UDP:19132─ MITMProxy ◄──UDP── Gerçek Sunucu
 *
 * Neden "bağlantı zaman aşımına uğradı" hatası çıkıyordu:
 *   1. RakNet el sıkışma paketleri (0x05,0x06,0x07,0x08,0x09,0x10,0x13)
 *      FrameSet içinde değil, DİREKT olarak iletilir. Bunları bloke etmemek gerekir.
 *   2. FrameSet paketleri (0x80..0x8F) içindeki connected handshake paketleri
 *      (0x09 ConnectionRequest, 0x10 ConnectionRequestAccepted, 0x13 NewIncomingConnection)
 *      da değiştirilmeden iletilmeli.
 *   3. ACK (0xC0) ve NACK (0xA0) hiçbir zaman işlenmemeli, direkt iletilmeli.
 *   4. Sunucu soketine ilk paket gittiğinde sunucu soketinin adresi bind edilmeli.
 *   5. LAN pong MOTD'u doğru formatta olmalı.
 *
 * Düzeltmeler:
 *   - Tüm offline RakNet paketleri (0x01,0x02,0x05,0x06,0x07,0x08,0x19) değiştirilmeden iletilir
 *   - ACK/NACK direkt iletilir
 *   - FrameSet (0x80..0x8F) içindeki paketler: eğer iç paket ID handshake ise direkt ilet
 *   - 0xFE (GamePacket) olan iç paketler PacketProcessor'a gönderilir
 *   - Sunucu soketi ilk pakette sunucuya bağlanır
 */
class MITMProxy(
    private val targetHost : String,
    private val targetPort : Int,
    private val listenPort : Int = 19132
) {
    companion object {
        private const val TAG         = "MITMProxy"
        private const val BUFFER_SIZE = 65535

        private val RAKNET_MAGIC = byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
            0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        private const val SERVER_GUID = 0x4F78436C69656E74L  // "OxClient"

        // RakNet offline paket ID'leri — BUNLAR DİREKT İLETİLİR, işlenmez
        private val OFFLINE_IDS = setOf(0x01, 0x02, 0x05, 0x06, 0x07, 0x08, 0x19, 0x1C)

        // ACK / NACK — direkt iletilir
        private const val ID_ACK  = 0xC0
        private const val ID_NACK = 0xA0

        // Unconnected Ping ID'leri
        private const val ID_PING_1 = 0x01
        private const val ID_PING_2 = 0x02

        // FrameSet aralığı
        private const val FRAMESET_MIN = 0x80
        private const val FRAMESET_MAX = 0x8F

        // FrameSet içindeki handshake paket ID'leri — DEĞİŞTİRİLMEDEN İLETİLİR
        // 0x00=ConnectedPing, 0x03=ConnectedPong, 0x09=ConnectionRequest,
        // 0x10=ConnectionRequestAccepted, 0x13=NewIncomingConnection,
        // 0x15=DisconnectNotification
        private val INNER_HANDSHAKE_IDS = setOf(
            0x00, 0x03, 0x09, 0x10, 0x13, 0x15
        )
    }

    private val running = AtomicBoolean(false)
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var listenSocket          : DatagramSocket? = null
    @Volatile private var serverSocket          : DatagramSocket? = null
    @Volatile private var clientAddress         : InetAddress?   = null
    @Volatile private var clientPort            : Int            = 0
    @Volatile private var resolvedServerAddress : InetAddress?   = null
    @Volatile private var serverConnected       : Boolean        = false

    val isRunning: Boolean get() = running.get()

    fun getListenSocket(): DatagramSocket? = listenSocket
    fun getServerSocket(): DatagramSocket? = serverSocket

    // ─────────────────────────────────────────────────────────────────────
    //  BAŞLAT
    // ─────────────────────────────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) { Log.w(TAG, "Zaten çalışıyor"); return }
        Log.i(TAG, "Başlatılıyor → :$listenPort ↔ $targetHost:$targetPort")

        EntityTracker.register()

        scope.launch {
            // 1. DNS çözümle
            try {
                resolvedServerAddress = withContext(Dispatchers.IO) {
                    InetAddress.getByName(targetHost)
                }
                Log.i(TAG, "DNS: $targetHost → ${resolvedServerAddress!!.hostAddress}")
            } catch (e: Exception) {
                Log.e(TAG, "DNS hatası: $targetHost", e)
                running.set(false)
                EntityTracker.unregister()
                return@launch
            }

            // 2. Soketleri aç
            try {
                listenSocket = DatagramSocket(listenPort).apply {
                    soTimeout    = 0
                    reuseAddress = true
                    broadcast    = true
                }
                serverSocket = DatagramSocket().apply {
                    soTimeout = 0
                }
                Log.i(TAG, "Soketler hazır — dinleniyor :$listenPort")
            } catch (e: Exception) {
                Log.e(TAG, "Soket hatası (port $listenPort meşgul?)", e)
                running.set(false)
                EntityTracker.unregister()
                return@launch
            }

            // 3. InjectionQueue bağla (sunucu adresi biliniyor ama client henüz yok,
            //    istemci bağlanınca rebind yapılacak)

            Log.i(TAG, "✓ Proxy aktif")

            val cJob = launch { clientLoop() }
            val sJob = launch { serverLoop() }
            joinAll(cJob, sJob)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DURDUR
    // ─────────────────────────────────────────────────────────────────────

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Durduruluyor…")
        EntityTracker.unregister()
        InjectionQueue.unbind()
        scope.cancel()
        safeClose(listenSocket); listenSocket = null
        safeClose(serverSocket); serverSocket = null
        clientAddress         = null
        resolvedServerAddress = null
        serverConnected       = false
        Log.i(TAG, "Proxy durduruldu")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  İSEMCİ → SUNUCU DÖNGÜSÜ
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun clientLoop() {
        val sock = listenSocket ?: return
        val buf  = ByteArray(BUFFER_SIZE)
        val pkt  = DatagramPacket(buf, buf.size)
        Log.d(TAG, "C→S döngüsü başladı")

        while (running.get() && !sock.isClosed) {
            try {
                sock.receive(pkt)
                val senderAddr = pkt.address
                val senderPort = pkt.port
                val raw        = pkt.data.copyOf(pkt.length)
                if (raw.isEmpty()) continue

                val id = raw[0].toInt() and 0xFF

                // ── LAN discovery ping → pong cevabı ver ──────────────────
                if (id == ID_PING_1 || id == ID_PING_2) {
                    val pingTime = if (raw.size >= 9) readInt64BE(raw, 1) else System.currentTimeMillis()
                    val pong     = buildUnconnectedPong(pingTime)
                    sock.send(DatagramPacket(pong, pong.size, senderAddr, senderPort))
                    Log.v(TAG, "Pong → ${senderAddr.hostAddress}:$senderPort")
                    continue
                }

                // Broadcast adreslerden gelen ping'leri yoksay
                if (isBroadcast(senderAddr)) continue

                // ── İlk bağlantı paketi → istemciyi kaydet ────────────────
                if (clientAddress == null) {
                    clientAddress = senderAddr
                    clientPort    = senderPort
                    Log.i(TAG, "İstemci bağlandı: ${senderAddr.hostAddress}:$senderPort")
                }

                // ── Her paketi sunucuya ilet ──────────────────────────────
                // (işleme aşağıda)
                routeClientPacket(raw)

            } catch (e: CancellationException) { break }
            catch (e: Exception) {
                if (running.get()) { Log.v(TAG, "C→S: ${e.message}"); delay(2) }
            }
        }
        Log.d(TAG, "C→S döngüsü bitti")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SUNUCU → İSEMCİ DÖNGÜSÜ
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun serverLoop() {
        val sock = serverSocket ?: return
        val buf  = ByteArray(BUFFER_SIZE)
        val pkt  = DatagramPacket(buf, buf.size)
        Log.d(TAG, "S→C döngüsü başladı")

        while (running.get() && !sock.isClosed) {
            try {
                sock.receive(pkt)
                val raw = pkt.data.copyOf(pkt.length)
                if (raw.isEmpty()) continue
                routeServerPacket(raw)
            } catch (e: CancellationException) { break }
            catch (e: Exception) {
                if (running.get()) { Log.v(TAG, "S→C: ${e.message}"); delay(2) }
            }
        }
        Log.d(TAG, "S→C döngüsü bitti")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PAKET YÖNLENDİRME — İstemci tarafı
    // ─────────────────────────────────────────────────────────────────────

    private fun routeClientPacket(raw: ByteArray) {
        val id = raw[0].toInt() and 0xFF

        when {
            // Offline handshake paketleri (OCR1, OCR2, vb.) → direkt ilet
            id in OFFLINE_IDS -> {
                sendToServer(raw)
            }

            // ACK / NACK → direkt ilet
            id == ID_ACK || id == ID_NACK -> {
                sendToServer(raw)
            }

            // FrameSet paketleri → içini incele
            id in FRAMESET_MIN..FRAMESET_MAX -> {
                val processed = processFrameSet(raw, PacketEvent.Direction.CLIENT_TO_SERVER)
                if (processed != null) sendToServer(processed)
            }

            // Bilinmeyenler → direkt ilet
            else -> sendToServer(raw)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PAKET YÖNLENDİRME — Sunucu tarafı
    // ─────────────────────────────────────────────────────────────────────

    private fun routeServerPacket(raw: ByteArray) {
        val id = raw[0].toInt() and 0xFF

        when {
            // Offline cevaplar (OCR Reply1, OCR Reply2, IncompatibleProtocol vb.) → direkt ilet
            id in OFFLINE_IDS -> {
                sendToClient(raw)
            }

            // ACK / NACK → direkt ilet
            id == ID_ACK || id == ID_NACK -> {
                sendToClient(raw)
            }

            // FrameSet paketleri → içini incele
            id in FRAMESET_MIN..FRAMESET_MAX -> {
                val processed = processFrameSet(raw, PacketEvent.Direction.SERVER_TO_CLIENT)
                if (processed != null) sendToClient(processed)
            }

            // Bilinmeyenler → direkt ilet
            else -> sendToClient(raw)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FRAMESET İŞLEME
    //
    //  RakNet FrameSet binary format:
    //    [0]      : flags byte (0x80..0x8F)
    //    [1..3]   : sequence number (3 byte Little Endian)
    //    [4..]    : bir veya daha fazla Frame
    //
    //  Her Frame:
    //    [0]      : reliability flags byte
    //              bit7-5 = reliability type
    //              bit4   = isSplit (fragment)
    //    [1..2]   : payload length in BITS (Big Endian)
    //    [3..5]   : reliableMessageIndex (if reliable, 3 bytes LE)  — opsiyonel
    //    [3..5]   : sequencingIndex (if sequenced, 3 bytes LE)      — opsiyonel
    //    [3..6]   : orderingIndex (if ordered, 3 bytes LE) + channel (1 byte) — opsiyonel
    //    [...]    : split frame header (10 bytes) if isSplit         — opsiyonel
    //    [...]    : payload (length/8 bytes)
    //
    //  Payload'un ilk byte'ı inner packet ID'dir.
    //  Eğer inner ID 0xFE ise → Bedrock GamePacket (batch)
    //  Eğer inner ID handshake ise → değiştirilmeden bırak
    // ─────────────────────────────────────────────────────────────────────

    private fun processFrameSet(raw: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (raw.size < 4) return raw

        val frameSetId  = raw[0]
        val seqNum0     = raw[1]; val seqNum1 = raw[2]; val seqNum2 = raw[3]

        var pos     = 4          // Frame'lerin başlangıcı
        val output  = java.io.ByteArrayOutputStream()
        output.write(frameSetId.toInt())
        output.write(seqNum0.toInt())
        output.write(seqNum1.toInt())
        output.write(seqNum2.toInt())

        var anyChanged = false

        while (pos < raw.size) {
            val frameStart = pos
            if (pos >= raw.size) break

            // Reliability byte
            val reliabilityByte = raw[pos].toInt() and 0xFF; pos++
            val reliability     = (reliabilityByte shr 5) and 0x07
            val isSplit         = (reliabilityByte and 0x10) != 0

            // Payload bit length → byte length
            if (pos + 2 > raw.size) { output.write(raw, frameStart, raw.size - frameStart); break }
            val bitLen  = ((raw[pos].toInt() and 0xFF) shl 8) or (raw[pos + 1].toInt() and 0xFF); pos += 2
            val byteLen = (bitLen + 7) / 8

            // Reliability-specific headers
            val headerStart = pos
            if (reliability in intArrayOf(2,3,4,6,7)) { if (pos + 3 <= raw.size) pos += 3 }  // reliableMessageIndex
            if (reliability in intArrayOf(1,4))        { if (pos + 3 <= raw.size) pos += 3 }  // sequencingIndex
            if (reliability in intArrayOf(3,4,7))      { if (pos + 4 <= raw.size) pos += 4 }  // orderingIndex + channel
            val headerExtra = pos - headerStart

            // Split header
            var splitHeaderBytes: ByteArray? = null
            if (isSplit) {
                if (pos + 10 > raw.size) { output.write(raw, frameStart, raw.size - frameStart); break }
                splitHeaderBytes = raw.copyOfRange(pos, pos + 10); pos += 10
            }

            // Payload
            if (pos + byteLen > raw.size) {
                // Eksik veri — güvenli tarafta direkt yaz
                output.write(raw, frameStart, raw.size - frameStart)
                break
            }
            val payload = raw.copyOfRange(pos, pos + byteLen); pos += byteLen

            // Payload'u işle
            val processedPayload = processInnerPayload(payload, direction, isSplit)
            val finalPayload     = processedPayload ?: payload   // null = cancel, yine de ilet (handshake güvenliği)

            if (!finalPayload.contentEquals(payload)) anyChanged = true

            // Frame'i yeniden yaz
            val newBitLen = finalPayload.size * 8
            output.write(reliabilityByte)
            output.write((newBitLen shr 8) and 0xFF)
            output.write(newBitLen and 0xFF)
            // Reliability headers (tekrar kopyala)
            if (headerExtra > 0) output.write(raw, headerStart, headerExtra)
            // Split header
            splitHeaderBytes?.let { output.write(it) }
            // Payload
            output.write(finalPayload)
        }

        return if (anyChanged) output.toByteArray() else raw
    }

    // ─────────────────────────────────────────────────────────────────────
    //  İÇ PAYLOAD İŞLEME
    // ─────────────────────────────────────────────────────────────────────

    private fun processInnerPayload(
        payload  : ByteArray,
        direction: PacketEvent.Direction,
        isSplit  : Boolean
    ): ByteArray? {
        if (payload.isEmpty()) return payload

        val innerId = payload[0].toInt() and 0xFF

        // Fragment parçası — henüz tam değil, direkt ilet
        if (isSplit) return payload

        // ── RakNet connected handshake paketleri: DOKUNMA ─────────────────
        // ConnectionRequest, ConnectionRequestAccepted, NewIncomingConnection,
        // ConnectedPing, ConnectedPong, DisconnectNotification
        if (innerId in INNER_HANDSHAKE_IDS) {
            Log.v(TAG, "Handshake inner ${innerId.toString(16)} → direkt ilet")
            return payload
        }

        // ── Bedrock GamePacket (0xFE) — işle ─────────────────────────────
        if (innerId == 0xFE) {
            return processGamePacket(payload, direction)
        }

        // Diğerleri → direkt ilet
        return payload
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BEDROCK GAME PACKET (0xFE) İŞLEME
    //  Format: [0xFE][varint_len][packet_data] × N
    //  (sıkıştırma YOKTUR — Bedrock raw mode, NetworkSettings paketinden sonra
    //   sıkıştırma açılabilir ama varsayılan kapalı)
    // ─────────────────────────────────────────────────────────────────────

    private fun processGamePacket(payload: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        // payload[0] = 0xFE
        // payload[1..] = batch body
        val body   = payload.copyOfRange(1, payload.size)
        val result = PacketProcessor.processBatch(body, direction)

        return if (result == null) null
        else byteArrayOf(0xFE.toByte()) + result
    }

    // ─────────────────────────────────────────────────────────────────────
    //  İLETİM
    // ─────────────────────────────────────────────────────────────────────

    internal fun sendToServer(data: ByteArray) {
        val addr = resolvedServerAddress ?: return
        try {
            serverSocket?.send(DatagramPacket(data, data.size, addr, targetPort))
        } catch (e: Exception) {
            Log.w(TAG, "→Sunucu hatası: ${e.message}")
        }
    }

    internal fun sendToClient(data: ByteArray) {
        val addr = clientAddress ?: return
        try {
            listenSocket?.send(DatagramPacket(data, data.size, addr, clientPort))
        } catch (e: Exception) {
            Log.w(TAG, "→İstemci hatası: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  MODÜL ENJEKSİYONU (InjectionQueue aracılığıyla)
    // ─────────────────────────────────────────────────────────────────────

    private fun bindInjectionQueue() {
        val sAddr = resolvedServerAddress ?: return
        val cAddr = clientAddress ?: return
        val sSock = serverSocket ?: return
        val lSock = listenSocket ?: return
        InjectionQueue.bind(
            sSocket = sSock, lSocket = lSock,
            sAddr = sAddr, sPort = targetPort,
            cAddr = cAddr, cPort = clientPort
        )
        Log.i(TAG, "InjectionQueue bağlandı")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RAKNET UNCONNECTED PONG
    // ─────────────────────────────────────────────────────────────────────

    private fun buildUnconnectedPong(pingTime: Long): ByteArray {
        // MOTD formatı: MCPE;Server Name;Protocol;Version;Players;MaxPlayers;GUID;SubMOTD;GameMode;GameModeId;PortV4;PortV6;
        val motd      = "MCPE;OxRelay;748;1.21.60;0;20;${SERVER_GUID};OxClient;Survival;1;$listenPort;$listenPort;"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)
        val out       = java.io.ByteArrayOutputStream(32 + motdBytes.size)

        out.write(0x1C)                              // UnconnectedPong ID
        writeInt64BE(out, pingTime)                  // pong time
        writeInt64BE(out, SERVER_GUID)               // server GUID
        out.write(RAKNET_MAGIC)                      // 16-byte magic
        out.write((motdBytes.size shr 8) and 0xFF)   // string length BE
        out.write(motdBytes.size and 0xFF)
        out.write(motdBytes)

        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  YARDIMCILAR
    // ─────────────────────────────────────────────────────────────────────

    private fun writeInt64BE(out: java.io.ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v shr (i * 8)) and 0xFF).toInt())
    }

    private fun readInt64BE(data: ByteArray, offset: Int): Long {
        if (data.size < offset + 8) return System.currentTimeMillis()
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        return result
    }

    private fun isBroadcast(addr: InetAddress): Boolean {
        val h = addr.hostAddress ?: return false
        return h.endsWith(".255") || h == "255.255.255.255"
    }

    private fun safeClose(s: DatagramSocket?) {
        try { s?.close() } catch (_: Exception) {}
    }

    private operator fun Int.contains(range: Nothing): Boolean = false
}
