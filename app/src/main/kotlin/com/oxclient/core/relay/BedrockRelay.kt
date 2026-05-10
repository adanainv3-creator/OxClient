package com.oxclient.core.relay

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.ui.overlay.OverlayLogger
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BedrockRelay
 *
 * Bedrock Edition UDP MITM relay — MITMProxy'nin yerini alır.
 *
 * ── Mimari Değişiklikleri ─────────────────────────────────────────────────
 * Eski MITMProxy                   │ Yeni BedrockRelay
 * ─────────────────────────────────┼──────────────────────────────────────
 * PacketProcessor (global object)  │ RelaySession (instance, per-connection)
 * HandshakeKeyHolder (global)      │ HandshakeHandler (instance methods)
 * Inline processFrameSet()         │ RakNetFrameParser (ayrı class)
 * Inline FrameSet header parser    │ parseFrameSet() correct reliability headers
 * SHA256 counter=0 LE              │ SHA256 counter=2 BE (CloudburstMC uyumlu)
 * AES/CTR sabit IV                 │ AES/CFB8 per-packet counter IV
 * wrapBatch() algorithm header yok │ BedrockCompression algorithm header ekler
 *
 * ── Çalışma Prensibi ─────────────────────────────────────────────────────
 * Minecraft ──UDP:19132──► BedrockRelay ──UDP──► Gerçek Sunucu
 * Minecraft ◄──UDP:19132─ BedrockRelay ◄──UDP── Gerçek Sunucu
 *
 * Minecraft → LAN listesi üzerinden proxy'yi "OxRelay" olarak görür.
 */
