package com.oxclient.core.proxy

import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * HandshakeKeyHolder
 *
 * Login sırasında üretilen EC key pair'i tutar.
 * MITMProxy'deki handleHandshake() bu key pair'i kullanarak
 * sunucuyla ECDH yapıp AES secret key türetir.
 *
 * KULLANIM:
 *   1. Login paketi gönderilmeden önce HandshakeKeyHolder.generate() çağır
 *   2. publicKey'i login JWT'sine koy (x5u field)
 *   3. Sunucudan ServerToClientHandshake gelince MITMProxy privateKey'i kullanır
 */
object HandshakeKeyHolder {

    private const val TAG = "HandshakeKeyHolder"

    @Volatile var privateKey: PrivateKey? = null
        private set

    @Volatile var publicKey: PublicKey? = null
        private set

    /**
     * Yeni EC key pair üret (secp384r1 — Bedrock standardı)
     */
    fun generate(): KeyPair {
        return try {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp384r1"))
            val pair = gen.generateKeyPair()
            privateKey = pair.private
            publicKey  = pair.public
            Log.d(TAG, "EC key pair üretildi")
            pair
        } catch (e: Exception) {
            Log.e(TAG, "Key pair üretilemedi: ${e.message}")
            throw e
        }
    }

    fun clear() {
        privateKey = null
        publicKey  = null
    }
}
