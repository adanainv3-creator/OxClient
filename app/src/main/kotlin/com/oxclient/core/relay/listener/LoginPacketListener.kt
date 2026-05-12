package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.auth.AccountManager
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import org.jose4j.json.JsonUtil
import org.jose4j.json.internal.json_simple.JSONObject
import org.jose4j.jws.JsonWebSignature
import java.util.Base64

/**
 * LoginPacketListener — Microsoft auth ile login ve şifreleme handshake'ini yönetir.
 * WRelay OnlineLoginPacketListener'dan adapte edildi — OxClient AccountManager ile entegre.
 *
 * Akış:
 *   C→S LoginPacket       → skin verisini sakla, sunucuya bağlan
 *   S→C NetworkSettings   → sıkıştırma kur, login paketi gönder
 *   S→C ServerToClientHandshake → şifrelemeyi etkinleştir, handshake yanıtla
 */
class LoginPacketListener(
    private val session: OxRelaySession
) : OxPacketListener {

    private val TAG = "LoginPacketListener"

    // İstemcinin skin JWT payload'ı — login sırasında yakalanır
    private var skinData: JSONObject? = null

    // ── İstemciden gelen paketler (C→S) ──────────────────────────────────

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (packet is LoginPacket) {
            Log.i(TAG, "Login paketi alındı")
            try {
                // Skin verisini JWT'den çıkar
                val jws = JsonWebSignature()
                jws.compactSerialization = packet.clientJwt
                skinData = JSONObject(JsonUtil.parseJson(jws.unverifiedPayload))

                // Sunucuya bağlan
                connectToServer()
            } catch (e: Exception) {
                Log.e(TAG, "Login işleme hatası: ${e.message}", e)
                session.server.disconnect("Login işlenemedi: ${e.message}")
            }
            return true  // orijinal paketi durdur — kendi login paketimizi göndereceğiz
        }
        return false
    }

    // ── Sunucudan gelen paketler (S→C) ────────────────────────────────────

    override fun beforeServerBound(packet: BedrockPacket): Boolean {

        // NetworkSettings → sıkıştırmayı kur, login paketi gönder
        if (packet is NetworkSettingsPacket) {
            try {
                val algo = if (packet.compressionThreshold > 0)
                    packet.compressionAlgorithm
                else
                    PacketCompressionAlgorithm.NONE

                session.client!!.setCompression(algo)
                Log.i(TAG, "Client sıkıştırma: $algo (threshold=${packet.compressionThreshold})")

                sendLoginToServer()
            } catch (e: Exception) {
                Log.e(TAG, "NetworkSettings hatası: ${e.message}", e)
                session.server.disconnect("Auth başarısız: ${e.message}")
            }
            return true
        }

        // ServerToClientHandshake → şifrelemeyi etkinleştir
        if (packet is ServerToClientHandshakePacket) {
            try {
                val parts = packet.jwt.split(".")
                require(parts.size == 3) { "Geçersiz JWT formatı" }

                val headerJson  = String(Base64.getUrlDecoder().decode(parts[0]))
                val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]))

                val header  = JSONObject(JsonUtil.parseJson(headerJson))
                val payload = JSONObject(JsonUtil.parseJson(payloadJson))

                val x5u       = header["x5u"]  as? String ?: error("x5u eksik")
                val saltStr   = payload["salt"] as? String ?: error("salt eksik")
                val serverKey = EncryptionUtils.parseKey(x5u)
                val salt      = Base64.getDecoder().decode(saltStr)

                val account = AccountManager.currentAccount
                    ?: error("Hesap bulunamadı — lütfen giriş yapın")

                val secretKey = EncryptionUtils.getSecretKey(
                    account.privateKey,
                    serverKey,
                    salt
                )
                session.client!!.enableEncryption(secretKey)
                Log.i(TAG, "Şifreleme etkinleştirildi")

                session.serverBoundImmediately(ClientToServerHandshakePacket())
            } catch (e: Exception) {
                Log.e(TAG, "Handshake hatası: ${e.message}", e)
                session.server.disconnect("Handshake başarısız: ${e.message}")
            }
            return true
        }

        return false
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────

    private fun connectToServer() {
        session.relay.connectToServer {
            Log.i(TAG, "Sunucu bağlantısı kuruldu, NetworkSettings isteği gönderiliyor")
            val req = RequestNetworkSettingsPacket().apply {
                protocolVersion = session.server.codec.protocolVersion
            }
            session.serverBoundImmediately(req)
        }
    }

    private fun sendLoginToServer() {
        val account = AccountManager.currentAccount
            ?: run {
                Log.e(TAG, "Hesap yok, login gönderilemedi")
                session.server.disconnect("Hesap bulunamadı — lütfen OxClient'ta giriş yapın")
                return
            }

        try {
            // AccountManager'dan chain ve skin data al
            val chain    = account.buildCertificateChain()
            val skinJwt  = account.buildSkinJwt(skinData, session.relay.remoteHost)

            val loginPacket = LoginPacket().apply {
                protocolVersion = session.server.codec.protocolVersion
                authPayload     = CertificateChainPayload(chain, AuthType.FULL)
                clientJwt       = skinJwt
            }
            session.serverBoundImmediately(loginPacket)
            Log.i(TAG, "Login paketi gönderildi (${account.displayName})")
        } catch (e: Exception) {
            Log.e(TAG, "Login paketi gönderilemedi: ${e.message}", e)
            session.server.disconnect("Authentication başarısız: ${e.message}")
        }
    }
}
