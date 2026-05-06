package com.oxclient.auth

data class SavedAccount(
    val gamertag    : String,
    val refreshToken: String,
    val mcToken     : String,
    val expireTimeMs: Long
)

sealed class AuthState {
    object Idle    : AuthState()
    object Loading : AuthState()

    data class WaitingForUser(
        val userCode       : String,
        val verificationUri: String
    ) : AuthState()

    data class Success(
        val gamertag: String,
        val mcToken : String
    ) : AuthState()

    data class Error(val message: String) : AuthState()
}
