package com.oxclient.events

/**
 * Bir paketin hangi yönde aktığını belirtir.
 */
enum class PacketDirection {
    CLIENT_BOUND,   // Sunucudan MC istemcisine
    SERVER_BOUND    // MC istemcisinden sunucuya
}

/**
 * PacketEvent
 *
 * Her intercept edilen paket için oluşturulan olay nesnesi.
 * Modüller bu nesneyi alır, [data]'yı değiştirebilir veya [cancelled] = true yapabilir.
 */
data class PacketEvent(
    val id        : Int,
    val direction : PacketDirection,
    var data      : ByteArray,          // değiştirilebilir
    val mutable   : Boolean = true,
    var cancelled : Boolean = false     // true → paket iletilmez
) {
    override fun equals(other: Any?): Boolean = other is PacketEvent && id == other.id
    override fun hashCode(): Int = id
}
