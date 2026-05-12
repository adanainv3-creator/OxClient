package com.oxclient.auth

import android.util.Base64
import android.util.Log
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import org.jose4j.json.internal.json_simple.JSONObject
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

// ── Kayıtlı hesap verisi ──────────────────────────────────────────────────────

/**
 * SavedAccount — Diske kaydedilen hesap verisi.
 *
 * mcToken: Minecraft /authentication'dan dönen JWT chain (JSON array string).
 * buildCertificateChain / buildSkinJwt → LoginPacketListener tarafından kullanılır.
 */
data class SavedAccount(
    val gamertag    : String,
    val refreshToken: String,
    val mcToken     : String,          // JSON array string: ["jwt1","jwt2",...]
    val expireTimeMs: Long             // System.currentTimeMillis() + 6h
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() > expireTimeMs

    /**
     * Login paketinde gönderilecek certificate chain listesini döner.
     * mcToken bir JSON array string olarak saklanıyor.
     */
    fun buildCertificateChain(): List<String> {
        return try {
            @Suppress("UNCHECKED_CAST")
            com.google.gson.Gson().fromJson(mcToken, List::class.java) as List<String>
        } catch (e: Exception) {
            Log.w("SavedAccount", "Chain parse hatası, tek token olarak kullanılıyor")
            listOf(mcToken)
        }
    }

    /**
     * Sunucuya gönderilecek client JWT'sini (skin data) oluşturur.
     * skinData: İstemcinin orijinal Login paketindeki skin JSONObject'i.
     * remoteHost: Hedef sunucu hostname'i.
     */
    fun buildSkinJwt(skinData: JSONObject?, remoteHost: String): String {
        val keyHolder = HandshakeHandler
        val ecPrivate = keyHolder.privateKey as? ECPrivateKey
            ?: run {
                Log.w("SavedAccount", "EC private key yok, yeni üretiliyor")
                keyHolder.generateKeyPair()
                keyHolder.privateKey as ECPrivateKey
            }
        val ecPublic = keyHolder.publicKey as ECPublicKey

        val pubKeyB64 = Base64.encodeToString(ecPublic.encoded, Base64.NO_WRAP)

        // Skin payload: orijinal skinData ya da minimal fallback
        val payload = skinData?.toJSONString() ?: buildMinimalSkinPayload(gamertag, pubKeyB64)

        // JWT header
        val headerJson = """{"alg":"ES256","x5u":"$pubKeyB64"}"""
        val headerB64  = b64Url(headerJson.toByteArray())
        val payloadB64 = b64Url(payload.toByteArray())

        val sigInput = "$headerB64.$payloadB64"
        val signer   = Signature.getInstance("SHA256withECDSA")
        signer.initSign(ecPrivate)
        signer.update(sigInput.toByteArray(Charsets.US_ASCII))
        val sig = derToRaw(signer.sign())

        return "$sigInput.${b64Url(sig)}"
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────

    private fun buildMinimalSkinPayload(name: String, pubKeyB64: String): String {
        val now = System.currentTimeMillis() / 1000L
        return """{"ClientRandomId":${(Math.random() * Long.MAX_VALUE).toLong()},"CurrentInputMode":1,"DefaultInputMode":1,"DeviceId":"${java.util.UUID.randomUUID()}","DeviceModel":"Android","DeviceOS":1,"GameVersion":"1.21.60","GuiScale":-1,"LanguageCode":"en_US","PlatformOfflineId":"","PlatformOnlineId":"","PlayFabId":"","SelfSignedId":"${java.util.UUID.randomUUID()}","ServerAddress":"","SkinData":"","SkinGeometryData":"","SkinId":"Standard_CustomSlim","SkinImageHeight":64,"SkinImageWidth":64,"SkinResourcePatch":"","ThirdPartyName":"$name","ThirdPartyNameOnly":false,"UIProfile":0,"identityPublicKey":"$pubKeyB64"}"""
    }

    private fun b64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        check(der[0] == 0x30.toByte())
        check(der[i] == 0x02.toByte()); i++
        val rLen = der[i++].toInt() and 0xFF
        val r = der.copyOfRange(i, i + rLen); i += rLen
        check(der[i] == 0x02.toByte()); i++
        val sLen = der[i++].toInt() and 0xFF
        val s = der.copyOfRange(i, i + sLen)

        fun pad(b: ByteArray) = java.math.BigInteger(1, b).toByteArray().let { raw ->
            when {
                raw.size == 32 -> raw
                raw.size > 32  -> raw.copyOfRange(raw.size - 32, raw.size)
                else           -> ByteArray(32 - raw.size) + raw
            }
        }
        return pad(r) + pad(s)
    }
}

// ── Auth akışı durumu ─────────────────────────────────────────────────────────

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class WaitingForUser(val userCode: String, val verificationUri: String) : AuthState()
    data class Success(val gamertag: String, val mcToken: String) : AuthState()
    data class Error(val message: String) : AuthState()
}
