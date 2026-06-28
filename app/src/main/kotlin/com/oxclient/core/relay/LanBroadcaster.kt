package com.oxclient.core.relay

import com.oxclient.ui.overlay.OverlayLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LanBroadcaster — Minecraft Bedrock LAN discovery için UDP broadcast sistemi.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Nasıl çalışır:
 *   1. Minecraft Bedrock, LAN'daki sunucuları bulmak için her ~1sn'de
 *      255.255.255.255:19132'ye RakNet UNCONNECTED_PING paketi gönderir.
 *   2. Bu sınıf iki strateji kullanır:
 *      a) PING_LISTENER  → 19132'yi dinler, gelen ping'e direkt PONG cevap verir (en doğrusu)
 *      b) ACTIVE_SENDER  → Her 1.5sn'de broadcast olarak PONG yayımlar (fallback)
 *   3. Her iki strateji de çalışır; hangi yöntemin başarılı olduğu loglanır.
 *   4. updateInfo() ile motd/version runtime'da güncellenir (AutoCodecListener entegrasyonu).
 *
 * AndroidManifest.xml'e eklenmesi gerekenler:
 *   <uses-permission android:name="android.permission.INTERNET"/>
 *   <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE"/>
 * ─────────────────────────────────────────────────────────────────────────────
 */
object LanBroadcaster {

    private const val TAG = "LanBroadcaster"

    // RakNet sabit değerleri
    private const val MC_PORT            = 19132
    private const val UNCONNECTED_PING   = 0x01.toByte()
    private const val UNCONNECTED_PONG   = 0x1C.toByte()
    private const val BROADCAST_INTERVAL = 1500L   // ms — MC'nin tarama aralığıyla uyumlu
    private const val SOCKET_TIMEOUT_MS  = 2000     // ms

    // RakNet offline message ID (magic bytes) — değişmez
    private val RAKNET_MAGIC = byteArrayOf(
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
        0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
        0x12, 0x34, 0x56, 0x78
    )

    // ── Durum ────────────────────────────────────────────────────────────────

    private val running       = AtomicBoolean(false)
    private val serverGuid    = System.currentTimeMillis()  // Sabit GUID — session boyunca değişmez

    @Volatile private var relayPort      : Int    = 19150
    @Volatile private var motd           : String = "OxClient"
    @Volatile private var subMotd        : String = "OxClient"
    @Volatile private var protocolVersion: Int    = 748
    @Volatile private var mcVersion      : String = "1.21.60"
    @Volatile private var playerCount    : Int    = 0
    @Volatile private var maxPlayers     : Int    = 10

    private var listenerThread : Thread? = null
    private var broadcasterThread: Thread? = null

    // ── Genel API ────────────────────────────────────────────────────────────

    /**
     * LAN broadcast'i başlatır.
     *
     * @param relayPort      Gerçek relay'in dinlediği port (19150). MC bu porta bağlanır.
     * @param motd           LAN listesinde görünecek sunucu adı.
     * @param subMotd        Alt başlık.
     * @param protocolVersion Codec'in protokol versiyonu.
     * @param mcVersion      Minecraft versiyon string'i ("1.21.60", "26.10" vb.)
     * @param maxPlayers     Gösterilecek maksimum oyuncu sayısı.
     */
    fun start(
        relayPort      : Int,
        motd           : String = "OxClient",
        subMotd        : String = "OxClient",
        protocolVersion: Int,
        mcVersion      : String,
        maxPlayers     : Int = 10
    ) {
        if (running.getAndSet(true)) {
            OverlayLogger.w(TAG, "LanBroadcaster zaten çalışıyor")
            return
        }

        this.relayPort       = relayPort
        this.motd            = motd
        this.subMotd         = subMotd
        this.protocolVersion = protocolVersion
        this.mcVersion       = mcVersion
        this.maxPlayers      = maxPlayers

        startPingListener()
        startActiveBroadcaster()

        OverlayLogger.i(TAG, "LanBroadcaster başlatıldı | relayPort=$relayPort | protocol=$protocolVersion | mc=$mcVersion")
    }

    /**
     * AutoCodecListener client'ın gerçek versiyonunu öğrenince bu metodu çağırır.
     * Yeni pong bilgisi anında aktif olur.
     */
    fun updateInfo(
        protocolVersion: Int,
        mcVersion      : String,
        motd           : String = this.motd,
        playerCount    : Int    = this.playerCount
    ) {
        this.protocolVersion = protocolVersion
        this.mcVersion       = mcVersion
        this.motd            = motd
        this.playerCount     = playerCount
        OverlayLogger.d(TAG, "Pong bilgisi güncellendi: protocol=$protocolVersion mc=$mcVersion")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        listenerThread?.interrupt()
        broadcasterThread?.interrupt()
        listenerThread    = null
        broadcasterThread = null
        OverlayLogger.i(TAG, "LanBroadcaster durduruldu")
    }

    val isRunning: Boolean get() = running.get()

