package com.oxclient.core.relay.listener

import android.util.Base64
import android.util.Log
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.core.relay.ClientIdentification
import com.oxclient.core.relay.ConnectionManager
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * LoginPacketListener — MITM relay'in en kritik listener'ı.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * Client → Server (downstream → upstream) akışı:
 *
 *   1. Client bir LoginPacket gönderir
 *   2. Bu listener onu yakalar
 *   3. [MicrosoftAuthManager.getActiveChainForRelay()] ile kayıtlı
 *      Bedrock JWT chain'ini alır
 *   4. Yeni bir EC keypair ile cihaz JWT'si üretip chain'e başa ekler
 *   5. Güncellenmiş LoginPacket'i server'a iletir
 *      → Online-mode sunucularda gerçek hesap kimliği ile doğrulanır
 *
 * Server → Client (upstream → downstream) akışı:
 *
 *   6. ServerToClientHandshakePacket gelirse şifreleme el sıkışmasını logla
 *   7. PlayStatusPacket gelirse ConnectionManager'ı bilgilendir
 *   8. StartGamePacket gelirse "PLAYING" durumuna geç
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Priority = 0 → AutoCodecListener'dan sonra, GamingPacketListener'dan önce.
 */
class LoginPacketListener : OxPacketListener {

    companion object {
        private const val TAG = "LoginPacketListener"
    }

    override val priority: Int = 0

    /** Session'a ait client kimliği — LoginPacket'ten parse edilir. */
    @Volatile var clientIdentification: ClientIdentification? = null
        private set

    @Volatile private var loginProcessed = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onSessionStart(session: OxRelaySession) {
        loginProcessed        = false
        clientIdentification  = null
        ConnectionManager.onHandshaking()
    }

    override fun onSessionEnd(session: OxRelaySession) {
        ConnectionManager.onDisconnected()
    }

