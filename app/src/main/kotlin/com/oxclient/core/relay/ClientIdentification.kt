package com.oxclient.core.relay

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlin.random.Random

/**
 * ClientIdentification — RakNet bağlantısı için client kimlik parametreleri.
 * WRelay ClientIdentification'dan birebir adapte edildi.
 */
object ClientIdentification {

    fun generateGuid(): Long {
        val timestamp = System.currentTimeMillis()
        val rand      = Random.nextLong(0, 0xFFFFFF)
        return (timestamp shl 24) or rand
    }

    fun createUnconnectedMagic(): ByteBuf = Unpooled.wrappedBuffer(
        byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
            0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
            0x12, 0x34, 0x56, 0x78
        )
    )

    /** Sunucu korumasına göre uygun config döner */
    fun getClientConfig(forProtectedServer: Boolean = false) = ClientConfig(
        guid             = generateGuid(),
        protocolVersion  = 11,                         // Bedrock RakNet protocol
        unconnectedMagic = createUnconnectedMagic(),
        compatibilityMode = true,
        connectionDelay  = if (forProtectedServer) Random.nextLong(100, 500) else 0L
    )

    data class ClientConfig(
        val guid: Long,
        val protocolVersion: Int,
        val unconnectedMagic: ByteBuf,
        val compatibilityMode: Boolean,
        val connectionDelay: Long
    )
}
