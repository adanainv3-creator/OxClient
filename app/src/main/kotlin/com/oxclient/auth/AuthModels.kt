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
    val xuid: String = ""
) {
    /** Token'ın süresi dolmuş mu? (5 dakika tolerans ile) */
    fun isExpired(bufferMs: Long = 5 * 60 * 1000L): Boolean =
        System.currentTimeMillis() >= expireTimeMs - bufferMs

    /** Relay için kullanılabilir mi? */
    fun isRelayReady(): Boolean = mcToken.isNotBlank() && !isExpired()

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
    val gamertag: String = ""
)
