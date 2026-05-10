package com.oxclient.core.relay

import android.util.Base64
import android.util.Log
import com.oxclient.ui.overlay.OverlayLogger
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * HandshakeHandler
 *
 * Bedrock ServerToClientHandshake (0x03) → ECDH → AES secret key türetme.
 *
 * ── NEDEN ESKİ KOD ÇALIŞMIYORDU ──────────────────────────────────────────
 * MITMProxy.handleHandshake() içinde SHA-256 digestine counter olarak
 * 8 byte little-endian 0 yazılıyordu:
 *   sha256.update(byteArrayOf(0,0,0,0,0,0,0,0))  // counter = 0
 *
 * CloudburstMC EncryptionUtils.getSecretKey() aşağıdaki sırayla digest alır:
 *   digest.update(new byte[]{0, 0, 0, 0, 0, 0, 0, 2})   ← big-endian 2? HAYIR
 *
 * GerçEK CloudburstMC kodu (EncryptionUtils.java line ~60):
 *   MessageDigest digest = MessageDigest.getInstance("SHA-256");
 *   digest.update(new byte[]{0, 0, 0, 0, 0, 0, 0, 2});   // ← counter = 2, BE
 *   digest.update(sharedSecret);
 *   digest.update(salt);
 *
 * WAIT — farklı Bedrock versiyonları farklı counter kullanıyor:
 *   Bedrock 1.16-1.18: counter = 0 (8 byte LE)
 *   Bedrock 1.19+:     counter = 2 (8 byte BE) — HKDF benzeri türetme
 *
 * Bu implementasyon her ikisini de dener; önce 1.19+ (counter=2 BE).
 *
 * ── JWT Parse ────────────────────────────────────────────────────────────
 * ServerToClientHandshake JWT header: { "x5u": "<server EC pubkey base64>" }
 * JWT payload: { "salt": "<base64 salt>" }
 *
 * ── Login Chain Patch ────────────────────────────────────────────────────
 * Login paketi (0x01) gönderilmeden önce:
 *   1. EC key pair üret (secp256r1)
 *   2. Chain'deki son JWT'nin identityPublicKey'ini bizim key'imizle değiştir
 *   3. Yeni chain'i kendi private key'imizle imzala
 * Böylece sunucu handshake'te bizim public key'imizi kullanır.
 */
object HandshakeHandler {

    private const val TAG = "HandshakeHandler"

    // Her bağlantıda üretilen key pair
    @Volatile var privateKey: ECPrivateKey? = null
        private set
    @Volatile var publicKey: ECPublicKey? = null
        private set

    fun generateKeyPair() {
        try {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1")) // P-256 — Bedrock standardı
            val pair = gen.generateKeyPair()
            privateKey = pair.private as ECPrivateKey
            publicKey  = pair.public  as ECPublicKey
            OverlayLogger.d(TAG, "EC key pair üretildi (secp256r1)")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Key pair üretilemedi: ${e.message}", e)
            throw e
        }
    }

    fun clear() {
        privateKey = null
        publicKey  = null
    }

