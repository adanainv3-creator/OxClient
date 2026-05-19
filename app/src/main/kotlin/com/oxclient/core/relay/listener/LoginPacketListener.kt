package com.oxclient.core.relay.listener

import android.util.Base64
import android.util.Log
import com.oxclient.auth.MicrosoftAuthManager
import com.oxclient.core.relay.ClientIdentification
import com.oxclient.core.relay.ConnectionManager
import com.oxclient.core.relay.OxRelaySession
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.packet.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class LoginPacketListener : OxPacketListener {

    companion object { private const val TAG = "LoginPacketListener" }

    override val priority: Int = 0

    @Volatile var clientIdentification: ClientIdentification? = null
        private set

    @Volatile private var loginProcessed = false

    override fun onSessionStart(session: OxRelaySession) {
        loginProcessed       = false
        clientIdentification = null
        ConnectionManager.onHandshaking()
    }

    override fun onSessionEnd(session: OxRelaySession) {
        ConnectionManager.onDisconnected()
    }

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {
            is RequestNetworkSettingsPacket -> {
                Log.d(TAG, "RequestNetworkSettings protocol=${packet.protocolVersion}")
                // Asıl işlem AutoCodecListener'da yapılıyor (priority=-10, önce çalışır)
            }
            is LoginPacket -> {
                if (!loginProcessed) {
                    loginProcessed = true
                    processLogin(packet, session)
                } else {
                    Log.w(TAG, "Tekrarlanan LoginPacket engellendi")
                }
            }
            is ClientToServerHandshakePacket -> {
                Log.d(TAG, "ClientToServerHandshake → şifreleme aktif")
            }
        }
        return true
    }

    override fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        when (packet) {

            // ── NetworkSettingsPacket ─────────────────────────────────────
            // Server bu paketi gönderince server-side compression açılır.
            // Client-side compression AutoCodecListener'da zaten açıldı.
            // WRelay akışı: server NetworkSettings → client compression aç → Login gönder
            is NetworkSettingsPacket -> {
                Log.d(TAG, "NetworkSettings compression=${packet.compressionAlgorithm} threshold=${packet.compressionThreshold}")
                try {
                    if (packet.compressionThreshold > 0) {
                        session.serverSession?.setCompression(packet.compressionAlgorithm)
                        Log.i(TAG, "Server compression açıldı: ${packet.compressionAlgorithm}")
                    } else {
                        session.serverSession?.setCompression(PacketCompressionAlgorithm.NONE)
                        Log.i(TAG, "Server compression kapalı")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Server compression ayarlanamadı: ${e.message}")
                }
                // paketi client'a iletme — client zaten AutoCodecListener'dan aldı
                return false
            }

            is ServerToClientHandshakePacket -> {
                Log.d(TAG, "ServerToClientHandshake jwt=${packet.jwt?.take(60)}…")
            }
            is PlayStatusPacket -> {
                Log.d(TAG, "PlayStatus: ${packet.status}")
                when (packet.status) {
                    PlayStatusPacket.Status.LOGIN_SUCCESS -> {
                        Log.i(TAG, "Login başarılı")
                        ConnectionManager.onHandshaking()
                    }
                    PlayStatusPacket.Status.PLAYER_SPAWN -> {
                        Log.i(TAG, "Oyuncu spawn")
                        ConnectionManager.onGameStarted()
                    }
                    else -> {
                        val s = packet.status.name
                        if (s.contains("FAIL", ignoreCase = true))
                            Log.e(TAG, "Login başarısız: $s")
                    }
                }
            }
            is StartGamePacket -> {
                Log.i(TAG, "StartGame → oyun içi")
                ConnectionManager.onGameStarted()
            }
            is DisconnectPacket -> {
                Log.w(TAG, "Server disconnect: ${packet.kickMessage}")
            }
        }
        return true
    }

    private fun processLogin(packet: LoginPacket, session: OxRelaySession) {
        Log.d(TAG, "LoginPacket işleniyor protocol=${packet.protocolVersion}")

        val chainJson = extractChainJson(packet)
        val extraJson = extractExtraJson(packet)

        try {
            clientIdentification = ClientIdentification.fromLogin(
                chainJson = chainJson,
                extraJson = extraJson
            )
            Log.i(TAG, "Client kimliği: $clientIdentification")
        } catch (e: Exception) {
            Log.w(TAG, "ClientIdentification parse hatası: ${e.message}")
        }

        injectAuthChain(packet, chainJson, extraJson)
    }

    private fun extractChainJson(packet: LoginPacket): String {
        return try {
            val raw = packet.chain
            when {
                raw == null    -> "{}"
                raw is String  -> raw
                raw is List<*> -> JSONObject().apply {
                    put("chain", JSONArray(raw.map { it.toString() }))
                }.toString()
                else           -> raw.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "chain extract hatası: ${e.message}")
            "{}"
        }
    }

    private fun extractExtraJson(packet: LoginPacket): String {
        return try {
            packet.extra?.toString() ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "extra extract hatası: ${e.message}")
            ""
        }
    }

    private fun injectAuthChain(packet: LoginPacket, originalChain: String, originalExtra: String) {
        val savedChainJson = MicrosoftAuthManager.getActiveChainForRelay()
        if (savedChainJson.isNullOrBlank()) {
            Log.w(TAG, "Kayıtlı chain yok — orijinal iletiliyor (offline)")
            return
        }

        try {
            val keyGen    = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair   = keyGen.generateKeyPair()
            val pubKey    = keyPair.public  as ECPublicKey
            val privKey   = keyPair.private as ECPrivateKey
            val pubKeyB64 = Base64.encodeToString(
                pubKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val savedJwts = parseSavedChain(savedChainJson)
            val deviceJwt = buildDeviceJwt(privKey, pubKeyB64)
            val fullChain = buildList {
                add(deviceJwt)
                addAll(savedJwts.filter { it != deviceJwt })
            }

            val newChainWrapper = JSONObject().apply {
                put("chain", JSONArray(fullChain))
            }.toString()

            var injected = false
            try {
                val chainField = packet.javaClass.getDeclaredField("chain")
                chainField.isAccessible = true
                chainField.set(packet, newChainWrapper)
                Log.i(TAG, "Chain enjekte edildi (reflection): ${fullChain.size} JWT")
                injected = true
            } catch (rf: Exception) {
                Log.w(TAG, "Reflection başarısız: ${rf.message}")
            }

            if (!injected) {
                try {
                    packet.javaClass.getMethod("setChain", String::class.java)
                        .invoke(packet, newChainWrapper)
                    Log.i(TAG, "Chain enjekte edildi (setter): ${fullChain.size} JWT")
                } catch (me: Exception) {
                    Log.e(TAG, "Chain enjeksiyonu tamamen başarısız: ${me.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Chain enjeksiyonu hatası: ${e.message}", e)
        }
    }

    private fun parseSavedChain(chainJson: String): List<String> {
        return try {
            when {
                chainJson.trimStart().startsWith("{") -> {
                    val arr = JSONObject(chainJson).optJSONArray("chain")
                    if (arr != null) (0 until arr.length()).map { arr.getString(it) }
                    else listOf(chainJson)
                }
                chainJson.trimStart().startsWith("[") -> {
                    val arr = JSONArray(chainJson)
                    (0 until arr.length()).map { arr.getString(it) }
                }
                else -> listOf(chainJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chain parse hatası: ${e.message}")
            listOf(chainJson)
        }
    }

    private fun buildDeviceJwt(privKey: ECPrivateKey, pubKeyB64: String): String {
        val now     = System.currentTimeMillis() / 1000L
        val header  = b64Url("""{"alg":"ES256","x5u":"$pubKeyB64"}""".toByteArray())
        val payload = b64Url((
            """{"certificateAuthority":true,"identityPublicKey":"$pubKeyB64",""" +
            """"exp":${now + 86400},"nbf":${now - 1},"iat":$now,"iss":"Minecraft"}"""
        ).toByteArray())
        val sigInput = "$header.$payload"
        val signer   = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privKey)
        signer.update(sigInput.toByteArray(Charsets.US_ASCII))
        return "$sigInput.${b64Url(derToRaw(signer.sign()))}"
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        check(der[i] == 0x02.toByte()) { "DER: r tag bekleniyor, got 0x${der[i].toString(16)}" }
        i++
        val rLen = der[i++].toInt() and 0xFF
        val r    = der.copyOfRange(i, i + rLen)
        i += rLen
        check(der[i] == 0x02.toByte()) { "DER: s tag bekleniyor, got 0x${der[i].toString(16)}" }
        i++
        val sLen = der[i++].toInt() and 0xFF
        val s    = der.copyOfRange(i, i + sLen)
        return padOrTrim(BigInteger(1, r).toByteArray(), 32) +
               padOrTrim(BigInteger(1, s).toByteArray(), 32)
    }

    private fun padOrTrim(b: ByteArray, sz: Int) = when {
        b.size == sz -> b
        b.size > sz  -> b.copyOfRange(b.size - sz, b.size)
        else         -> ByteArray(sz - b.size) + b
    }

    private fun b64Url(data: ByteArray) =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
