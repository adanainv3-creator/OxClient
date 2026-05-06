package com.oxclient.relay.listener

import android.util.Base64
import com.google.gson.Gson
import com.oxclient.relay.OxAddress
import com.oxclient.relay.session.OxRelaySession
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import timber.log.Timber
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * OxLoginListener — MITM login akışını yönetir.
 *
 * 1. MC App → LoginPacket → relay sunucuya bağlanır
 * 2. Relay → Sunucuya OxClient mcToken'ıyla LoginPacket gönderir
 * 3. Sunucu → ServerToClientHandshakePacket → şifreleme başlatılır
 * 4. Relay → İstemciye PlayStatus(LOGIN_SUCCESS) bildirir
 */
class OxLoginListener(
    private val session      : OxRelaySession,
    private val remoteAddress: OxAddress,
    private val mcToken      : String,
    private val gamertag     : String
) : OxPacketListener {

    private val gson    = Gson()
    private val keyPair : KeyPair by lazy {
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp384r1")) }
            .generateKeyPair()
    }

    private var loginHandled = false

    // MC App → Relay  (istemciden gelen)
    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (loginHandled || packet !is LoginPacket) return false
        loginHandled = true
        Timber.i("[Login] LoginPacket alındı — sunucuya bağlanılıyor: $remoteAddress")
        session.relay.connectToServer(remoteAddress) {
            Timber.i("[Login] Sunucuya bağlanıldı")
            sendLoginToServer(packet)
        }
        return true   // MC App'e iletme
    }

    // Sunucu → Relay  (sunucudan gelen)
    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        when (packet) {
            is ServerToClientHandshakePacket -> {
                handleServerHandshake(packet)
                return true
            }
            is PlayStatusPacket -> {
                if (packet.status == PlayStatusPacket.Status.LOGIN_SUCCESS) {
                    Timber.i("[Login] Sunucu login başarılı")
                    session.clientBound(packet)
                    return true
                }
                if (packet.status == PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD ||
                    packet.status == PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD) {
                    Timber.e("[Login] Protokol uyumsuzluğu: ${packet.status}")
                }
            }
            else -> {}
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun sendLoginToServer(original: LoginPacket) {
        try {
            val loginPacket = LoginPacket().apply {
                protocolVersion = original.protocolVersion
                val chain: List<String> = if (mcToken.startsWith("["))
                    gson.fromJson(mcToken, List::class.java) as List<String>
                else listOf(mcToken)
                this.chain.addAll(chain)
            }
            session.clientSide?.sendPacketImmediately(loginPacket)
            Timber.i("[Login] LoginPacket sunucuya gönderildi (gamertag=$gamertag)")
        } catch (e: Exception) {
            Timber.e(e, "[Login] sendLoginToServer hata")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleServerHandshake(packet: ServerToClientHandshakePacket) {
        try {
            val parts = packet.jwt.split(".")
            if (parts.size < 2) { Timber.e("[Login] Geçersiz handshake JWT"); return }

            val payloadJson = String(
                Base64.decode(parts[1].replace('-', '+').replace('_', '/'), Base64.DEFAULT),
                Charsets.UTF_8
            )
            val payload     = gson.fromJson(payloadJson, Map::class.java) as Map<String, Any?>
            val keyB64      = payload["identityPublicKey"] as? String
                ?: run { Timber.e("[Login] identityPublicKey yok"); return }
            val saltB64     = payload["salt"] as? String ?: ""
            val salt        = if (saltB64.isNotBlank())
                Base64.decode(saltB64.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            else ByteArray(0)

            val serverKey    = decodeEcPublicKey(keyB64)
            val sharedSecret = EncryptionUtils.getSecretKey(keyPair.private, serverKey, salt)
            session.clientSide?.enableEncryption(sharedSecret)
            Timber.i("[Login] Sunucu şifrelemesi aktif")

            session.clientSide?.sendPacketImmediately(ClientToServerHandshakePacket())
            session.clientBoundImmediate(
                PlayStatusPacket().apply { status = PlayStatusPacket.Status.LOGIN_SUCCESS }
            )
        } catch (e: Exception) {
            Timber.e(e, "[Login] handleServerHandshake hata")
        }
    }

    private fun decodeEcPublicKey(b64: String): ECPublicKey =
        KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT))) as ECPublicKey
}
