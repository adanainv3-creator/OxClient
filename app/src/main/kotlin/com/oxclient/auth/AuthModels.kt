package com.oxclient.auth

/**
 * SavedAccount — kalıcı olarak saklanan hesap bilgileri.
 *
 * AccountManager tarafından DataStore'a serialize edilir.
 * MicrosoftAuthManager'ın [AuthState.Success]'i bu modeli üretir.
 *
 * @param gamertag      Xbox Live görünen adı
 * @param refreshToken  OAuth refresh token (token yenileme için)
 * @param mcToken       Minecraft Bedrock JWT chain (JSON array string)
 *                      LoginPacketListener bunu doğrudan chain'e enjekte eder
 * @param expireTimeMs  Token'ın geçerliliğinin biteceği Unix zaman damgası (ms)
 * @param xuid          Xbox User ID (opsiyonel; istatistik/sunucu doğrulama için)
 */
data class SavedAccount(
    val gamertag: String,
    val refreshToken: String,
    val mcToken: String,
    val expireTimeMs: Long,
    val xuid: String = "",
    // Chain'in ilk linkini (deviceJwt) imzalayan EC private key (PKCS8, Base64).
    // Sunucu, LoginPacket.clientJwt'nin (skin/client data) imzasını bu key'in
    // public karşılığına göre doğruluyor — bu yüzden clientJwt de relay
    // tarafında bu key ile yeniden imzalanmalı (bkz. LoginPacketListener).
    // Boşsa (eski kayıtlı hesap) — tekrar login gerekir.
    val privateKeyB64: String = "",
    // Aynı keypair'in public key'i (X.509 SPKI, URL-safe Base64) — clientJwt'yi
    // yeniden imzalarken JWT header'ındaki "x5u" alanı için gerekli.
    val publicKeyB64: String = ""
) {
    /** Token'ın süresi dolmuş mu? (5 dakika tolerans ile) */
    fun isExpired(bufferMs: Long = 5 * 60 * 1000L): Boolean =
        System.currentTimeMillis() >= expireTimeMs - bufferMs

    /** Relay için kullanılabilir mi? */
    fun isRelayReady(): Boolean = mcToken.isNotBlank() && privateKeyB64.isNotBlank() && !isExpired()

    override fun toString(): String =
        "SavedAccount(gamertag=$gamertag, xuid=$xuid, expired=${isExpired()})"
}

/**
 * DeviceCodeResponse — /oauth20_connect.srf'den gelen cihaz kodu yanıtı.
 */
data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int
)

/**
 * TokenResponse — /oauth20_token.srf'den gelen token yanıtı.
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val tokenType: String = "bearer"
)

/**
 * XblAuthResult — Xbox Live / XSTS kimlik doğrulama sonucu.
 */
data class XblAuthResult(
    val token: String,
    val userHash: String,     // uhs (User Hash)
    val gamertag: String = ""
)

/**
 * McAuthResult — Minecraft multiplayer.minecraft.net token yanıtı.
 */
data class McAuthResult(
    val chainJson: String,    // JSON array: ["jwt1", "jwt2", ...]
    val gamertag: String = "",
    val privateKeyB64: String = "", // chain[0]'ı (deviceJwt) imzalayan key, PKCS8 Base64
    val publicKeyB64: String = ""   // aynı keypair'in public'i, X.509 SPKI Base64 (URL-safe)
)
