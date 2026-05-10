package com.oxclient.core.proxy

import android.util.Base64
import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.events.PacketListener
import com.oxclient.ui.overlay.OverlayLogger
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.io.ByteArrayOutputStream

/**
 * LoginPacketInterceptor
 *
 * NEDEN GEREKLİ:
 * Minecraft kendi içinde bir EC key pair üretir ve Login paketinin
 * "chain" alanına kendi public key'ini koyar. Sunucu bu public key'i
 * alır ve ServerToClientHandshake'te ECDH için kullanır.
 *
 * MITMProxy handleHandshake() içinde HandshakeKeyHolder.privateKey ile
 * ECDH yapmaya çalışır — ama sunucu Minecraft'ın key'ini kullanmış,
 * bizim key'imizi değil. Sonuç: yanlış shared secret → yanlış AES key
 * → tüm paketler çözülemiyor → hileler çalışmıyor.
 *
 * ÇÖZÜM:
 * Login paketi (0x01) CLIENT_TO_SERVER yönünde intercept edilir.
 * Minecraft'ın chain'i alınır, içindeki "identityPublicKey" bizim
 * HandshakeKeyHolder.publicKey ile değiştirilir. Yeni chain kendi
 * private key'imizle ES256 ile yeniden imzalanır. Sunucu artık bizim
 * public key'imizle handshake yapar → handleHandshake() doğru çalışır.
 *
 * KAYIT:
 * MITMProxy.start() içinde register() çağrılmalı.
 * MITMProxy.stop() içinde unregister() çağrılmalı.
 */
object LoginPacketInterceptor : PacketListener {

    private const val TAG = "LoginInterceptor"
    override val priority: Int = 1  // EntityTracker'dan önce (priority=10), en yüksek öncelik

    fun register()   { PacketEventBus.register(this) }
    fun unregister() { PacketEventBus.unregister(this) }