    // ── Strateji A: Ping Listener ─────────────────────────────────────────
    // MC'nin gönderdiği UNCONNECTED_PING paketini dinler ve direkt cevap verir.
    // Bu yöntem en güveniliridir çünkü MC'nin tam zamanlamasına uyar.

    private fun startPingListener() {
        listenerThread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(MC_PORT).apply {
                    broadcast   = true
                    soTimeout   = SOCKET_TIMEOUT_MS
                    reuseAddress = true
                }
                OverlayLogger.i(TAG, "Ping listener başlatıldı → port $MC_PORT")

                val buf = ByteArray(512)
                while (running.get()) {
                    try {
                        val incoming = DatagramPacket(buf, buf.size)
                        socket.receive(incoming)

                        // UNCONNECTED_PING kontrolü (ID=0x01, min 1+8+16 = 25 byte)
                        if (incoming.length >= 25 && buf[0] == UNCONNECTED_PING) {
                            val pingTime = ByteBuffer.wrap(buf, 1, 8)
                                .order(ByteOrder.BIG_ENDIAN).getLong()

                            val pong = buildPongPacket(pingTime)
                            val response = DatagramPacket(
                                pong, pong.size,
                                incoming.address,
                                incoming.port
                            )
                            socket.send(response)
                            OverlayLogger.v(TAG, "Ping cevaplandı → ${incoming.address}:${incoming.port}")
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal — timeout döngüyü bloke etmemek için var
                    } catch (e: Exception) {
                        if (running.get()) OverlayLogger.w(TAG, "Ping listener hatası: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                // 19132 zaten bind olmuş olabilir (relay aynı porttaysa)
                OverlayLogger.w(TAG, "Ping listener başlatılamadı (port meşgul?): ${e.message} — aktif broadcaster devam eder")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }, "OxLan-PingListener").apply {
            isDaemon = true
            start()
        }
    }

    // ── Strateji B: Active Broadcaster ───────────────────────────────────
    // Periyodik olarak broadcast gönderir. Ping listener başaramazsa tek çare budur.

    private fun startActiveBroadcaster() {
        broadcasterThread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                OverlayLogger.i(TAG, "Aktif broadcaster başlatıldı → hedef $broadcastAddr:$MC_PORT")

                while (running.get()) {
                    try {
                        val pong = buildPongPacket(System.currentTimeMillis())
                        socket.send(DatagramPacket(pong, pong.size, broadcastAddr, MC_PORT))
                        OverlayLogger.v(TAG, "Broadcast gönderildi (${pong.size} byte)")
                    } catch (e: Exception) {
                        if (running.get()) OverlayLogger.w(TAG, "Broadcast send hatası: ${e.message}")
                    }
                    Thread.sleep(BROADCAST_INTERVAL)
                }
            } catch (e: InterruptedException) {
                // Normal stop
            } catch (e: Exception) {
                OverlayLogger.e(TAG, "Aktif broadcaster çöktü: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }, "OxLan-Broadcaster").apply {
            isDaemon = true
            start()
        }
    }

    // ── Paket Oluşturma ───────────────────────────────────────────────────

    /**
     * RakNet UNCONNECTED_PONG paketi oluşturur.
     *
     * Format:
     *   [1]  Packet ID   = 0x1C
     *   [8]  Ping time   (echo)
     *   [8]  Server GUID
     *   [16] Magic bytes
     *   [2]  String length (big-endian unsigned short)
     *   [N]  MCPE pong string (UTF-8)
     *
     * MCPE Pong String formatı:
     *   MCPE;<motd>;<protocol>;<version>;<players>;<maxPlayers>;<serverUID>;<subMotd>;<gameType>;1;<ipv4Port>;<ipv6Port>;
     */
    private fun buildPongPacket(pingTime: Long): ByteArray {
        val pongStr = buildPongString()
        val strBytes = pongStr.toByteArray(Charsets.UTF_8)

        // Toplam boyut: 1 + 8 + 8 + 16 + 2 + N
        val buf = ByteBuffer.allocate(1 + 8 + 8 + 16 + 2 + strBytes.size)
            .order(ByteOrder.BIG_ENDIAN)

        buf.put(UNCONNECTED_PONG)
        buf.putLong(pingTime)
        buf.putLong(serverGuid)
        buf.put(RAKNET_MAGIC)
        buf.putShort(strBytes.size.toShort())
        buf.put(strBytes)

        return buf.array()
    }

    /**
     * MCPE pong string'ini oluşturur.
     * Snapshot değerlerini okuyarak her çağrıda güncel bilgiyi verir.
     */
    private fun buildPongString(): String {
        // Lokal snapshot — thread safety için volatile alanları bir kez okuyoruz
        val p  = protocolVersion
        val mv = mcVersion
        val m  = motd.take(64)       // MC 64 karakter ile sınırlar
        val sm = subMotd.take(20)
        val pc = playerCount
        val mp = maxPlayers
        val rp = relayPort

        return "MCPE;$m;$p;$mv;$pc;$mp;$serverGuid;$sm;Survival;1;$rp;$rp;"
    }
}
