package com.oxclient.auth

/**
 * AuthState — MicrosoftAuthManager'ın yayımladığı kimlik doğrulama durumları.
 *
 * Relay tarafından da tüketilir:
 *   - [Success.mcToken] → LoginPacketListener'ın chain enjeksiyonunda kullanılır
 *   - [Success.gamertag] → UI'da gösterilir / ClientIdentification ile doğrulanır
 */
sealed class AuthState {

    /** Hiçbir işlem yok, kullanıcı giriş yapmadı. */
    object Idle : AuthState()

    /** Giriş akışı başlatıldı, arka planda devam ediyor. */
    object Loading : AuthState()

    /**
     * Cihaz kodu üretildi; kullanıcının [verificationUri] adresine gidip
     * [userCode]'u girmesi bekleniyor.
     */
    data class WaitingForUser(
        val userCode: String,
        val verificationUri: String
    ) : AuthState()

    /**
     * Giriş başarılı.
     *
     * @param gamertag  Xbox Live görünen adı
     * @param mcToken   Bedrock JWT chain (JSON array string) veya ham token;
     *                  LoginPacketListener tarafından chain'e enjekte edilir
     */
    data class Success(
        val gamertag: String,
        val mcToken: String
    ) : AuthState()

    /**
     * Giriş başarısız.
     * @param message  Kullanıcıya gösterilecek hata mesajı
     */
    data class Error(val message: String) : AuthState()

    // ── Yardımcı kontroller ───────────────────────────────────────────────

    val isLoggedIn: Boolean get() = this is Success
    val isLoading:  Boolean get() = this is Loading || this is WaitingForUser

    /** Oturumu relay için geçerli kabul et (token var ve boş değil). */
    fun isRelayReady(): Boolean =
        this is Success && mcToken.isNotBlank()
}