    override fun onPacket(event: PacketEvent) {
        // Sadece C→S yönünde Login paketini intercept et
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        if (event.packetId  != BedrockPacketIds.LOGIN) return

        try {
            val patched = patchLoginPacket(event.data)
            if (patched != null && !patched.contentEquals(event.data)) {
                event.modifiedData = patched
                OverlayLogger.i(TAG, "✅ Login chain kendi key'imizle güncellendi")
            }
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Login patch hatası — orijinal iletiliyor: ${e.message}", e)
            // Hata durumunda orijinali ilet, bağlantıyı kesme
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Login Paketi Patch
    // ─────────────────────────────────────────────────────────────────────

    private fun patchLoginPacket(data: ByteArray): ByteArray? {
        val privKey = HandshakeKeyHolder.privateKey as? ECPrivateKey
        val pubKey  = HandshakeKeyHolder.publicKey  as? ECPublicKey
        if (privKey == null || pubKey == null) {
            OverlayLogger.w(TAG, "HandshakeKeyHolder boş — Login patch atlandı")
            return null
        }

        // Login paketi formatı (Bedrock):
        //   [varint] packetId = 0x01
        //   [int32 LE] protocolVersion
        //   [varint] chainDataLen
        //   [string JSON] chainData  ← {"chain": ["JWT1", "JWT2", ...]}
        //   [varint] skinDataLen
        //   [bytes]  skinData        ← JWT (kimlik + skin bilgileri)

        var pos = 0

        // 1. packetId varint
        val (_, p0) = PacketHelper.readVarInt(data, pos); pos = p0

        // 2. protocolVersion int32 LE
        if (pos + 4 > data.size) return null
        val protoBytes = data.copyOfRange(pos, pos + 4); pos += 4

        // 3. chainData length (varint)
        val (chainDataLen, p1) = PacketHelper.readVarInt(data, pos); pos = p1
        if (chainDataLen <= 0 || pos + chainDataLen > data.size) return null

        val chainDataJson = String(data, pos, chainDataLen, Charsets.UTF_8)
        pos += chainDataLen

        // 4. skinData length (varint)
        val skinDataStart = pos
        val (skinDataLen, p2) = PacketHelper.readVarInt(data, pos); pos = p2
        if (skinDataLen <= 0 || pos + skinDataLen > data.size) return null
        val skinDataJwt = String(data, pos, skinDataLen, Charsets.UTF_8)
        pos += skinDataLen

        // ── Chain'i parse et ──────────────────────────────────────────────
        val chainRoot = JSONObject(chainDataJson)
        val chain     = chainRoot.getJSONArray("chain")

        if (chain.length() == 0) {
            OverlayLogger.w(TAG, "Login chain boş")
            return null
        }

        // Public key'imizi Base64 olarak encode et (SubjectPublicKeyInfo DER)
        val ourPubKeyB64 = Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)

        // ── Chain'deki son JWT'yi bul — "identityPublicKey" içeren ──────────
        // Bedrock chain genelde 3 JWT içerir:
        //   [0] Mojang imzalı root (identityPublicKey = Mojang'ın key'i)
        //   [1] Ara (bir öncekinin key'iyle imzalanmış)
        //   [2] Son (Minecraft'ın ürettiği key — BİZİM DEĞİŞTİRECEĞİMİZ)
        // Ama bazı versiyonlarda 1 JWT olur (offline mod) — o zaman onu değiştir.

        val newChain = JSONArray()

        for (i in 0 until chain.length()) {
            val jwt = chain.getString(i)

            if (i == chain.length() - 1) {
                // Son JWT: identityPublicKey'i bizim key'imizle değiştir ve yeniden imzala
                val patchedJwt = patchLastChainJwt(jwt, privKey, ourPubKeyB64)
                newChain.put(patchedJwt ?: jwt)
            } else {
                // Diğer JWT'ler: olduğu gibi bırak
                newChain.put(jwt)
            }
        }

        // ── SkinData JWT'sini de patch et ─────────────────────────────────
        // SkinData JWT'si de identityPublicKey içerir.
        // Bu da bizim key'imizle yeniden imzalanmalı.
        val patchedSkinJwt = patchSkinDataJwt(skinDataJwt, privKey, ourPubKeyB64) ?: skinDataJwt

        // ── Yeni Login paketini oluştur ───────────────────────────────────
        val newChainDataJson = JSONObject().put("chain", newChain).toString()
        val newChainBytes    = newChainDataJson.toByteArray(Charsets.UTF_8)
        val newSkinBytes     = patchedSkinJwt.toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream()

        // packetId
        PacketHelper.writeVarInt(out, BedrockPacketIds.LOGIN)

        // protocolVersion
        out.write(protoBytes)

        // chainData
        PacketHelper.writeVarInt(out, newChainBytes.size)
        out.write(newChainBytes)

        // skinData
        PacketHelper.writeVarInt(out, newSkinBytes.size)
        out.write(newSkinBytes)

        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Chain'deki Son JWT'yi Patch Et
    // ─────────────────────────────────────────────────────────────────────

    private fun patchLastChainJwt(
        originalJwt: String,
        privateKey : ECPrivateKey,
        ourPubKeyB64: String
    ): String? {
        return try {
            val parts = originalJwt.split(".")
            if (parts.size != 3) return null

            // Payload'u decode et
            val payloadJson = String(
                Base64.decode(urlSafeB64Pad(parts[1]), Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val payload = JSONObject(payloadJson)

            // identityPublicKey'i bizim key'imizle değiştir
            payload.put("identityPublicKey", ourPubKeyB64)

            // Header: alg ve x5u güncelle
            val header = JSONObject()
            header.put("alg", "ES256")
            header.put("x5u", ourPubKeyB64)

            // Yeni JWT'yi imzala
            val newHeaderB64  = b64Url(header.toString().toByteArray(Charsets.UTF_8))
            val newPayloadB64 = b64Url(payload.toString().toByteArray(Charsets.UTF_8))
            val signingInput  = "$newHeaderB64.$newPayloadB64"

            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(signingInput.toByteArray(Charsets.US_ASCII))
            val derSig = signer.sign()
            val rawSig = derToRaw(derSig)
            val sigB64 = b64Url(rawSig)

            "$signingInput.$sigB64"
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Chain JWT patch hatası: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SkinData JWT'sini Patch Et
    // ─────────────────────────────────────────────────────────────────────

    private fun patchSkinDataJwt(
        originalJwt: String,
        privateKey : ECPrivateKey,
        ourPubKeyB64: String
    ): String? {
        return try {
            val parts = originalJwt.split(".")
            if (parts.size != 3) return null

            val payloadJson = String(
                Base64.decode(urlSafeB64Pad(parts[1]), Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val payload = JSONObject(payloadJson)

            // identityPublicKey varsa değiştir
            if (payload.has("identityPublicKey")) {
                payload.put("identityPublicKey", ourPubKeyB64)
            }

            val header = JSONObject()
            header.put("alg", "ES256")
            header.put("x5u", ourPubKeyB64)

            val newHeaderB64  = b64Url(header.toString().toByteArray(Charsets.UTF_8))
            val newPayloadB64 = b64Url(payload.toString().toByteArray(Charsets.UTF_8))
            val signingInput  = "$newHeaderB64.$newPayloadB64"

            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(signingInput.toByteArray(Charsets.US_ASCII))
            val rawSig = derToRaw(signer.sign())

            "$signingInput.${b64Url(rawSig)}"
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "SkinData JWT patch hatası: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Yardımcılar
    // ─────────────────────────────────────────────────────────────────────

    private fun b64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun urlSafeB64Pad(s: String): String =
        s + "=".repeat((4 - s.length % 4) % 4)

    /** DER ECDSA imzasını 64 byte raw (r||s) formatına çevirir */
    private fun derToRaw(der: ByteArray): ByteArray {
        var off = 2
        check(der[0] == 0x30.toByte())

        check(der[off] == 0x02.toByte()); off++
        val rLen = der[off++].toInt() and 0xFF
        val r    = der.copyOfRange(off, off + rLen); off += rLen

        check(der[off] == 0x02.toByte()); off++
        val sLen = der[off++].toInt() and 0xFF
        val s    = der.copyOfRange(off, off + sLen)

        return padOrTrim(BigInteger(1, r).toByteArray(), 32) +
               padOrTrim(BigInteger(1, s).toByteArray(), 32)
    }

    private fun padOrTrim(bytes: ByteArray, size: Int): ByteArray = when {
        bytes.size == size -> bytes
        bytes.size >  size -> bytes.copyOfRange(bytes.size - size, bytes.size)
        else               -> ByteArray(size - bytes.size) + bytes
    }
}
