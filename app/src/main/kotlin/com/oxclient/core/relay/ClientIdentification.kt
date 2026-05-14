package com.oxclient.core.relay

import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * ClientIdentification — LoginPacket'teki JWT chain'ini parse eder ve
 * oyuncu kimliğini (displayName, XUID, UUID, skinData) çıkarır.
 *
 * Minecraft Bedrock login zinciri yapısı:
 *   chain[0] → cihaz / Mojang kök sertifikası
 *   chain[1] → identityPublicKey içeren JWT
 *   chain[2] → extraData: { displayName, XUID, identity(UUID) }
 *
 * extra → SkinData JWT (ayrı alan, chain değil)
 */
data class ClientIdentification(
    val displayName: String,
    val xuid: String,
    val identity: String,        // UUID
    val deviceOs: Int,
    val languageCode: String,
    val clientVersion: String,
    val identityPublicKey: String,
    val chainJson: String,       // Orijinal chain JSON (relay için korunur)
    val extraJson: String        // Orijinal extra (skin JWT)
) {
    companion object {
        private const val TAG = "ClientIdentification"

        /**
         * LoginPacket.chain ve LoginPacket.extra'dan kimlik bilgilerini çıkarır.
         *
         * @param chainJson  {"chain": ["jwt1","jwt2","jwt3"]} formatında JSON string
         * @param extraJson  Skin data JWT string'i
         */
        fun fromLogin(chainJson: String, extraJson: String): ClientIdentification? {
            return try {
                parseChain(chainJson, extraJson)
            } catch (e: Exception) {
                Log.e(TAG, "Login parse hatası: ${e.message}", e)
                null
            }
        }

        private fun parseChain(chainJson: String, extraJson: String): ClientIdentification {
            val root = JSONObject(chainJson)
            val chain = root.getJSONArray("chain")

            var displayName    = "Unknown"
            var xuid           = ""
            var identity       = ""
            var identityPubKey = ""

            // Zincirdeki her JWT'yi tara
            for (i in 0 until chain.length()) {
                val jwt = chain.getString(i)
                val payload = decodeJwtPayload(jwt) ?: continue

                // identityPublicKey (tüm JWT'lerde olabilir)
                val ipk = payload.optString("identityPublicKey")
                if (ipk.isNotBlank()) identityPublicKey = ipk

                // extraData (genellikle son JWT'de)
                if (payload.has("extraData")) {
                    val extra = payload.getJSONObject("extraData")
                    displayName = extra.optString("displayName", "Unknown")
                    xuid        = extra.optString("XUID", "")
                    identity    = extra.optString("identity", "")
                    Log.d(TAG, "extraData bulundu → displayName=$displayName, XUID=$xuid")
                }
            }

            // Skin JWT'den cihaz bilgisi çıkar
            var deviceOs      = 0
            var languageCode  = "en_US"
            var clientVersion = ""

            val skinPayload = decodeJwtPayload(extraJson)
            if (skinPayload != null) {
                deviceOs      = skinPayload.optInt("DeviceOS", 0)
                languageCode  = skinPayload.optString("LanguageCode", "en_US")
                clientVersion = skinPayload.optString("GameVersion", "")
                Log.d(TAG, "SkinJWT → DeviceOS=$deviceOs, lang=$languageCode, ver=$clientVersion")
            }

            return ClientIdentification(
                displayName      = displayName,
                xuid             = xuid,
                identity         = identity,
                deviceOs         = deviceOs,
                languageCode     = languageCode,
                clientVersion    = clientVersion,
                identityPublicKey = identityPubKey,
                chainJson        = chainJson,
                extraJson        = extraJson
            )
        }

        /**
         * JWT'nin payload kısmını Base64 decode ederek JSONObject olarak döndürür.
         */
        private fun decodeJwtPayload(jwt: String): JSONObject? {
            return try {
                val parts = jwt.split(".")
                if (parts.size < 2) return null

                // URL-safe Base64 → standart Base64 → decode
                val padded = parts[1]
                    .replace('-', '+')
                    .replace('_', '/')
                    .let { it + "=".repeat((4 - it.length % 4) % 4) }

                val bytes = Base64.decode(padded, Base64.DEFAULT)
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "JWT payload parse hatası: ${e.message}")
                null
            }
        }
    }

    /** Kısa log formatı */
    override fun toString(): String =
        "ClientIdentification(name=$displayName, xuid=$xuid, os=$deviceOs, ver=$clientVersion)"
}
