package com.oxclient.auth

// ── Kayıtlı hesap verisi ──────────────────────────────────────────────────────

data class SavedAccount(
    val gamertag    : String,
    val refreshToken: String,
    val mcToken     : String,
    val expireTimeMs: Long      // System.currentTimeMillis() + 6h
)

// ── Auth akışı durumu ─────────────────────────────────────────────────────────

sealed class AuthState {
    /** Başlangıç / çıkış yapıldı */
    object Idle : AuthState()

    /** Device code alınıyor */
    object Loading : AuthState()

    /**
     * Device code hazır → [userCode] kullanıcıya gösterilir,
     * [verificationUri] WebView'da açılır.
     */
    data class WaitingForUser(
        val userCode       : String,
        val verificationUri: String
    ) : AuthState()

    /** Token poll edildi, Minecraft token alındı */
    data class Success(
        val gamertag: String,
        val mcToken : String
    ) : AuthState()

    /** Herhangi bir adımda hata */
    data class Error(val message: String) : AuthState()
}
