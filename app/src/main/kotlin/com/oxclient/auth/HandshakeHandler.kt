package com.oxclient.auth

import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * HandshakeHandler — ECDH el sıkışması için EC key pair yönetimi.
 *
 * MicrosoftAuthManager login akışı başlamadan önce generateKeyPair() çağırır.
 * LoginPacketListener şifreleme handshake'inde private key'i kullanır.
 * SavedAccount.buildSkinJwt() public key'i JWT'ye gömer.
 *
 * Eski: com.oxclient.core.relay.HandshakeKeyHolder
 * Yeni: com.oxclient.auth.HandshakeHandler (auth paketinde daha mantıklı)
 */
object HandshakeHandler {

    private const val TAG = "HandshakeHandler"

    @Volatile var privateKey: PrivateKey? = null
        private set

    @Volatile var publicKey: PublicKey? = null
        private set

    fun generateKeyPair() {
        try {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            val kp: KeyPair = gen.generateKeyPair()
            privateKey = kp.private
            publicKey  = kp.public
            Log.d(TAG, "EC key pair üretildi (secp256r1)")
        } catch (e: Exception) {
            Log.e(TAG, "Key pair üretme hatası: ${e.message}", e)
            throw e
        }
    }

    fun clear() {
        privateKey = null
        publicKey  = null
    }
}