    // ── Client → Server ───────────────────────────────────────────────────

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {

            // ── RequestNetworkSettings ────────────────────────────────────
            is RequestNetworkSettingsPacket -> {
                Log.d(TAG, "RequestNetworkSettings: protocol=${packet.protocolVersion}")
                // AutoCodecListener zaten codec'i ayarladı, buraya sadece log
            }

            // ── LoginPacket ───────────────────────────────────────────────
            is LoginPacket -> {
                if (!loginProcessed) {
                    loginProcessed = true
                    processLogin(packet, session)
                } else {
                    Log.w(TAG, "Tekrarlanan LoginPacket engellendi")
                }
            }

            // ── ClientToServerHandshake ───────────────────────────────────
            is ClientToServerHandshakePacket -> {
                Log.d(TAG, "ClientToServerHandshake → şifreleme aktif")
            }
        }
        return true
    }

    // ── Server → Client ───────────────────────────────────────────────────

    override fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {

            // ── NetworkSettings ───────────────────────────────────────────
            is NetworkSettingsPacket -> {
                Log.d(TAG, "NetworkSettings: compression=${packet.compressionAlgorithm}, threshold=${packet.compressionThreshold}")
            }

            // ── ServerToClientHandshake (şifreleme) ───────────────────────
            is ServerToClientHandshakePacket -> {
                Log.d(TAG, "ServerToClientHandshake alındı")
                handleServerHandshake(packet, session)
            }

            // ── PlayStatus ────────────────────────────────────────────────
            is PlayStatusPacket -> {
                Log.d(TAG, "PlayStatus: ${packet.status}")
                when (packet.status) {
                    PlayStatusPacket.Status.LOGIN_SUCCESS -> {
                        Log.i(TAG, "Login başarılı")
                        ConnectionManager.onHandshaking()
                    }
                    PlayStatusPacket.Status.PLAYER_SPAWN -> {
                        Log.i(TAG, "Oyuncu spawn oldu")
                        ConnectionManager.onGameStarted()
                    }
                    PlayStatusPacket.Status.LOGIN_FAILED_CLIENT,
                    PlayStatusPacket.Status.LOGIN_FAILED_SERVER -> {
                        Log.e(TAG, "Login başarısız: ${packet.status}")
                    }
                    else -> {}
                }
            }

            // ── StartGame ─────────────────────────────────────────────────
            is StartGamePacket -> {
                Log.i(TAG, "StartGame → oyun içi aşamaya geçildi")
                ConnectionManager.onGameStarted()
            }

            // ── Disconnect ────────────────────────────────────────────────
            is DisconnectPacket -> {
                Log.w(TAG, "Server disconnect: ${packet.kickMessage}")
            }
        }
        return true
    }

    // ── Login İşleme ──────────────────────────────────────────────────────

    private fun processLogin(packet: LoginPacket, session: OxRelaySession) {
        Log.d(TAG, "LoginPacket işleniyor: protocol=${packet.protocolVersion}")

        // 1. Client kimliğini parse et
        try {
            clientIdentification = ClientIdentification.fromLogin(
                chainJson = packet.chain ?: "{}",
                extraJson = packet.extra  ?: ""
            )
            Log.i(TAG, "Client kimliği: $clientIdentification")
        } catch (e: Exception) {
            Log.w(TAG, "ClientIdentification parse hatası: ${e.message}")
        }

        // 2. Kayıtlı auth chain'i enjekte et
        injectAuthChain(packet)
    }

    /**
     * LoginPacket.chain'ini MicrosoftAuthManager'dan gelen Bedrock JWT chain'i ile değiştirir.
     * Hesap giriş yapılmamışsa veya chain alınamazsa orijinal paket değişmeden iletilir.
     */
    private fun injectAuthChain(packet: LoginPacket) {
        val savedChainJson = MicrosoftAuthManager.getActiveChainForRelay()

        if (savedChainJson.isNullOrBlank()) {
            Log.w(TAG, "Kayıtlı chain yok — orijinal LoginPacket iletiliyor (offline)")
            return
        }

        try {
            // EC keypair üret
            val keyGen  = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            val keyPair = keyGen.generateKeyPair()
            val pubKey  = keyPair.public  as ECPublicKey
            val privKey = keyPair.private as ECPrivateKey
            val pubKeyB64 = Base64.encodeToString(
                pubKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            // Kaydedilen chain'i parse et
            val savedJwts: List<String> = parseSavedChain(savedChainJson)

            // Cihaz JWT'si oluştur ve başa ekle
            val deviceJwt = buildDeviceJwt(privKey, pubKeyB64)
            val fullChain = buildList {
                add(deviceJwt)
                addAll(savedJwts.filter { it != deviceJwt })
            }

            // Yeni chain wrapper
            val newChainWrapper = JSONObject().apply {
                put("chain", JSONArray(fullChain))
            }.toString()

            // LoginPacket'e uygula (extra/skin korunur)
            packet.chain = newChainWrapper
            Log.i(TAG, "Chain enjekte edildi: ${fullChain.size} JWT, pubKey=${pubKeyB64.take(20)}…")

        } catch (e: Exception) {
            Log.e(TAG, "Chain enjeksiyonu başarısız — orijinal gönderiliyor: ${e.message}", e)
        }
    }

    /**
     * Sunucudan gelen ServerToClientHandshake paketini işler.
     * Relay modunda şifreleme el sıkışması transparandır:
     * client ve server kendi aralarında şifreli kanal kurar,
     * relay sadece loglar ve paketleri geçirir.
     */
    private fun handleServerHandshake(
        packet: ServerToClientHandshakePacket,
        session: OxRelaySession
    ) {
        // Relay'de şifreleme bypass: paketi olduğu gibi client'a ilet
        // BedrockSession.enableEncryption() çağrısı CloudburstMC tarafından
        // otomatik yönetilir; burada müdahale gerekmez.
        Log.d(TAG, "Handshake JWT: ${packet.jwt?.take(60)}…")
    }

    // ── JWT / Crypto Yardımcıları ─────────────────────────────────────────

    /**
     * Kayıtlı mcToken'ı (JSON array veya ham string) JWT listesine dönüştürür.
     */
    private fun parseSavedChain(chainJson: String): List<String> {
        return try {
            when {
                // {"chain": [...]} formatı
                chainJson.trimStart().startsWith("{") -> {
                    val arr = JSONObject(chainJson).optJSONArray("chain")
                    if (arr != null) (0 until arr.length()).map { arr.getString(it) }
                    else listOf(chainJson)
                }
                // [...] formatı
                chainJson.trimStart().startsWith("[") -> {
                    val arr = JSONArray(chainJson)
                    (0 until arr.length()).map { arr.getString(it) }
                }
                // Ham token string
                else -> listOf(chainJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chain parse hatası: ${e.message}")
            listOf(chainJson)
        }
    }

    /**
     * ES256 cihaz JWT'si imzalar.
     *
     * Header : {"alg":"ES256","x5u":"<pubKeyB64>"}
     * Payload: {"certificateAuthority":true, "identityPublicKey":"<pubKeyB64>",
     *            "exp":<now+86400>, "nbf":<now-1>, "iat":<now>, "iss":"Minecraft"}
     */
    private fun buildDeviceJwt(privKey: ECPrivateKey, pubKeyB64: String): String {
        val now = System.currentTimeMillis() / 1000L

        val header  = b64Url("""{"alg":"ES256","x5u":"$pubKeyB64"}""".toByteArray())
        val payload = b64Url((
            """{"certificateAuthority":true,"identityPublicKey":"$pubKeyB64",""" +
            """"exp":${now + 86400},"nbf":${now - 1},"iat":$now,"iss":"Minecraft"}"""
        ).toByteArray())
        val sigInput = "$header.$payload"

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privKey)
        signer.update(sigInput.toByteArray(Charsets.US_ASCII))

        return "$sigInput.${b64Url(derToRaw(signer.sign()))}"
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        i++ // 0x02 tag
        val rLen = der[i++].toInt() and 0xFF
        val r    = der.copyOfRange(i, i + rLen); i += rLen
        i++ // 0x02 tag
        val sLen = der[i++].toInt() and 0xFF
        val s    = der.copyOfRange(i, i + sLen)
        return padOrTrim(BigInteger(1, r).toByteArray(), 32) +
               padOrTrim(BigInteger(1, s).toByteArray(), 32)
    }

    private fun padOrTrim(b: ByteArray, sz: Int) = when {
        b.size == sz -> b
        b.size > sz  -> b.copyOfRange(b.size - sz, b.size)
        else         -> ByteArray(sz - b.size) + b
    }

    private fun b64Url(data: ByteArray) =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
