package com.oxclient.core.proxy

import com.oxclient.ui.overlay.OverlayLogger
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
 * ✅ FIX: secp384r1 → secp256r1
 * Bedrock authentication protokolü secp256r1 (P-256) kullanır.
 * secp384r1 ile üretilen key pair ECDH sırasında curve mismatch
 * exception'ına neden olur → handshake sessizce başarısız →
 * şifreleme aktif olmaz veya yanlış key ile aktif olur.
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
     * Yeni EC key pair üret.
     *
     * ✅ FIX: secp256r1 (P-256) — Bedrock auth standardı.
     * MicrosoftAuthManager.fetchMinecraftToken() da secp256r1 kullanır.
     * ECDH için client ve server aynı curve'ü kullanmalı.
     */
    fun generate(): KeyPair {
        return try {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))  // ✅ FIX: secp384r1 → secp256r1
            val pair = gen.generateKeyPair()
            privateKey = pair.private
            publicKey  = pair.public
            OverlayLogger.d(TAG, "EC key pair üretildi (secp256r1)")
            pair
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Key pair üretilemedi: ${e.message}", e)
            throw e
        }
    }

    fun clear() {
        privateKey = null
        publicKey  = null
    }
}