class BedrockRelay(
    private val targetHost : String,
    private val targetPort : Int,
    private val listenPort : Int = 19132
) {
    companion object {
        private const val TAG         = "BedrockRelay"
        private const val BUFFER_SIZE = 65535

        private val RAKNET_MAGIC = byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
            0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
            0x12, 0x34, 0x56, 0x78
        )
        private const val SERVER_GUID = 0x4F78436C69656E74L // "OxClient"

        // RakNet offline paket ID'leri (unconnected) — değiştirilmeden iletilir
        private val OFFLINE_IDS = setOf(0x01, 0x02, 0x05, 0x06, 0x07, 0x08, 0x19, 0x1C)

        // ACK / NACK — direkt iletilir
        private const val ID_ACK  = 0xC0
        private const val ID_NACK = 0xA0

        // FrameSet aralığı
        private const val FRAMESET_MIN = 0x80
        private const val FRAMESET_MAX = 0x8F

        // FrameSet içindeki connected handshake paket ID'leri — değiştirilmeden iletilir
        private val INNER_HANDSHAKE_IDS = setOf(0x00, 0x03, 0x09, 0x10, 0x13, 0x15)

        // Bedrock paket ID'leri
        private const val ID_GAME_PACKET       = 0xFE
        private const val ID_SERVER_HANDSHAKE  = 0x03
        private const val ID_LOGIN             = 0x01
    }

    private val running = AtomicBoolean(false)
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Her bağlantıya özgü session state
    private val session = RelaySession()

    @Volatile private var listenSocket          : DatagramSocket? = null
    @Volatile private var serverSocket          : DatagramSocket? = null
    @Volatile private var clientAddress         : InetAddress?   = null
    @Volatile private var clientPort            : Int            = 0
    @Volatile private var resolvedServerAddress : InetAddress?   = null

    val isRunning: Boolean get() = running.get()

    fun getListenSocket(): DatagramSocket? = listenSocket
    fun getServerSocket(): DatagramSocket? = serverSocket

    // ── Enjeksiyon (modüller için) ────────────────────────────────────────

    /**
     * Sunucuya paket enjekte et.
     * @param batchBody 0xFE header'sız raw batch body (wrapBatch çıktısının gövdesi)
     */
    fun injectToServer(batchBody: ByteArray) {
        val body = if (session.encryptionEnabled) session.encrypt(batchBody) else batchBody
        val wrapped = wrapInFrameSet(byteArrayOf(0xFE.toByte()) + body)
        try {
            val addr = resolvedServerAddress ?: return
            serverSocket?.send(DatagramPacket(wrapped, wrapped.size, addr, targetPort))
        } catch (e: Exception) {
            Log.w(TAG, "injectToServer hata: ${e.message}")
        }
    }

    /**
     * İstemciye paket enjekte et (şifreleme yok — LAN).
     */
    fun injectToClient(batchBody: ByteArray) {
        val wrapped = wrapInFrameSet(byteArrayOf(0xFE.toByte()) + batchBody)
        try {
            val addr = clientAddress ?: return
            listenSocket?.send(DatagramPacket(wrapped, wrapped.size, addr, clientPort))
        } catch (e: Exception) {
            Log.w(TAG, "injectToClient hata: ${e.message}")
        }
    }

    // ── Başlat / Durdur ───────────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) { Log.w(TAG, "Zaten çalışıyor"); return }
        OverlayLogger.i(TAG, "Başlatılıyor → :$listenPort ↔ $targetHost:$targetPort")

        HandshakeHandler.generateKeyPair()
        session.reset()

        scope.launch {
            // 1. DNS
            try {
                resolvedServerAddress = withContext(Dispatchers.IO) {
                    InetAddress.getByName(targetHost)
                }
                OverlayLogger.i(TAG, "DNS: $targetHost → ${resolvedServerAddress!!.hostAddress}")
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "DNS hatası: $targetHost", e)
                running.set(false)
                return@launch
            }

            // 2. Soketler
            try {
                listenSocket = DatagramSocket(listenPort).apply {
                    soTimeout    = 0
                    reuseAddress = true
                    broadcast    = true
                }
                serverSocket = DatagramSocket().apply { soTimeout = 0 }
                OverlayLogger.i(TAG, "Soketler hazır — :$listenPort")
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "Soket hatası (port $listenPort meşgul?)", e)
                running.set(false)
                return@launch
            }

            // InjectionQueue'yu yeni relay ile bağla
            RelayInjectionBridge.bind(this@BedrockRelay)
            OverlayLogger.i(TAG, "✓ BedrockRelay aktif")

            val cJob = launch { clientLoop() }
            val sJob = launch { serverLoop() }
            joinAll(cJob, sJob)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        OverlayLogger.i(TAG, "Durduruluyor…")
        HandshakeHandler.clear()
        RelayInjectionBridge.unbind()
        session.reset()
        scope.cancel()
        safeClose(listenSocket); listenSocket = null
        safeClose(serverSocket); serverSocket = null
        clientAddress         = null
        resolvedServerAddress = null
        OverlayLogger.i(TAG, "BedrockRelay durduruldu")
    }

    // ── Döngüler ─────────────────────────────────────────────────────────

    private suspend fun clientLoop() {
        val sock = listenSocket ?: return
        val buf  = ByteArray(BUFFER_SIZE)
        val pkt  = DatagramPacket(buf, buf.size)

        while (running.get() && !sock.isClosed) {
            try {
                sock.receive(pkt)
                val senderAddr = pkt.address
                val senderPort = pkt.port
                val raw = pkt.data.copyOf(pkt.length)
                if (raw.isEmpty()) continue

                val id = raw[0].toInt() and 0xFF

                // LAN discovery ping → pong cevabı
                if (id == 0x01 || id == 0x02) {
                    val pingTime = if (raw.size >= 9) readInt64BE(raw, 1) else System.currentTimeMillis()
                    val pong = buildUnconnectedPong(pingTime)
                    sock.send(DatagramPacket(pong, pong.size, senderAddr, senderPort))
                    continue
                }

                if (isBroadcast(senderAddr)) continue

                if (clientAddress == null) {
                    clientAddress = senderAddr
                    clientPort    = senderPort
                    OverlayLogger.i(TAG, "İstemci bağlandı: ${senderAddr.hostAddress}:$senderPort")
                }

                routeClientPacket(raw)

            } catch (e: CancellationException) { break }
            catch (e: Exception) {
                if (running.get()) { Log.v(TAG, "C→S: ${e.message}"); delay(2) }
            }
        }
    }

    private suspend fun serverLoop() {
        val sock = serverSocket ?: return
        val buf  = ByteArray(BUFFER_SIZE)
        val pkt  = DatagramPacket(buf, buf.size)

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
    }

    // ── Yönlendirme ───────────────────────────────────────────────────────

    private fun routeClientPacket(raw: ByteArray) {
        val id = raw[0].toInt() and 0xFF
        when {
            id in OFFLINE_IDS                   -> sendToServer(raw)
            id == ID_ACK || id == ID_NACK       -> sendToServer(raw)
            id in FRAMESET_MIN..FRAMESET_MAX    -> {
                val out = processFrameSet(raw, PacketEvent.Direction.CLIENT_TO_SERVER)
                if (out != null) sendToServer(out)
            }
            else -> sendToServer(raw)
        }
    }

    private fun routeServerPacket(raw: ByteArray) {
        val id = raw[0].toInt() and 0xFF
        when {
            id in OFFLINE_IDS                   -> sendToClient(raw)
            id == ID_ACK || id == ID_NACK       -> sendToClient(raw)
            id in FRAMESET_MIN..FRAMESET_MAX    -> {
                val out = processFrameSet(raw, PacketEvent.Direction.SERVER_TO_CLIENT)
                if (out != null) sendToClient(out)
            }
            else -> sendToClient(raw)
        }
    }

    // ── FrameSet işleme ───────────────────────────────────────────────────

    private fun processFrameSet(raw: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (raw.size < 4) return raw

        val frameSetHeader = raw.copyOf(4)
        val frames = RakNetFrameParser.parseFrameSet(raw) ?: return raw

        var anyChanged = false
        val processedFrames = frames.map { frame ->
            if (frame.isSplit) {
                frame // Split paketleri birleştirmeden işleme
            } else {
                val processed = processInnerPayload(frame.payload, direction)
                if (processed != null && !processed.contentEquals(frame.payload)) {
                    anyChanged = true
                    frame.withPayload(processed)
                } else if (processed == null) {
                    anyChanged = true
                    frame // null = paket atlandı, frame'i boş payload ile bırak
                } else {
                    frame
                }
            }
        }

        return if (anyChanged) {
            RakNetFrameParser.buildFrameSet(frameSetHeader, processedFrames)
        } else raw
    }

    private fun processInnerPayload(payload: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (payload.isEmpty()) return payload
        val innerId = payload[0].toInt() and 0xFF

        if (innerId in INNER_HANDSHAKE_IDS) return payload

        if (innerId == ID_GAME_PACKET) {
            return processGamePacket(payload, direction)
        }

        return payload
    }

    // ── Game packet (0xFE) işleme ─────────────────────────────────────────

    private fun processGamePacket(payload: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        val body = payload.copyOfRange(1, payload.size)

        // Handshake taraması — şifreleme aktif olmadan önce ServerToClientHandshake'i yakala
        if (direction == PacketEvent.Direction.SERVER_TO_CLIENT && !session.encryptionEnabled) {
            tryCaptureHandshake(body)
        }

        val result = session.processBatch(body, direction) ?: return null
        return byteArrayOf(0xFE.toByte()) + result
    }

    private fun tryCaptureHandshake(body: ByteArray) {
        try {
            val decompressed = if (session.compressionEnabled) {
                BedrockCompression.decompress(body, session.compressionAlgorithm, encrypted = false)
            } else body

            // [varint len][varint packetId][data...]
            var pos = 0
            val (_, p0) = readVarInt(decompressed, 0); pos = p0
            val (packetId, p1) = readVarInt(decompressed, pos)

            if (packetId == ID_SERVER_HANDSHAKE) {
                OverlayLogger.d(TAG, "ServerToClientHandshake yakalandı")
                val packetData = decompressed.copyOfRange(p0, decompressed.size)
                val secretKey = HandshakeHandler.processHandshake(packetData)
                if (secretKey != null) {
                    session.enableEncryption(secretKey)
                    // ClientToServerHandshake (0x04) gönder
                    sendClientHandshake()
                }
            }
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Handshake tarama hatası: ${e.message}")
        }
    }

    private fun sendClientHandshake() {
        // [varint 0x04] — ClientToServerHandshake
        val out = java.io.ByteArrayOutputStream()
        writeVarInt(out, 0x04)
        val packetBytes = out.toByteArray()

        // Batch'e sar: [varint len][packet]
        val batchOut = java.io.ByteArrayOutputStream()
        writeVarInt(batchOut, packetBytes.size)
        batchOut.write(packetBytes)

        val batchBody = if (session.compressionEnabled) {
            BedrockCompression.compress(batchOut.toByteArray(), session.compressionAlgorithm, encrypted = false)
        } else batchOut.toByteArray()

        injectToServer(batchBody)
        OverlayLogger.i(TAG, "✅ ClientToServerHandshake gönderildi")
    }

    // ── Login intercept ───────────────────────────────────────────────────

    /**
     * Bu metod PacketEvent sistemiyle entegre çalışır.
     * LoginRelayInterceptor, PacketEventBus üzerinden Login paketini (0x01)
     * alıp HandshakeHandler.patchLoginPacket() ile patch eder.
     * Ayrı bir EventBus listener olduğu için burada özel bir kod gerekmez.
     */

    // ── İletim ───────────────────────────────────────────────────────────

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

    // ── RakNet Pong ───────────────────────────────────────────────────────

    private fun buildUnconnectedPong(pingTime: Long): ByteArray {
        val motd      = "MCPE;OxRelay;748;1.21.60;0;20;${SERVER_GUID};OxClient;Survival;1;$listenPort;$listenPort;"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)
        val out       = java.io.ByteArrayOutputStream(32 + motdBytes.size)
        out.write(0x1C)
        writeInt64BE(out, pingTime)
        writeInt64BE(out, SERVER_GUID)
        out.write(RAKNET_MAGIC)
        out.write((motdBytes.size shr 8) and 0xFF)
        out.write(motdBytes.size and 0xFF)
        out.write(motdBytes)
        return out.toByteArray()
    }

    // ── RakNet FrameSet Wrapper (enjeksiyon için) ─────────────────────────

    private var seqCounter      = 0
    private var reliableCounter = 0
    private var orderCounter    = 0

    private fun wrapInFrameSet(payload: ByteArray): ByteArray {
        val seq      = seqCounter++      and 0xFFFFFF
        val reliable = reliableCounter++ and 0xFFFFFF
        val order    = orderCounter++    and 0xFFFFFF
        val bitLen   = payload.size * 8
        val out      = java.io.ByteArrayOutputStream(payload.size + 27)

        out.write(0x84) // FrameSet ID
        out.write(seq and 0xFF)
        out.write((seq shr 8) and 0xFF)
        out.write((seq shr 16) and 0xFF)

        // RELIABLE_ORDERED frame (reliability = 3, flags = 0x60)
        out.write(0x60)
        out.write((bitLen shr 8) and 0xFF)
        out.write(bitLen and 0xFF)
        // Reliable header
        out.write(reliable and 0xFF)
        out.write((reliable shr 8) and 0xFF)
        out.write((reliable shr 16) and 0xFF)
        // Ordered header
        out.write(order and 0xFF)
        out.write((order shr 8) and 0xFF)
        out.write((order shr 16) and 0xFF)
        out.write(0x00) // orderChannel

        out.write(payload)
        return out.toByteArray()
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────

    private fun readVarInt(data: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0; var shift = 0; var pos = offset
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to pos
    }

    private fun writeVarInt(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var b = v and 0x7F; v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
    }

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
}

// RakNetFrameParser extension — burada kullanımı kolaylaştırmak için
private fun RakNetFrameParser.Frame.withPayload(p: ByteArray) =
    with(RakNetFrameParser) { this@withPayload.withPayload(p) }
