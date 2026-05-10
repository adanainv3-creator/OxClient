package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.ui.overlay.OverlayLogger
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
 *   - [FIX] İstemci bağlanınca bindInjectionQueue() artık çağrılıyor
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
        OverlayLogger.i(TAG, "Başlatılıyor → :$listenPort ↔ $targetHost:$targetPort")

        EntityTracker.register()
        LoginPacketInterceptor.register()   // ✅ EKLENDİ

        scope.launch {
            // 1. DNS çözümle
            try {
                resolvedServerAddress = withContext(Dispatchers.IO) {
                    InetAddress.getByName(targetHost)
                }
                OverlayLogger.i(TAG, "DNS: $targetHost → ${resolvedServerAddress!!.hostAddress}")
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "DNS hatası: $targetHost", e)
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
                OverlayLogger.i(TAG, "Soketler hazır — dinleniyor :$listenPort")
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "Soket hatası (port $listenPort meşgul?)", e)
                running.set(false)
                EntityTracker.unregister()
                return@launch
            }

            OverlayLogger.i(TAG, "✓ Relay aktif")

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
        OverlayLogger.i(TAG, "Durduruluyor…")
        EntityTracker.unregister()
        LoginPacketInterceptor.unregister() // ✅ EKLENDİ
        InjectionQueue.unbind()
        PacketProcessor.reset()  // ✅ Şifreleme + sıkıştırma state'ini temizle
        scope.cancel()
        safeClose(listenSocket); listenSocket = null
        safeClose(serverSocket); serverSocket = null
        clientAddress         = null
        resolvedServerAddress = null
        serverConnected       = false
        OverlayLogger.i(TAG, "Relay durduruldu")
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
                    OverlayLogger.i(TAG, "İstemci bağlandı: ${senderAddr.hostAddress}:$senderPort")
                    // ✅ FIX: InjectionQueue'yu bağla — hileler artık çalışır
                    bindInjectionQueue()
                }

                // ── Her paketi sunucuya ilet ──────────────────────────────
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
            id in OFFLINE_IDS -> sendToServer(raw)
            id == ID_ACK || id == ID_NACK -> sendToServer(raw)
            id in FRAMESET_MIN..FRAMESET_MAX -> {
                val processed = processFrameSet(raw, PacketEvent.Direction.CLIENT_TO_SERVER)
                if (processed != null) sendToServer(processed)
            }
            else -> sendToServer(raw)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PAKET YÖNLENDİRME — Sunucu tarafı
    // ─────────────────────────────────────────────────────────────────────

    private fun routeServerPacket(raw: ByteArray) {
        val id = raw[0].toInt() and 0xFF

        when {
            id in OFFLINE_IDS -> sendToClient(raw)
            id == ID_ACK || id == ID_NACK -> sendToClient(raw)
            id in FRAMESET_MIN..FRAMESET_MAX -> {
                val processed = processFrameSet(raw, PacketEvent.Direction.SERVER_TO_CLIENT)
                if (processed != null) sendToClient(processed)
            }
            else -> sendToClient(raw)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FRAMESET İŞLEME
    // ─────────────────────────────────────────────────────────────────────

    private fun processFrameSet(raw: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        if (raw.size < 4) return raw

        val frameSetId  = raw[0]
        val seqNum0     = raw[1]; val seqNum1 = raw[2]; val seqNum2 = raw[3]

        var pos     = 4
        val output  = java.io.ByteArrayOutputStream()
        output.write(frameSetId.toInt())
        output.write(seqNum0.toInt())
        output.write(seqNum1.toInt())
        output.write(seqNum2.toInt())

        var anyChanged = false

        while (pos < raw.size) {
            val frameStart = pos
            if (pos >= raw.size) break

            val reliabilityByte = raw[pos].toInt() and 0xFF; pos++
            val reliability     = (reliabilityByte shr 5) and 0x07
            val isSplit         = (reliabilityByte and 0x10) != 0

            if (pos + 2 > raw.size) { output.write(raw, frameStart, raw.size - frameStart); break }
            val bitLen  = ((raw[pos].toInt() and 0xFF) shl 8) or (raw[pos + 1].toInt() and 0xFF); pos += 2
            val byteLen = (bitLen + 7) / 8

            val headerStart = pos
            if (reliability in intArrayOf(2,3,4,6,7)) { if (pos + 3 <= raw.size) pos += 3 }
            if (reliability in intArrayOf(1,4))        { if (pos + 3 <= raw.size) pos += 3 }
            if (reliability in intArrayOf(3,4,7))      { if (pos + 4 <= raw.size) pos += 4 }
            val headerExtra = pos - headerStart

            var splitHeaderBytes: ByteArray? = null
            if (isSplit) {
                if (pos + 10 > raw.size) { output.write(raw, frameStart, raw.size - frameStart); break }
                splitHeaderBytes = raw.copyOfRange(pos, pos + 10); pos += 10
            }

            if (pos + byteLen > raw.size) {
                output.write(raw, frameStart, raw.size - frameStart)
                break
            }
            val payload = raw.copyOfRange(pos, pos + byteLen); pos += byteLen

            val processedPayload = processInnerPayload(payload, direction, isSplit)
            val finalPayload     = processedPayload ?: payload

            if (!finalPayload.contentEquals(payload)) anyChanged = true

            val newBitLen = finalPayload.size * 8
            output.write(reliabilityByte)
            output.write((newBitLen shr 8) and 0xFF)
            output.write(newBitLen and 0xFF)
            if (headerExtra > 0) output.write(raw, headerStart, headerExtra)
            splitHeaderBytes?.let { output.write(it) }
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

        if (isSplit) return payload

        if (innerId in INNER_HANDSHAKE_IDS) {
            Log.v(TAG, "Handshake inner ${innerId.toString(16)} → direkt ilet")
            return payload
        }

        if (innerId == 0xFE) {
            return processGamePacket(payload, direction)
        }

        return payload
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BEDROCK GAME PACKET (0xFE) İŞLEME
    // ─────────────────────────────────────────────────────────────────────

    private fun processGamePacket(payload: ByteArray, direction: PacketEvent.Direction): ByteArray? {
        val body = payload.copyOfRange(1, payload.size)

        // ServerToClientHandshake (0x03) tespiti — şifreleme aktif olmadan önce gelir.
        // Bu paketi yakalayıp ECDH key türet, şifrelemeyi başlat.
        // KRİTİK: handleHandshake() encryptionEnabled=true yapıyor.
        // Bu paket processBatch'e GİRMEMELİ — girecek olursa kod onu şifreli sanır
        // ve bozar; Minecraft bağlantıyı keser.
        if (direction == PacketEvent.Direction.SERVER_TO_CLIENT && !PacketProcessor.encryptionEnabled) {
            try {
                val rawBody = if (PacketProcessor.compressionEnabled) {
                    val r = decompressHandshakeBody(body)
                    OverlayLogger.d(TAG, "HS-scan: body[0]=0x${(body[0].toInt() and 0xFF).toString(16)} bodyLen=${body.size} rawLen=${r.size}")
                    r
                } else body

                val (packetId, _) = readVarIntFromBytes(rawBody, 0)
                OverlayLogger.d(TAG, "HS-scan: packetId=0x${packetId.toString(16)}")

                if (packetId == BedrockPacketIds.SERVER_TO_CLIENT_HANDSHAKE) {
                    handleHandshake(rawBody)
                    return payload
                }
            } catch (e: Exception) {
                OverlayLogger.w(TAG, "HS-scan hata: ${e.message}")
            }
        }

        val result = PacketProcessor.processBatch(body, direction)

        return if (result == null) null
        else byteArrayOf(0xFE.toByte()) + result
    }

    /**
     * Handshake body'sini decompress eder.
     *
     * Bedrock 1.20+ compression batch formatı:
     *   body[0] = algorithm byte (0x00=zlib/deflate, 0x01=snappy, 0xFF=none)
     *   body[1..] = gerçek sıkıştırılmış veri
     *
     * Eğer body[0] tanınan bir header değilse (eski protokol veya header yok),
     * tüm body'yi decompress etmeye çalışır.
     */
    private fun decompressHandshakeBody(body: ByteArray): ByteArray {
        if (body.isEmpty()) return body

        val first = body[0].toInt() and 0xFF

        // 0xFF = sıkıştırılmamış — body[1..] direkt raw batch
        if (first == 0xFF) {
            return if (body.size > 1) body.copyOfRange(1, body.size) else body
        }

        // 0x00 veya 0x01 = algorithm header var
        if (first == 0x00 || first == 0x01) {
            val compressed = body.copyOfRange(1, body.size)
            if (first == 0x01) {
                // Snappy
                try {
                    return org.iq80.snappy.Snappy.uncompress(compressed, 0, compressed.size)
                } catch (_: Exception) {}
            }
            // 0x00 = raw deflate veya zlib wrapper
            val result = zlibInflateSafe(compressed)
            if (result !== compressed) return result  // inflate başarılı
            // Header'sız dene (fallthrough)
        }

        // Header yok ya da header'lı inflate başarısız — tüm body'yi dene
        return zlibInflateSafe(body)
    }

    /**
     * Hem raw deflate (nowrap=true) hem zlib wrapper (nowrap=false) dener.
     * Başarısız olursa orijinal veriyi döner — hiçbir zaman exception fırlatmaz.
     */
    private fun zlibInflateSafe(input: ByteArray): ByteArray {
        // 1. Raw deflate (Bedrock genellikle bunu kullanır)
        try {
            val inf = java.util.zip.Inflater(true)
            inf.setInput(input)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
            inf.end()
            val result = out.toByteArray()
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {}

        // 2. Zlib wrapper (0x78 0x9C magic)
        try {
            val inf = java.util.zip.Inflater(false)
            inf.setInput(input)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
            inf.end()
            val result = out.toByteArray()
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {}

        return input  // Başarısız — ham veriyi döndür
    }

    /**
     * ServerToClientHandshake JWT'sini parse edip AES secret key türet.
     * JWT payload: { "salt": "<base64>" }
     * JWT header:  { "x5u": "<server EC public key base64>" }
     *
     * Secret key = ECDH(clientPrivateKey, serverPublicKey) + SHA256(sharedSecret + salt)
     * Bedrock bunu EncryptionUtils.getSecretKey ile yapıyor.
     */
    private fun handleHandshake(rawBody: ByteArray) {
        try {
            // Paket ID varint'ini atla
            val (_, p0) = readVarIntFromBytes(rawBody, 0)
            // JWT string: varint(len) + UTF8 bytes
            val (jwtLen, p1) = readVarIntFromBytes(rawBody, p0)
            if (jwtLen <= 0 || p1 + jwtLen > rawBody.size) return
            val jwt = String(rawBody, p1, jwtLen, Charsets.UTF_8)

            // JWT'yi parse et (header.payload.signature)
            val parts = jwt.split(".")
            if (parts.size != 3) return

            val headerJson  = String(java.util.Base64.getUrlDecoder().decode(parts[0]))
            val payloadJson = String(java.util.Base64.getUrlDecoder().decode(parts[1]))

            // Server public key
            val headerMap  = parseSimpleJson(headerJson)
            val payloadMap = parseSimpleJson(payloadJson)

            val x5u       = headerMap["x5u"] ?: return
            val saltB64   = payloadMap["salt"] ?: return
            val salt      = java.util.Base64.getDecoder().decode(saltB64)

            // Server EC public key
            val serverKeyBytes = java.util.Base64.getDecoder().decode(x5u)
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val serverPublicKey = keyFactory.generatePublic(
                java.security.spec.X509EncodedKeySpec(serverKeyBytes)
            )

            // Client key pair — login sırasında üretilip kaydedilen
            val clientPrivateKey = HandshakeKeyHolder.privateKey
            if (clientPrivateKey == null) {
                OverlayLogger.w(TAG, "Handshake: client private key yok — şifreleme atlandı")
                return
            }

            // ECDH shared secret
            val keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH")
            keyAgreement.init(clientPrivateKey)
            keyAgreement.doPhase(serverPublicKey, true)
            val sharedSecret = keyAgreement.generateSecret()

            // Secret key = SHA256(counter_le(0) + sharedSecret + salt)
            val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            sha256.update(byteArrayOf(0,0,0,0,0,0,0,0))  // counter = 0, 8 bytes LE
            sha256.update(sharedSecret)
            sha256.update(salt)
            val secretKeyBytes = sha256.digest()  // 32 bytes

            PacketProcessor.enableEncryption(secretKeyBytes)

            // ClientToServerHandshake (0x04) gönder — sunucuya şifreleme hazır de
            val handshakeResponse = buildClientToServerHandshake()
            PacketHelper.injectToServer(handshakeResponse)

            OverlayLogger.i(TAG, "✅ Handshake tamamlandı — şifreleme aktif")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Handshake hatası: ${e.message}", e)
        }
    }

    private fun buildClientToServerHandshake(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // packetId = 0x04 (CLIENT_TO_SERVER_HANDSHAKE)
        PacketProcessor.writeVarInt(out, 0x04)
        return PacketHelper.wrapBatch(out.toByteArray())
    }

    private fun readVarIntFromBytes(data: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0; var shift = 0; var pos = offset
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to pos
    }

    /** Minimal JSON parser — sadece {"key":"value"} formatı için */
    private fun parseSimpleJson(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pattern = Regex(""""(\w+)"\s*:\s*"([^"]+)"""")
        pattern.findAll(json).forEach { match ->
            map[match.groupValues[1]] = match.groupValues[2]
        }
        return map
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
        OverlayLogger.i(TAG, "InjectionQueue bağlandı")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RAKNET UNCONNECTED PONG
    // ─────────────────────────────────────────────────────────────────────

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