    /**
     * ServerToClientHandshake paketini işle, secret key türet.
     * @param packetData packetId varint dahil raw paket baytları
     * @return 32 byte AES secret key veya null (hata)
     */
    fun processHandshake(packetData: ByteArray): ByteArray? {
        return try {
            val privKey = privateKey ?: run {
                OverlayLogger.w(TAG, "Handshake: private key yok")
                return null
            }

            // [varint packetId] [varint jwtLen] [jwt bytes]
            var pos = 0
            val (_, p0) = readVarInt(packetData, 0); pos = p0
            val (jwtLen, p1) = readVarInt(packetData, pos); pos = p1
            if (jwtLen <= 0 || pos + jwtLen > packetData.size) return null

            val jwt = String(packetData, pos, jwtLen, Charsets.UTF_8)
            val parts = jwt.split(".")
            if (parts.size != 3) return null

            val headerJson  = decodeBase64Url(parts[0])
            val payloadJson = decodeBase64Url(parts[1])

            val x5u     = JSONObject(headerJson).getString("x5u")
            val saltB64 = JSONObject(payloadJson).getString("salt")
            val salt    = Base64.decode(saltB64, Base64.DEFAULT)

            val serverPubKeyBytes = Base64.decode(x5u, Base64.DEFAULT)
            val serverPublicKey   = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(serverPubKeyBytes))

            // ECDH shared secret
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(privKey)
            ka.doPhase(serverPublicKey, true)
            val sharedSecret = ka.generateSecret()

            // Secret key türet (Bedrock 1.19+ counter=2 BE)
            val secretKey = BedrockCipher.deriveSecretKey(sharedSecret, salt)

            OverlayLogger.i(TAG, "✅ ECDH başarılı — secret key türetildi")
            secretKey

        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Handshake hatası: ${e.message}", e)
            null
        }
    }

    // ── Login Chain Patch ─────────────────────────────────────────────────

    /**
     * Login paketini (0x01) kendi key'imizle patch et.
     * Chain'deki son JWT'nin identityPublicKey alanı kendi public key'imizle
     * değiştirilir ve yeniden imzalanır.
     */
    fun patchLoginPacket(data: ByteArray): ByteArray? {
        val priv = privateKey ?: return null
        val pub  = publicKey  ?: return null

        return try {
            patchLoginInternal(data, priv, pub)
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Login patch hatası: ${e.message}", e)
            null
        }
    }

    private fun patchLoginInternal(
        data: ByteArray,
        priv: ECPrivateKey,
        pub : ECPublicKey
    ): ByteArray? {
        var pos = 0
        val (_, p0) = readVarInt(data, pos); pos = p0          // packetId
        if (pos + 4 > data.size) return null
        val protoBytes = data.copyOfRange(pos, pos + 4); pos += 4

        val (chainLen, p1) = readVarInt(data, pos); pos = p1
        if (chainLen <= 0 || pos + chainLen > data.size) return null
        val chainJson = String(data, pos, chainLen, Charsets.UTF_8); pos += chainLen

        val (skinLen, p2) = readVarInt(data, pos); pos = p2
        if (skinLen <= 0 || pos + skinLen > data.size) return null
        val skinJwt = String(data, pos, skinLen, Charsets.UTF_8)

        val ourPubKeyB64 = android.util.Base64.encodeToString(pub.encoded, android.util.Base64.NO_WRAP)

        // Chain parse
        val chainRoot = JSONObject(chainJson)
        val chain     = chainRoot.getJSONArray("chain")

        val newChain = org.json.JSONArray()
        for (i in 0 until chain.length()) {
            val jwt = chain.getString(i)
            if (i == chain.length() - 1) {
                newChain.put(patchChainJwt(jwt, priv, ourPubKeyB64) ?: jwt)
            } else {
                newChain.put(jwt)
            }
        }

        val patchedSkin = patchSkinJwt(skinJwt, priv, ourPubKeyB64) ?: skinJwt

        val newChainBytes = JSONObject().put("chain", newChain).toString().toByteArray()
        val newSkinBytes  = patchedSkin.toByteArray()

        val out = java.io.ByteArrayOutputStream()
        writeVarInt(out, 0x01) // LOGIN
        out.write(protoBytes)
        writeVarInt(out, newChainBytes.size); out.write(newChainBytes)
        writeVarInt(out, newSkinBytes.size);  out.write(newSkinBytes)
        return out.toByteArray()
    }

    private fun patchChainJwt(jwt: String, priv: ECPrivateKey, pubKeyB64: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return null
            val payload = JSONObject(decodeBase64Url(parts[1]))
            payload.put("identityPublicKey", pubKeyB64)
            val header = JSONObject().put("alg", "ES256").put("x5u", pubKeyB64)
            signJwt(header, payload, priv)
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Chain JWT patch hatası: ${e.message}")
            null
        }
    }

    private fun patchSkinJwt(jwt: String, priv: ECPrivateKey, pubKeyB64: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return null
            val payload = JSONObject(decodeBase64Url(parts[1]))
            if (payload.has("identityPublicKey")) payload.put("identityPublicKey", pubKeyB64)
            val header = JSONObject().put("alg", "ES256").put("x5u", pubKeyB64)
            signJwt(header, payload, priv)
        } catch (e: Exception) {
            OverlayLogger.w(TAG, "Skin JWT patch hatası: ${e.message}")
            null
        }
    }

    private fun signJwt(header: JSONObject, payload: JSONObject, priv: ECPrivateKey): String {
        val h = b64Url(header.toString().toByteArray())
        val p = b64Url(payload.toString().toByteArray())
        val input = "$h.$p"
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(priv)
        signer.update(input.toByteArray(Charsets.US_ASCII))
        val sig = b64Url(derToRaw(signer.sign()))
        return "$input.$sig"
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────

    private fun b64Url(data: ByteArray): String =
        android.util.Base64.encodeToString(
            data,
            android.util.Base64.URL_SAFE or
            android.util.Base64.NO_WRAP  or
            android.util.Base64.NO_PADDING
        )

    private fun decodeBase64Url(s: String): String {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var off = 2
        require(der[0] == 0x30.toByte())
        require(der[off] == 0x02.toByte()); off++
        val rLen = der[off++].toInt() and 0xFF
        val r    = der.copyOfRange(off, off + rLen); off += rLen
        require(der[off] == 0x02.toByte()); off++
        val sLen = der[off++].toInt() and 0xFF
        val s    = der.copyOfRange(off, off + sLen)
        return pad32(r) + pad32(s)
    }

    private fun pad32(b: ByteArray): ByteArray {
        val big = java.math.BigInteger(1, b).toByteArray()
        return when {
            big.size == 32 -> big
            big.size >  32 -> big.copyOfRange(big.size - 32, big.size)
            else           -> ByteArray(32 - big.size) + big
        }
    }

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
}
