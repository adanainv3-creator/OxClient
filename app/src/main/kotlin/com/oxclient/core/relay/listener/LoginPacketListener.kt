package com.oxclient.core.relay.listener

import android.util.Base64
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.core.relay.ClientIdentification
import com.oxclient.core.relay.ConnectionManager
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload
import org.cloudburstmc.protocol.bedrock.packet.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * LoginPacketListener — WRelay OnlineLoginPacketListener ile birebir akış.
 *
 * ═══════════════════════════════════════════════════════════════════
 * AKIŞ:
 *  1. [C→R] RequestNetworkSettingsPacket
 *     → AutoCodecListener (priority=-10) halleder, biz sadece geçiririz
 *
 *  2. [C→R] LoginPacket
 *     → Chain inject
 *     → session.connectToServer() — İLK KEZ burada server'a bağlanılır
 *     → Paketi tutarız, server hazır olunca göndereceğiz
 *     → return false (server'a henüz iletme)
 *
 *  3. Server bağlantısı kurulunca onConnected callback:
 *     → Server'a RequestNetworkSettings gönder
 *
 *  4. [S→R] NetworkSettingsPacket
 *     → serverSession.setCompression() — server ZLIB açılır
 *     → Saklanan Login paketini server'a gönder
 *     → return false (client'a iletme — client zaten AutoCodec'ten aldı)
 *
 *  5. [S→R] ServerToClientHandshake
 *     → Şifreleme anahtarını üret
 *     → serverSession.enableEncryption()
 *     → Server'a ClientToServerHandshake gönder
 *     → Client'a da ilet (return true)
 *
 *  6. [C→R] ClientToServerHandshake
 *     → Server'a ilet (return true)
 *
 *  7. [S→R] PlayStatus(LOGIN_SUCCESS) → ConnectionManager.onHandshaking()
 *  8. [S→R] StartGame → ConnectionManager.onGameStarted()
 * ═══════════════════════════════════════════════════════════════════
 */
class LoginPacketListener : OxPacketListener {

    companion object {
        private const val TAG = "LoginPacketListener"
    }

    override val priority: Int = 0

    @Volatile var clientIdentification: ClientIdentification? = null
        private set

    @Volatile private var loginProcessed = false
    @Volatile private var pendingLogin  : LoginPacket? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onSessionStart(session: OxRelaySession) {
        // Server bağlantısı kurulunca çağrılır — Login akışı devam ediyor demek
        OverlayLogger.d(TAG, "onSessionStart — server bağlandı")
        ConnectionManager.onHandshaking()
    }

    override fun onSessionEnd(session: OxRelaySession) {
        pendingLogin = null
        ConnectionManager.onDisconnected()
    }

    // ── Client → Relay ────────────────────────────────────────────────────

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {

            is RequestNetworkSettingsPacket -> {
                // AutoCodecListener (priority=-10) zaten halletti
                // Biz geçiriyoruz (server henüz yok, sendToServer kuyruğa alır)
                OverlayLogger.d(TAG, "RequestNetworkSettings geçti — protocol=${packet.protocolVersion}")
            }

            is LoginPacket -> {
                if (loginProcessed) {
                    OverlayLogger.w(TAG, "Tekrarlanan Login engellendi")
                    return false
                }
                loginProcessed = true
                OverlayLogger.i(TAG, "Login alındı — server bağlantısı başlatılıyor")

                // Kimlik bilgilerini parse et
                val chainJson = extractChainJson(packet)
                val extraJson = extractExtraJson(packet)
                try {
                    clientIdentification = ClientIdentification.fromLogin(chainJson, extraJson)
                    OverlayLogger.i(TAG, "Client: $clientIdentification")
                } catch (e: Exception) {
                    OverlayLogger.w(TAG, "ClientIdentification parse hatası: ${e.message}")
                }

                // Xbox chain'i inject et
                injectAuthChain(packet)

                // Login'i sakla — server NetworkSettings gönderince iletiriz
                pendingLogin = packet

                // Server'a bağlan — callback: RequestNetworkSettings gönder
                session.connectToServer {
                    OverlayLogger.i(TAG, "Server hazır — RequestNetworkSettings gönderiliyor")
                    val reqNet = RequestNetworkSettingsPacket().apply {
                        protocolVersion = session.activeCodec.protocolVersion
                    }
                    session.sendToServer(reqNet)
                }

                // Paketi server'a iletme — biz yönettik
                return false
            }

            is ClientToServerHandshakePacket -> {
                OverlayLogger.d(TAG, "ClientToServerHandshake → iletiliyor")
                // İlet
            }
        }
        return true
    }

    // ── Server → Relay ────────────────────────────────────────────────────

    override fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {

            // Server NetworkSettings → server compression aç → Login gönder
            is NetworkSettingsPacket -> {
                OverlayLogger.d(TAG, "Server NetworkSettings: algo=${packet.compressionAlgorithm} threshold=${packet.compressionThreshold}")

                try {
                    val algo = if (packet.compressionThreshold > 0)
                        packet.compressionAlgorithm
                    else
                        PacketCompressionAlgorithm.NONE

                    session.serverSession?.setCompression(algo)
                    OverlayLogger.i(TAG, "Server compression: $algo")
                } catch (e: Exception) {
                    OverlayLogger.w(TAG, "Server compression ayarlanamadı: ${e.message}")
                }

                val login = pendingLogin
                if (login != null) {
                    OverlayLogger.i(TAG, "Login server'a gönderiliyor")
                    session.sendToServer(login)
                    pendingLogin = null
                } else {
                    OverlayLogger.e(TAG, "HATA: pendingLogin null — Login kaybedildi!")
                    session.disconnect("Login paketi kayboldu")
                }

                // Client'a iletme — client zaten AutoCodecListener'dan NetworkSettings aldı
                return false
            }

            // Server şifreleme başlatıyor
            is ServerToClientHandshakePacket -> {
                OverlayLogger.d(TAG, "ServerToClientHandshake alındı")
                try {
                    enableEncryption(packet, session)
                } catch (e: Exception) {
                    OverlayLogger.w(TAG, "Şifreleme başarısız (devam ediliyor): ${e.message}")
                    // Şifreleme yoksa sadece handshake gönder
                    session.sendToServer(ClientToServerHandshakePacket())
                }
                // Client'a da ilet
                return true
            }

            is PlayStatusPacket -> {
                OverlayLogger.d(TAG, "PlayStatus: ${packet.status}")
                when (packet.status) {
                    PlayStatusPacket.Status.LOGIN_SUCCESS -> {
                        OverlayLogger.i(TAG, "Login başarılı ✓")
                        ConnectionManager.onHandshaking()
                    }
                    PlayStatusPacket.Status.PLAYER_SPAWN -> {
                        OverlayLogger.i(TAG, "Oyuncu spawn ✓")
                        ConnectionManager.onGameStarted()
                    }
                    else -> {
                        if (packet.status.name.contains("FAIL", ignoreCase = true))
                            OverlayLogger.e(TAG, "Login BAŞARISIZ: ${packet.status}")
                    }
                }
            }

            is StartGamePacket -> {
                OverlayLogger.i(TAG, "StartGame → oyun içi")
                ConnectionManager.onGameStarted()
            }

            is DisconnectPacket -> {
                OverlayLogger.w(TAG, "Server Disconnect: ${packet.kickMessage}")
            }
        }
        return true
    }

    // ── Şifreleme ─────────────────────────────────────────────────────────

    private fun enableEncryption(packet: ServerToClientHandshakePacket, session: OxRelaySession) {
        val jwt    = packet.jwt ?: throw IllegalStateException("JWT null")
        val parts  = jwt.split(".")
        require(parts.size == 3) { "Geçersiz JWT format" }

        val decoder     = java.util.Base64.getUrlDecoder()
        val headerJson  = JSONObject(String(decoder.decode(parts[0])))
        val payloadJson = JSONObject(String(decoder.decode(parts[1])))

        val x5u  = headerJson.getString("x5u")
        val salt = java.util.Base64.getDecoder().decode(payloadJson.getString("salt"))

        val serverPubKey = org.cloudburstmc.protocol.bedrock.util.EncryptionUtils.parseKey(x5u)
        val privateKey   = MicrosoftAuthManager.getPrivateKeyForEncryption()
            ?: throw IllegalStateException("Encryption private key yok")

        val secretKey = org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
            .getSecretKey(privateKey, serverPubKey, salt)

        session.serverSession?.enableEncryption(secretKey)
        OverlayLogger.i(TAG, "Server şifreleme aktif ✓")

        session.sendToServer(ClientToServerHandshakePacket())
    }

    // ── Auth Chain Inject ─────────────────────────────────────────────────
    //
    // v818+ ile LoginPacket.chain/extra alanları kaldırıldı, yerine geldi:
    //   packet.authPayload : AuthPayload (CertificateChainPayload ya da TokenPayload)
    //   packet.clientJwt    : String      (eski "extra"nın doğrudan karşılığı)
    //
    // AuthType.FULL — wclient'in OnlineLoginPacketListener.kt'sinde aynı
    // pattern doğrulandı: loginPacket.authPayload = CertificateChainPayload(chain, AuthType.FULL)
    //
    // ═══════════════════════════════════════════════════════════════════
    // KRİTİK FİX — "Invalid login data (identifier)" sebebi:
    // ──────────────────────────────────────────────────────
    // MicrosoftAuthManager.fetchMinecraftChain() ZATEN kendi içinde bir
    // keypair üretip onunla imzalanmış bir "deviceJwt"yi chain'in başına
    // ekliyor — yani getActiveChainForRelay()'den dönen chain, baştan sona
    // kriptografik olarak TUTARLI ve TAMAMLANMIŞ bir zincir.
    //
    // Önceki sürümde burada İKİNCİ bir rastgele keypair daha üretilip
    // ikinci bir deviceJwt zincirin başına ekleniyordu. Çözüm: ekstra
    // keypair/deviceJwt üretmeyi tamamen kaldırıp kayıtlı chain'i OLDUĞU
    // GİBİ kullanmak.
    //
    // İKİNCİ HATA: clientJwt (skin/client data) — sunucu bunun imzasını
    // chain'in ilk linkinin public key'ine göre doğruluyor. Client'ın
    // kendi clientJwt'si KENDİ key'iyle imzalı, bizim chain'imizle alakası
    // yok. Bu yüzden clientJwt'yi de AYNI key ile yeniden imzalıyoruz.
    //
    // ÜÇÜNCÜ HATA: Mojang Bedrock auth secp384r1 (P-384) + ES384 kullanır —
    // önceden kullanılan secp256r1/ES256 tek başına reddedilme sebebiydi.
    // ═══════════════════════════════════════════════════════════════════

    private fun injectAuthChain(packet: LoginPacket) {
        val savedChain = MicrosoftAuthManager.getActiveChainForRelay()
        if (savedChain.isNullOrBlank()) {
            OverlayLogger.w(TAG, "Kayıtlı chain yok — offline mod (orijinal authPayload iletiliyor)")
            return
        }
        val privKeyB64 = MicrosoftAuthManager.getActivePrivateKeyForRelay()
        if (privKeyB64.isNullOrBlank()) {
            OverlayLogger.e(TAG, "Kayıtlı private key yok — chain enjeksiyonu atlandı (tekrar login gerekebilir)")
            return
        }
        val pubKeyB64 = MicrosoftAuthManager.getActivePublicKeyForRelay()
        if (pubKeyB64.isNullOrBlank()) {
            OverlayLogger.e(TAG, "Kayıtlı public key yok — chain enjeksiyonu atlandı (tekrar login gerekebilir)")
            return
        }

        try {
            val savedJwts = parseSavedChain(savedChain)
            if (savedJwts.isEmpty()) {
                OverlayLogger.e(TAG, "Kayıtlı chain boş/parse edilemedi — enjeksiyon atlandı")
                return
            }

            packet.authPayload = CertificateChainPayload(savedJwts, AuthType.FULL)
            OverlayLogger.i(TAG, "Chain enjekte edildi [authPayload]: ${savedJwts.size} JWT (orijinal, değiştirilmeden), authType=FULL")

            // ── clientJwt (skin/client data) yeniden imzalama — KRİTİK ─────
            // Sunucu clientJwt'nin imzasını chain'in ilk linkinin public
            // key'ine göre doğruluyor. Orijinal client kendi key'iyle
            // imzalamış, bizim chain'imizle alakası yok — payload'ı
            // (değiştirmeden) alıp AYNI key ile yeniden imzalıyoruz.
            val originalClientJwt = packet.clientJwt
            if (!originalClientJwt.isNullOrBlank()) {
                val resigned = resignClientJwt(originalClientJwt, privKeyB64, pubKeyB64)
                if (resigned != null) {
                    packet.clientJwt = resigned
                    OverlayLogger.i(TAG, "clientJwt yeniden imzalandı ✓ (chain key'i ile)")
                } else {
                    OverlayLogger.e(TAG, "clientJwt yeniden imzalanamadı — login muhtemelen reddedilecek")
                }
            } else {
                OverlayLogger.w(TAG, "clientJwt boş — skin data yok")
            }

        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Chain inject hatası: ${e.message}", e)
        }
    }

    // ── JWT / Base64 Yardımcıları ─────────────────────────────────────────

    private fun parseSavedChain(json: String): List<String> = try {
        when {
            json.trimStart().startsWith("{") -> {
                val arr = JSONObject(json).optJSONArray("chain")
                if (arr != null) (0 until arr.length()).map { arr.getString(it) }
                else emptyList()
            }
            json.trimStart().startsWith("[") -> {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            }
            else -> listOf(json)
        }
    } catch (e: Exception) {
        OverlayLogger.w(TAG, "Chain parse hatası: ${e.message}")
        emptyList()
    }

    private fun buildDeviceJwt(priv: ECPrivateKey, pubB64: String): String {
        val now     = System.currentTimeMillis() / 1000L
        // Mojang Bedrock auth secp384r1 (P-384) + ES384 kullanır.
        val header  = b64Url("""{"alg":"ES384","x5u":"$pubB64"}""".toByteArray())
        val payload = b64Url(
            ("""{"certificateAuthority":true,"identityPublicKey":"$pubB64",""" +
             """"exp":${now + 86400},"nbf":${now - 1},"iat":$now,"iss":"Minecraft"}""")
                .toByteArray()
        )
        val data = "$header.$payload"
        val sig  = Signature.getInstance("SHA384withECDSA").apply {
            initSign(priv)
            update(data.toByteArray(Charsets.US_ASCII))
        }.sign()
        return "$data.${b64Url(derToRaw(sig))}"
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        i++ // skip r tag 0x02
        val rLen = der[i++].toInt() and 0xFF
        val r = der.copyOfRange(i, i + rLen); i += rLen
        i++ // skip s tag 0x02
        val sLen = der[i++].toInt() and 0xFF
        val s = der.copyOfRange(i, i + sLen)
        // P-384 imza bileşenleri 48 byte'tır (P-256'da 32 idi)
        return pad48(BigInteger(1, r).toByteArray()) + pad48(BigInteger(1, s).toByteArray())
    }

    private fun pad48(b: ByteArray): ByteArray = when {
        b.size == 48 -> b
        b.size >  48 -> b.copyOfRange(b.size - 48, b.size)
        else         -> ByteArray(48 - b.size) + b
    }

    /**
     * clientJwt'nin (skin/client data) PAYLOAD'ını değiştirmeden alıp,
     * MicrosoftAuthManager'da saklanan chain key'i ile yeniden imzalar.
     * pubKeyB64 doğrudan SavedAccount'tan geldiği için EC point çarpımı
     * gibi riskli manuel kriptografiye gerek yok.
     */
    private fun resignClientJwt(originalClientJwt: String, privKeyB64: String, pubKeyB64: String): String? {
        try {
            val parts = originalClientJwt.split(".")
            if (parts.size != 3) {
                OverlayLogger.e(TAG, "clientJwt formatı geçersiz (3 parça bekleniyor, ${parts.size} bulundu)")
                return null
            }
            val payloadJson = parts[1] // skin/cape/vb. veriler — değiştirilmeden aynen kullanılır

            val keyBytes = Base64.decode(privKeyB64, Base64.NO_WRAP)
            val privKey  = KeyFactory.getInstance("EC")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes)) as ECPrivateKey

            val header = b64Url("""{"alg":"ES384","x5u":"$pubKeyB64"}""".toByteArray())
            val data   = "$header.$payloadJson"
            val sig    = Signature.getInstance("SHA384withECDSA").apply {
                initSign(privKey)
                update(data.toByteArray(Charsets.US_ASCII))
            }.sign()

            return "$data.${b64Url(derToRaw(sig))}"
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "clientJwt yeniden imzalama hatası: ${e.message}", e)
            return null
        }
    }

    private fun b64Url(data: ByteArray) =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun extractChainJson(packet: LoginPacket): String = try {
        when (val auth = packet.authPayload) {
            is CertificateChainPayload -> JSONObject().apply {
                put("chain", JSONArray(auth.chain))
            }.toString()
            is TokenPayload -> JSONObject().apply {
                put("token", auth.token)
            }.toString()
            else -> "{}"
        }
    } catch (_: Exception) { "{}" }

    private fun extractExtraJson(packet: LoginPacket): String = try {
        packet.clientJwt ?: ""
    } catch (_: Exception) { "" }
}
