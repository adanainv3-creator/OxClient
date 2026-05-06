package com.oxclient.relay.listener

import android.util.Base64
import android.util.Log
import com.oxclient.auth.AccountManager
import com.oxclient.relay.OxRelaySession
import com.oxclient.relay.RelayPacketListener
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * OnlineLoginPacketListener
 *
 * Login akışını yönetir:
 * 1. Minecraft → relay: [LoginPacket] yakala → skin data sakla → sunucuya bağlan
 * 2. Sunucu → relay: [NetworkSettingsPacket] → compression ayarla → gerçek LoginPacket oluştur
 * 3. Sunucu → relay: [ServerToClientHandshakePacket] → JWT parse → encryption kur → handshake gönder
 */
class OnlineLoginPacketListener(
    private val session: OxRelaySession
) : RelayPacketListener {

    companion object {
        private const val TAG = "LoginListener"
    }

    private var skinData: String? = null
    private var serverEncryptionEnabled = false

    // ── Minecraft → Relay (serverBound) ──────────────────────────────────

    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet !is LoginPacket) return false

        Log.d(TAG, "LoginPacket intercept — sunucuya bağlanılıyor")

        // Skin / device data sakla
        skinData = extractSkinData(packet)

        // Gerçek sunucuya bağlan
        session.connectToServer { clientSession ->
            Log.d(TAG, "Sunucu bağlantısı kuruldu, login akışı devam ediyor")
        }

        return true // paketi şimdilik beklet, sunucu hazır olunca göndereceğiz
    }

    // ── Sunucu → Relay (clientBound) ─────────────────────────────────────

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        return when (packet) {
            is NetworkSettingsPacket    -> handleNetworkSettings(packet)
            is ServerToClientHandshakePacket -> handleHandshake(packet)
            else -> false
        }
    }

    private fun handleNetworkSettings(packet: NetworkSettingsPacket): Boolean {
        Log.d(TAG, "ServerNetworkSettings — compression ${packet.compressionAlgorithm}")

        val client = session.clientSession ?: return false
        client.setCompression(packet.compressionAlgorithm ?: PacketCompressionAlgorithm.ZLIB)

        // Şimdi gerçek login paketi oluştur ve gönder
        sendRealLogin()
        return true // Minecraft'a iletme
    }

    private fun handleHandshake(packet: ServerToClientHandshakePacket): Boolean {
        Log.d(TAG, "ServerToClientHandshake intercept — encryption kuruluyor")

        val client = session.clientSession ?: return false

        try {
            // JWT'den sunucunun public key'ini çıkar
            val jwt    = packet.jwt ?: return false
            val parts  = jwt.split(".")
            if (parts.size < 2) return false

            val header  = parseJwtPart(parts[0])
            val payload = parseJwtPart(parts[1])

            val x5u     = header.optString("x5u")
            val salt    = payload.optString("salt")

            if (x5u.isNotBlank() && salt.isNotBlank()) {
                // Encryption key türet ve aktif et
                val serverPubKey = decodeEcPublicKey(x5u)
                if (serverPubKey != null) {
                    client.enableEncryption(serverPubKey)
                    Log.d(TAG, "Client encryption aktif")
                }
            }

            // ClientToServerHandshake gönder
            client.sendPacketImmediately(ClientToServerHandshakePacket())
            Log.d(TAG, "ClientToServerHandshake gönderildi")

        } catch (e: Exception) {
            Log.e(TAG, "Handshake işleme hatası", e)
        }

        return true // Minecraft'a iletme
    }

    // ── Gerçek Login Paketi ───────────────────────────────────────────────

    private fun sendRealLogin() {
        val account = AccountManager.selectedAccount
        if (account == null) {
            Log.w(TAG, "Hesap yok — anonim login deneniyor")
            sendAnonymousLogin()
            return
        }

        Log.d(TAG, "Hesap bulundu: ${account.gamertag}")
        try {
            val loginPacket = buildLoginPacket(account.mcToken)
            session.clientSession?.sendPacketImmediately(loginPacket)
        } catch (e: Exception) {
            Log.e(TAG, "Login paketi gönderilemedi", e)
            sendAnonymousLogin()
        }
    }

    private fun sendAnonymousLogin() {
        try {
            val loginPacket = buildOfflineLoginPacket()
            session.clientSession?.sendPacketImmediately(loginPacket)
        } catch (e: Exception) {
            Log.e(TAG, "Anonim login başarısız", e)
            session.disconnect("Giriş yapılamadı")
        }
    }

    private fun buildLoginPacket(mcToken: String): LoginPacket {
        val packet = LoginPacket()
        packet.protocolVersion = session.protocolVersion

        // MC token JSON array'ini chain olarak kullan
        try {
            val chainJson = JSONObject().apply {
                put("chain", org.json.JSONArray(mcToken))
            }
            // Skin data varsa ekle
            val skinStr = skinData ?: buildDefaultSkinData()
            packet.chainData = chainJson.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Login paketi oluşturma hatası: ${e.message}")
        }

        return packet
    }

    private fun buildOfflineLoginPacket(): LoginPacket {
        val packet = LoginPacket()
        packet.protocolVersion = session.protocolVersion
        // Minimal offline chain
        packet.chainData = "{\"chain\":[]}"
        return packet
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────

    private fun extractSkinData(loginPacket: LoginPacket): String? {
        return try {
            loginPacket.chainData
        } catch (e: Exception) {
            Log.w(TAG, "Skin data çıkarılamadı")
            null
        }
    }

    private fun buildDefaultSkinData(): String {
        return "{\"SkinId\":\"default\",\"SkinData\":\"\"}"
    }

    private fun parseJwtPart(part: String): JSONObject {
        val padded = part + "=".repeat((4 - part.length % 4) % 4)
        val bytes  = Base64.decode(
            padded.replace('-', '+').replace('_', '/'),
            Base64.DEFAULT
        )
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun decodeEcPublicKey(x5u: String): ECPublicKey? {
        return try {
            val padded = x5u + "=".repeat((4 - x5u.length % 4) % 4)
            val bytes  = Base64.decode(
                padded.replace('-', '+').replace('_', '/'),
                Base64.DEFAULT
            )
            val keySpec = X509EncodedKeySpec(bytes)
            KeyFactory.getInstance("EC").generatePublic(keySpec) as ECPublicKey
        } catch (e: Exception) {
            Log.w(TAG, "EC public key decode hatası: ${e.message}")
            null
        }
    }
}
