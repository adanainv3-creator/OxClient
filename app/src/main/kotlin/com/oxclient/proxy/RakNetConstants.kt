package com.oxclient.proxy

/**
 * RakNetConstants
 *
 * Minecraft Bedrock Edition RakNet protokolü sabitleri.
 * Protokol versiyonu: 11 (RakNet), Bedrock protocol: 748 (1.21.60)
 */
object RakNetConstants {

    // ── RakNet Offline Message ID ─────────────────────────────────────────
    @JvmField
    val OFFLINE_MESSAGE_ID = byteArrayOf(
        0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(),
        0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
        0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()
    )

    // ── RakNet Paket Tipleri ──────────────────────────────────────────────
    const val ID_UNCONNECTED_PING              : Byte = 0x01
    const val ID_UNCONNECTED_PONG             : Byte = 0x1C
    const val ID_OPEN_CONNECTION_REQUEST_1    : Byte = 0x05
    const val ID_OPEN_CONNECTION_REPLY_1      : Byte = 0x06
    const val ID_OPEN_CONNECTION_REQUEST_2    : Byte = 0x07
    const val ID_OPEN_CONNECTION_REPLY_2      : Byte = 0x08
    const val ID_CONNECTION_REQUEST           : Byte = 0x09
    const val ID_CONNECTION_REQUEST_ACCEPTED  : Byte = 0x10
    const val ID_NEW_INCOMING_CONNECTION      : Byte = 0x13
    const val ID_DISCONNECT_NOTIFICATION      : Byte = 0x15
    const val ID_INCOMPATIBLE_PROTOCOL_VERSION: Byte = 0x19
    const val ID_CONNECTED_PING               : Byte = 0x00
    const val ID_CONNECTED_PONG               : Byte = 0x03
    const val ID_ACK                          : Byte = 0xC0.toByte()
    const val ID_NACK                         : Byte = 0xA0.toByte()

    // Frame Set Packet range: 0x80..0x8F
    const val FRAME_SET_PACKET_BEGIN : Int = 0x80
    const val FRAME_SET_PACKET_END   : Int = 0x8F

    // ── RakNet Güvenilirlik Tipleri ───────────────────────────────────────
    const val UNRELIABLE                  : Byte = 0
    const val UNRELIABLE_SEQUENCED        : Byte = 1
    const val RELIABLE                    : Byte = 2
    const val RELIABLE_ORDERED            : Byte = 3
    const val RELIABLE_SEQUENCED          : Byte = 4
    const val UNRELIABLE_WITH_ACK_RECEIPT : Byte = 5
    const val RELIABLE_WITH_ACK_RECEIPT   : Byte = 6
    const val RELIABLE_ORDERED_WITH_ACK   : Byte = 7

    // ── Bedrock Protokol Sabitleri ────────────────────────────────────────
    const val RAKNET_PROTOCOL_VERSION  : Byte  = 11
    const val BEDROCK_PROTOCOL_VERSION : Int   = 748          // 1.21.60
    const val BEDROCK_VERSION_STRING   : String = "1.21.60"

    // ── MTU Değerleri ─────────────────────────────────────────────────────
    const val MTU_MAX     : Int = 1492
    const val MTU_MIN     : Int = 400
    const val MTU_DEFAULT : Int = 1400

    // ── Yerel Proxy Portları ──────────────────────────────────────────────
    const val LOCAL_BIND_PORT  : Int = 19132   // Minecraft'ın bağlandığı port
    const val LOCAL_PROXY_PORT : Int = 19135   // Relay'in upstream'e bağlandığı kaynak port

    // ── Timeout Değerleri (ms) ────────────────────────────────────────────
    const val CONNECTION_TIMEOUT_MS : Long = 15_000L
    const val SESSION_TIMEOUT_MS    : Long = 30_000L
    const val PING_INTERVAL_MS      : Long = 5_000L

    // ── Bedrock Batch Paketi ID ───────────────────────────────────────────
    const val GAME_PACKET_ID : Byte = 0xFE.toByte()   // Bedrock "Batch" / "Game" paketi
}
