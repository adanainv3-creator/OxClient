package com.oxclient.core.relay

import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BedrockCipher
 *
 * Bedrock 1.16.220+ şifreleme: AES-256-CFB8 (her paket için ayrı counter IV).
 *
 * CloudburstMC/Protocol referans: EncryptionUtils.java
 *
 * ── NEDEN ESKİ KOD ÇALIŞMIYORDU ──────────────────────────────────────────
 * Eski PacketProcessor her decrypt/encrypt için aynı sabit IV kullanıyordu
 * (ctrIv[15]=1). Bedrock protokolü her paketin IV'ünü paket sayacından
 * (sendCounter / recvCounter) türetiyor. Sabit IV ile:
 *   - İlk paket çözülür (şans).
 *   - Sonraki tüm paketler yanlış keystream → bozuk veri → bağlantı kopar.
 *
 * ── DOĞRU IV HESAPLAMA ────────────────────────────────────────────────────
 * CloudburstMC EncryptionUtils.createCipher():
 *   iv[0..7]  = secretKey[0..7]      (ilk 8 byte sabit)
 *   iv[8..15] = counter_le(sendCount) (8 byte little-endian paket sayacı)
 * Algorithm: AES/CFB8/NoPadding
 *
 * ── CHECKSUM ─────────────────────────────────────────────────────────────
 * Her pakete 8 byte SHA-256 checksum ekleniyor (son 8 byte):
 *   SHA256( counter_le(N) || plaintext || secretKey )[0..7]
 *
 * ── HANDSHAKE SECRET KEY ─────────────────────────────────────────────────
 * CloudburstMC EncryptionUtils.getSecretKey():
 *   sharedSecret = ECDH(clientPriv, serverPub)
 *   digest = SHA256( counter_be(1) || sharedSecret || salt )
 *                    ^^^^^^^^^^^
 *                    8 byte big-endian 1 (NOT 0, NOT little-endian!)
 */
class BedrockCipher(secretKey: ByteArray) {

    companion object {
        private const val TAG = "BedrockCipher"

        /**
         * CloudburstMC EncryptionUtils.getSecretKey() ile aynı.
         * counter = 1, big-endian, 8 bytes.
         */
        fun deriveSecretKey(sharedSecret: ByteArray, salt: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            // counter = 1, big-endian 8 bytes
            digest.update(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
            digest.update(sharedSecret)
            digest.update(salt)
            return digest.digest() // 32 bytes
        }
    }

    private val key = secretKey.copyOf(32)

    // Gönderme ve alma sayaçları — thread-safe erişim için @Volatile
    @Volatile private var sendCount = 0L
    @Volatile private var recvCount = 0L

    /**
     * S→C paketini deşifre et.
     * - Son 8 byte checksum — çıkarılır.
     * - Geriye kalan: CFB8 decrypt edilir.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size <= 8) return ciphertext
        return try {
            val cipher = createCipher(Cipher.DECRYPT_MODE, recvCount)
            recvCount++
            val decrypted = cipher.doFinal(ciphertext)
            // Son 8 byte checksum — at
            if (decrypted.size > 8) decrypted.copyOf(decrypted.size - 8)
            else decrypted
        } catch (e: Exception) {
            Log.w(TAG, "Decrypt hata: ${e.message}")
            ciphertext
        }
    }

    /**
     * C→S paketini şifrele.
     * - 8 byte SHA-256 checksum ekle.
     * - CFB8 encrypt et.
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        return try {
            val checksum = computeChecksum(plaintext, sendCount)
            val withChecksum = plaintext + checksum
            val cipher = createCipher(Cipher.ENCRYPT_MODE, sendCount)
            sendCount++
            cipher.doFinal(withChecksum)
        } catch (e: Exception) {
            Log.w(TAG, "Encrypt hata: ${e.message}")
            plaintext
        }
    }

    /**
     * IV oluştur: key[0..7] + counter_le(count)
     * AES bloğu 16 byte → 8 + 8 = 16.
     */
    private fun createCipher(mode: Int, count: Long): Cipher {
        val iv = ByteArray(16)
        // İlk 8 byte: secret key'in ilk 8 byte'ı
        System.arraycopy(key, 0, iv, 0, 8)
        // Son 8 byte: little-endian counter
        var c = count
        for (i in 8 until 16) {
            iv[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CFB8/NoPadding")
        cipher.init(mode, secretKey, IvParameterSpec(iv))
        return cipher
    }

    private fun computeChecksum(data: ByteArray, counter: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val counterBytes = ByteArray(8)
        var c = counter
        for (i in 0 until 8) {
            counterBytes[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        digest.update(counterBytes)
        digest.update(data)
        digest.update(key)
        return digest.digest().copyOf(8)
    }
}
